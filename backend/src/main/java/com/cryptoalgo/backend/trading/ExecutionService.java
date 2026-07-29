package com.cryptoalgo.backend.trading;

import com.cryptoalgo.backend.common.AuditService;
import com.cryptoalgo.backend.common.SecretCrypto;
import com.cryptoalgo.backend.config.AppProperties;
import com.cryptoalgo.backend.domain.Bot;
import com.cryptoalgo.backend.domain.Position;
import com.cryptoalgo.backend.domain.Signal;
import com.cryptoalgo.backend.domain.TradeOrder;
import com.cryptoalgo.backend.market.MarketRulesService;
import com.cryptoalgo.backend.market.TickerService;
import com.cryptoalgo.backend.repo.BotRepository;
import com.cryptoalgo.backend.repo.ExchangeKeyRepository;
import com.cryptoalgo.backend.repo.PositionRepository;
import com.cryptoalgo.backend.repo.StrategyRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Execution engine: fans a strategy signal out to every running bot subscribed
 * to that strategy, applies risk checks, then places paper or live orders.
 *
 * Every entry carries a stop-loss and a target derived from the strategy's
 * config (stoploss / minimal_roi["0"]). Live entries are sized against the
 * account's available funds; after the market buy an exchange-side stop_limit
 * SL leg is placed (CoinDCX spot has no OCO, so the target leg is watched by
 * {@link PositionGuardService}).
 */
@Service
public class ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

    /** SL limit price sits 0.3% under the stop trigger so the limit fills. */
    private static final BigDecimal SL_LIMIT_SLIP = new BigDecimal("0.997");

    record Risk(BigDecimal stoploss, BigDecimal targetRoi) {}

    private final BotRepository bots;
    private final PositionRepository positions;
    private final ExchangeKeyRepository keys;
    private final StrategyRepository strategies;
    private final CoinDcxTradeClient trade;
    private final CoinDcxFuturesClient futuresClient;
    private final TickerService ticker;
    private final MarketRulesService rules;
    private final SecretCrypto crypto;
    private final R2dbcEntityTemplate template;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final AppProperties props;

    public ExecutionService(BotRepository bots, PositionRepository positions,
                            ExchangeKeyRepository keys, StrategyRepository strategies,
                            CoinDcxTradeClient trade, CoinDcxFuturesClient futuresClient,
                            TickerService ticker, MarketRulesService rules, SecretCrypto crypto,
                            R2dbcEntityTemplate template, AuditService audit,
                            ObjectMapper mapper, AppProperties props) {
        this.bots = bots;
        this.positions = positions;
        this.keys = keys;
        this.strategies = strategies;
        this.trade = trade;
        this.futuresClient = futuresClient;
        this.ticker = ticker;
        this.rules = rules;
        this.crypto = crypto;
        this.template = template;
        this.audit = audit;
        this.mapper = mapper;
        this.props = props;
    }

    /** Returns the number of bots that acted on the signal. */
    public Mono<Long> process(Signal signal) {
        return strategies.findById(signal.strategyId())
                .map(s -> parseRisk(s.config()))
                .defaultIfEmpty(defaultRisk())
                .flatMap(risk -> bots.findByStrategyIdAndStatus(signal.strategyId(), "RUNNING")
                        .filter(bot -> !bot.killSwitch())
                        .filter(bot -> bot.tenantId().equals(signal.tenantId()))
                        .filter(bot -> botTradesPair(bot, signal.pair()))
                        .flatMap(bot -> handle(bot, signal, risk)
                                .onErrorResume(e -> {
                                    log.error("Bot {} failed to handle signal {}", bot.id(), signal.id(), e);
                                    return Mono.empty();
                                }))
                        .count());
    }

    private Risk parseRisk(io.r2dbc.postgresql.codec.Json config) {
        try {
            JsonNode cfg = mapper.readTree(config.asString());
            BigDecimal stoploss = cfg.hasNonNull("stoploss")
                    ? new BigDecimal(cfg.get("stoploss").asText())
                    : BigDecimal.valueOf(props.pipeline().defaultStoploss());
            BigDecimal roi = BigDecimal.valueOf(props.pipeline().defaultTargetRoi());
            JsonNode minimalRoi = cfg.path("minimal_roi");
            if (minimalRoi.isObject() && minimalRoi.hasNonNull("0"))
                roi = new BigDecimal(minimalRoi.get("0").asText());
            if (stoploss.signum() >= 0) stoploss = stoploss.negate();
            return new Risk(stoploss, roi);
        } catch (Exception e) {
            return defaultRisk();
        }
    }

    private Risk defaultRisk() {
        return new Risk(BigDecimal.valueOf(props.pipeline().defaultStoploss()),
                BigDecimal.valueOf(props.pipeline().defaultTargetRoi()));
    }

    private boolean botTradesPair(Bot bot, String pair) {
        return bot.pairs() != null && bot.pairs().asString().contains("\"" + pair + "\"");
    }

    private Mono<Bot> handle(Bot bot, Signal signal, Risk risk) {
        boolean isEntry = signal.action().startsWith("ENTRY");
        return isEntry ? enter(bot, signal, risk) : exit(bot, signal);
    }

    // ------------------------------------------------------------------ entry

    private Mono<Bot> enter(Bot bot, Signal signal, Risk risk) {
        String side = signal.action().equals("ENTRY_SHORT") ? "SHORT" : "LONG";
        if ("SHORT".equals(side) && "SPOT".equals(bot.marketType())) {
            log.debug("Bot {} ignores short entry on spot market", bot.id());
            return Mono.empty();
        }
        return positions.countByBotIdAndStatus(bot.id(), "OPEN")
                .filter(open -> open < bot.maxOpenTrades())
                .flatMap(open -> positions.findByBotIdAndPairAndStatus(bot.id(), signal.pair(), "OPEN")
                        .hasElement()
                        .filter(hasOpen -> !hasOpen)
                        .flatMap(x -> resolveFillPrice(bot, signal)
                                .flatMap(price -> {
                                    BigDecimal slPrice;
                                    BigDecimal targetPrice;
                                    if ("SHORT".equals(side)) {
                                        slPrice = price.multiply(BigDecimal.ONE.subtract(risk.stoploss()))
                                                .setScale(10, RoundingMode.HALF_UP);
                                        targetPrice = price.multiply(BigDecimal.ONE.subtract(risk.targetRoi()))
                                                .setScale(10, RoundingMode.HALF_UP);
                                    } else {
                                        slPrice = price.multiply(BigDecimal.ONE.add(risk.stoploss()))
                                                .setScale(10, RoundingMode.HALF_UP);
                                        targetPrice = price.multiply(BigDecimal.ONE.add(risk.targetRoi()))
                                                .setScale(10, RoundingMode.HALF_UP);
                                    }
                                    if ("PAPER".equals(bot.mode())) {
                                        BigDecimal qty = bot.stakeAmount()
                                                .divide(price, MathContext.DECIMAL64)
                                                .setScale(8, RoundingMode.DOWN);
                                        if (qty.signum() <= 0) return Mono.empty();
                                        return paperFill(bot, signal, side, qty, price, slPrice, targetPrice);
                                    }
                                    int slots = (int) Math.max(1, bot.maxOpenTrades() - open);
                                    return liveEnter(bot, signal, side, price, slPrice, targetPrice, slots);
                                })))
                .thenReturn(bot);
    }

    // ------------------------------------------------------------------- exit

    private Mono<Bot> exit(Bot bot, Signal signal) {
        return positions.findByBotIdAndPairAndStatus(bot.id(), signal.pair(), "OPEN")
                .flatMap(pos -> resolveFillPrice(bot, signal)
                        .flatMap(price -> "PAPER".equals(bot.mode())
                                ? closePaperPosition(bot, pos, price, signal.id(), "SIGNAL_EXIT")
                                : liveExit(bot, pos, price, signal.id(), "SIGNAL_EXIT")))
                .thenReturn(bot);
    }

    // ---------------------------------------------------------------- pricing

    /**
     * FUTURES: CoinDCX live futures last/mark only — never invent a price.
     * Missing price → skip fill (fail closed).
     * SPOT: prefer live ticker, fall back to signal close.
     */
    private Mono<BigDecimal> resolveFillPrice(Bot bot, Signal signal) {
        if ("FUTURES".equals(bot.marketType())) {
            return futuresClient.lastPrice(signal.pair())
                    .filter(p -> p != null && p.signum() > 0)
                    .switchIfEmpty(Mono.defer(() -> {
                        log.warn("Bot {} skipped {}: no CoinDCX futures price for {}",
                                bot.id(), signal.action(), signal.pair());
                        return Mono.empty();
                    }));
        }
        String market = pairToMarket(signal.pair());
        TickerService.Tick tick = ticker.last(market);
        BigDecimal price = tick != null ? tick.lastPrice() : signal.price();
        if (price == null || price.signum() <= 0) {
            log.warn("Bot {} skipped {}: non-positive fill price {}", bot.id(), signal.action(), price);
            return Mono.empty();
        }
        return Mono.just(price);
    }

    // ------------------------------------------------------------------ paper

    private Mono<Void> paperFill(Bot bot, Signal signal, String posSide,
                                 BigDecimal qty, BigDecimal price,
                                 BigDecimal slPrice, BigDecimal targetPrice) {
        String orderSide = "SHORT".equals(posSide) ? "SELL" : "BUY";
        TradeOrder order = new TradeOrder(UUID.randomUUID(), bot.tenantId(), bot.userId(), bot.id(),
                signal.id(), null, "paper-" + UUID.randomUUID(), signal.pair(), orderSide,
                "MARKET_ORDER", bot.marketType(), "PAPER", "FILLED", price, qty, qty, price,
                BigDecimal.ZERO, null, Instant.now(), Instant.now());
        Position pos = new Position(UUID.randomUUID(), bot.tenantId(), bot.userId(), bot.id(),
                signal.pair(), posSide, qty, price, null, bot.leverage(), "OPEN", null,
                slPrice, targetPrice, null, bot.marginCurrency() == null ? "INR" : bot.marginCurrency(),
                Instant.now(), null);
        return template.insert(order).then(template.insert(pos))
                .then(audit.record(bot.tenantId(), bot.userId(), "PAPER_ENTRY", "POSITION", pos.id(),
                        Map.of("pair", signal.pair(), "qty", qty.toPlainString(),
                                "price", price.toPlainString(), "sl", slPrice.toPlainString(),
                                "target", targetPrice.toPlainString())))
                .then();
    }

    /** Close a paper position at the given price (signal exit, SL or target). */
    Mono<Void> closePaperPosition(Bot bot, Position pos, BigDecimal price,
                                  UUID signalId, String reason) {
        String orderSide = "SHORT".equals(pos.side()) ? "BUY" : "SELL";
        TradeOrder order = new TradeOrder(UUID.randomUUID(), bot.tenantId(), bot.userId(), bot.id(),
                signalId, null, "paper-" + UUID.randomUUID(), pos.pair(), orderSide,
                "MARKET_ORDER", bot.marketType(), "PAPER", "FILLED", price, pos.quantity(),
                pos.quantity(), price, BigDecimal.ZERO, null, Instant.now(), Instant.now());
        return template.insert(order).then(closePosition(pos, price))
                .then(audit.record(bot.tenantId(), bot.userId(), "PAPER_EXIT", "POSITION", pos.id(),
                        Map.of("pair", pos.pair(), "exitPrice", price.toPlainString(),
                                "reason", reason)))
                .then();
    }

    Mono<Void> closePosition(Position pos, BigDecimal exitPrice) {
        BigDecimal direction = "SHORT".equals(pos.side()) ? BigDecimal.valueOf(-1) : BigDecimal.ONE;
        BigDecimal pnl = exitPrice.subtract(pos.entryPrice())
                .multiply(pos.quantity()).multiply(direction).multiply(pos.leverage());
        Position closed = new Position(pos.id(), pos.tenantId(), pos.userId(), pos.botId(), pos.pair(),
                pos.side(), pos.quantity(), pos.entryPrice(), exitPrice, pos.leverage(), "CLOSED",
                pnl, pos.slPrice(), pos.targetPrice(), pos.slOrderId(), pos.marginCurrency(),
                pos.openedAt(), Instant.now());
        return positions.save(closed).then();
    }

    // ------------------------------------------------------------------ live

    /**
     * Live entry: size the order against the available balance, place a
     * market buy, then rest a stop_limit SL order on the exchange.
     */
    private Mono<Void> liveEnter(Bot bot, Signal signal, String posSide, BigDecimal price,
                                 BigDecimal slPrice, BigDecimal targetPrice, int slots) {
        if ("FUTURES".equals(bot.marketType())) {
            return liveFuturesEnter(bot, signal, posSide, price, slPrice, targetPrice, slots);
        }
        String market = pairToMarket(signal.pair());
        return withKey(bot).flatMap(key -> trade.balances(key.apiKey(), key.apiSecret())
                .flatMap(balances -> {
                    BigDecimal available = availableBalance(balances, bot.stakeCurrency());
                    BigDecimal stake = bot.stakeAmount()
                            .min(available.divide(BigDecimal.valueOf(slots), MathContext.DECIMAL64));
                    if (stake.signum() <= 0) {
                        return skip(bot, signal, "no available " + bot.stakeCurrency() + " balance");
                    }
                    return rules.rule(market)
                            .defaultIfEmpty(new MarketRulesService.Rule(BigDecimal.ZERO, BigDecimal.ZERO, 8))
                            .flatMap(rule -> {
                                BigDecimal qty = stake.divide(price, MathContext.DECIMAL64)
                                        .setScale(rule.quantityPrecision(), RoundingMode.DOWN);
                                BigDecimal notional = qty.multiply(price);
                                if (qty.compareTo(rule.minQuantity()) < 0
                                        || notional.compareTo(rule.minNotional()) < 0) {
                                    return skip(bot, signal, "funds below market minimum");
                                }
                                return placeLiveEntry(bot, signal, key, market, posSide,
                                        qty, price, slPrice, targetPrice);
                            });
                }));
    }

    private Mono<Void> liveFuturesEnter(Bot bot, Signal signal, String posSide, BigDecimal price,
                                        BigDecimal slPrice, BigDecimal targetPrice, int slots) {
        String pair = signal.pair();
        String side = "SHORT".equals(posSide) ? "sell" : "buy";
        int leverage = bot.leverage() == null ? props.pipeline().futuresLeverage()
                : bot.leverage().intValue();
        String margin = "INR";
        return withKey(bot).flatMap(key -> futuresClient.availableInrBalance(key.apiKey(), key.apiSecret())
                .flatMap(available -> {
                    BigDecimal maxWallet = available.multiply(BigDecimal.valueOf(props.pipeline().maxWalletPct()));
                    BigDecimal stake = bot.stakeAmount().min(maxWallet)
                            .divide(BigDecimal.valueOf(slots), MathContext.DECIMAL64);
                    if (stake.signum() <= 0) {
                        return skip(bot, signal, "no available INR futures balance");
                    }
                    return futuresClient.instrumentDetails(pair, margin)
                            .flatMap(inst -> {
                                BigDecimal qty = stake.multiply(BigDecimal.valueOf(leverage))
                                        .divide(price, MathContext.DECIMAL64)
                                        .setScale(8, RoundingMode.DOWN);
                                BigDecimal notional = qty.multiply(price);
                                if (qty.signum() <= 0
                                        || qty.compareTo(inst.minQuantity()) < 0
                                        || notional.compareTo(inst.minNotional()) < 0) {
                                    String reason = "below min notional/qty (qty=" + qty.toPlainString()
                                            + " notional=" + notional.toPlainString()
                                            + " minQty=" + inst.minQuantity()
                                            + " minNotional=" + inst.minNotional() + ")";
                                    log.warn("Bot {} skipped LIVE futures entry: {}", bot.id(), reason);
                                    TradeOrder failed = new TradeOrder(UUID.randomUUID(),
                                            bot.tenantId(), bot.userId(), bot.id(), signal.id(),
                                            null, "ca-f-" + UUID.randomUUID(), pair,
                                            side.toUpperCase(), "MARKET_ORDER", "FUTURES",
                                            "LIVE", "FAILED", price, qty.max(BigDecimal.ZERO),
                                            BigDecimal.ZERO, null, BigDecimal.ZERO, reason,
                                            Instant.now(), Instant.now());
                                    return template.insert(failed)
                                            .then(audit.record(bot.tenantId(), bot.userId(),
                                                    "LIVE_FUTURES_ORDER_FAILED", "ORDER", failed.id(),
                                                    Map.of("pair", pair, "side", side,
                                                            "qty", qty.toPlainString(),
                                                            "margin", margin,
                                                            "error", reason)))
                                            .then(skip(bot, signal, reason));
                                }
                                final BigDecimal fillQty = qty;
                                return futuresClient.placeOrder(key.apiKey(), key.apiSecret(), pair, side,
                                                fillQty, leverage, margin, targetPrice, slPrice)
                                        .flatMap(resp -> {
                                            TradeOrder order = new TradeOrder(UUID.randomUUID(), bot.tenantId(),
                                                    bot.userId(), bot.id(), signal.id(),
                                                    resp.path("id").asText(resp.path("orders").path(0).path("id").asText(null)),
                                                    "ca-f-" + UUID.randomUUID(), pair, side.toUpperCase(),
                                                    "MARKET_ORDER", "FUTURES", "LIVE", "OPEN",
                                                    price, fillQty, fillQty, price, BigDecimal.ZERO, null,
                                                    Instant.now(), Instant.now());
                                            Position pos = new Position(UUID.randomUUID(), bot.tenantId(),
                                                    bot.userId(), bot.id(), pair, posSide, fillQty, price, null,
                                                    bot.leverage(), "OPEN", null, slPrice, targetPrice, null,
                                                    margin, Instant.now(), null);
                                            return template.insert(order).then(template.insert(pos))
                                                    .then(audit.record(bot.tenantId(), bot.userId(),
                                                            "LIVE_FUTURES_ORDER", "ORDER", order.id(),
                                                            Map.of("pair", pair, "side", side,
                                                                    "qty", fillQty.toPlainString(),
                                                                    "margin", margin)));
                                        })
                                        .onErrorResume(e -> {
                                            log.error("CoinDCX futures placeOrder failed for bot {}: {}",
                                                    bot.id(), e.getMessage());
                                            TradeOrder failed = new TradeOrder(UUID.randomUUID(),
                                                    bot.tenantId(), bot.userId(), bot.id(), signal.id(),
                                                    null, "ca-f-" + UUID.randomUUID(), pair,
                                                    side.toUpperCase(), "MARKET_ORDER", "FUTURES",
                                                    "LIVE", "FAILED", price, fillQty, BigDecimal.ZERO,
                                                    null, BigDecimal.ZERO,
                                                    String.valueOf(e.getMessage()),
                                                    Instant.now(), Instant.now());
                                            return template.insert(failed)
                                                    .then(audit.record(bot.tenantId(), bot.userId(),
                                                            "LIVE_FUTURES_ORDER_FAILED", "ORDER", failed.id(),
                                                            Map.of("pair", pair, "side", side,
                                                                    "qty", fillQty.toPlainString(),
                                                                    "margin", margin,
                                                                    "error", String.valueOf(e.getMessage()))))
                                                    .then();
                                        });
                            });
                }));
    }

    private Mono<Void> placeLiveEntry(Bot bot, Signal signal, DecryptedKey key, String market,
                                      String posSide, BigDecimal qty, BigDecimal price,
                                      BigDecimal slPrice, BigDecimal targetPrice) {
        String clientOrderId = "ca-" + UUID.randomUUID();
        return trade.placeMarketOrder(key.apiKey(), key.apiSecret(), market, "buy", qty, clientOrderId)
                .flatMap(resp -> {
                    var o = resp.path("orders").path(0);
                    TradeOrder order = orderFromExchange(bot, signal.id(), o, clientOrderId,
                            signal.pair(), "BUY", "MARKET_ORDER", price, qty);
                    Position pos = new Position(UUID.randomUUID(), bot.tenantId(), bot.userId(),
                            bot.id(), signal.pair(), posSide, qty, price, null, bot.leverage(),
                            "OPEN", null, slPrice, targetPrice, null, bot.marginCurrency(),
                            Instant.now(), null);
                    return template.insert(order).then(template.insert(pos))
                            .then(audit.record(bot.tenantId(), bot.userId(), "LIVE_ORDER_PLACED",
                                    "ORDER", order.id(), Map.of("market", market, "side", "buy",
                                            "qty", qty.toPlainString(),
                                            "sl", slPrice.toPlainString(),
                                            "target", targetPrice.toPlainString())))
                            .then(placeSlLeg(bot, key, market, pos, qty, slPrice));
                });
    }

    /** Rest the stop-loss leg on the exchange and remember its order id. */
    private Mono<Void> placeSlLeg(Bot bot, DecryptedKey key, String market, Position pos,
                                  BigDecimal qty, BigDecimal slPrice) {
        String clientOrderId = "ca-sl-" + UUID.randomUUID();
        BigDecimal limitPrice = slPrice.multiply(SL_LIMIT_SLIP).setScale(10, RoundingMode.DOWN);
        return trade.placeStopLimitOrder(key.apiKey(), key.apiSecret(), market, "sell",
                        qty, slPrice, limitPrice, clientOrderId)
                .flatMap(resp -> {
                    var o = resp.path("orders").path(0);
                    String exchangeId = o.path("id").asText(null);
                    TradeOrder slOrder = orderFromExchange(bot, null, o, clientOrderId,
                            pos.pair(), "SELL", "STOP_LIMIT", slPrice, qty);
                    Position updated = new Position(pos.id(), pos.tenantId(), pos.userId(),
                            pos.botId(), pos.pair(), pos.side(), pos.quantity(), pos.entryPrice(),
                            pos.exitPrice(), pos.leverage(), pos.status(), pos.realizedPnl(),
                            pos.slPrice(), pos.targetPrice(), exchangeId, pos.marginCurrency(),
                            pos.openedAt(), pos.closedAt());
                    return template.insert(slOrder).then(positions.save(updated)).then();
                })
                .onErrorResume(e -> {
                    // Entry stands even if the SL leg fails; the guard still watches sl_price.
                    log.error("SL leg for position {} failed: {}", pos.id(), e.getMessage());
                    return audit.record(bot.tenantId(), bot.userId(), "SL_LEG_FAILED", "POSITION",
                            pos.id(), Map.of("error", String.valueOf(e.getMessage()))).then();
                });
    }

    /** Live exit at market: cancel the resting SL leg first, then close. */
    Mono<Void> liveExit(Bot bot, Position pos, BigDecimal price, UUID signalId, String reason) {
        if ("FUTURES".equals(bot.marketType())) {
            return liveFuturesExit(bot, pos, price, signalId, reason);
        }
        String market = pairToMarket(pos.pair());
        String clientOrderId = "ca-" + UUID.randomUUID();
        return withKey(bot).flatMap(key -> cancelSlLeg(key, pos)
                .then(trade.placeMarketOrder(key.apiKey(), key.apiSecret(), market, "sell",
                        pos.quantity(), clientOrderId))
                .flatMap(resp -> {
                    var o = resp.path("orders").path(0);
                    TradeOrder order = orderFromExchange(bot, signalId, o, clientOrderId,
                            pos.pair(), "SELL", "MARKET_ORDER", price, pos.quantity());
                    return template.insert(order).then(closePosition(pos, price))
                            .then(audit.record(bot.tenantId(), bot.userId(), "LIVE_EXIT",
                                    "POSITION", pos.id(), Map.of("market", market,
                                            "price", price.toPlainString(), "reason", reason)));
                })
                .then());
    }

    private Mono<Void> liveFuturesExit(Bot bot, Position pos, BigDecimal price,
                                       UUID signalId, String reason) {
        String closeSide = "SHORT".equals(pos.side()) ? "buy" : "sell";
        int leverage = bot.leverage() == null ? props.pipeline().futuresLeverage()
                : bot.leverage().intValue();
        String margin = bot.marginCurrency() == null ? "INR" : bot.marginCurrency();
        return withKey(bot).flatMap(key -> futuresClient.placeOrder(
                        key.apiKey(), key.apiSecret(), pos.pair(), closeSide,
                        pos.quantity(), leverage, margin, null, null)
                .flatMap(resp -> {
                    TradeOrder order = new TradeOrder(UUID.randomUUID(), bot.tenantId(),
                            bot.userId(), bot.id(), signalId,
                            resp.path("id").asText(resp.path("orders").path(0).path("id").asText(null)),
                            "ca-fx-" + UUID.randomUUID(), pos.pair(), closeSide.toUpperCase(),
                            "MARKET_ORDER", "FUTURES", "LIVE", "OPEN",
                            price, pos.quantity(), pos.quantity(), price, BigDecimal.ZERO, null,
                            Instant.now(), Instant.now());
                    return template.insert(order).then(closePosition(pos, price))
                            .then(audit.record(bot.tenantId(), bot.userId(), "LIVE_FUTURES_EXIT",
                                    "POSITION", pos.id(), Map.of("pair", pos.pair(),
                                            "price", price.toPlainString(), "reason", reason)));
                }));
    }

    private Mono<Void> cancelSlLeg(DecryptedKey key, Position pos) {
        if (pos.slOrderId() == null) return Mono.empty();
        return trade.cancelOrder(key.apiKey(), key.apiSecret(), pos.slOrderId())
                .onErrorResume(e -> {
                    log.warn("Cancelling SL order {} failed: {}", pos.slOrderId(), e.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    // -------------------------------------------------------------- plumbing

    record DecryptedKey(String apiKey, String apiSecret) {}

    Mono<DecryptedKey> withKey(Bot bot) {
        if (bot.exchangeKeyId() == null)
            return Mono.error(new IllegalStateException("Live bot has no exchange key configured"));
        return keys.findById(bot.exchangeKeyId())
                .switchIfEmpty(Mono.error(new IllegalStateException("Exchange key missing")))
                .map(k -> new DecryptedKey(crypto.decrypt(k.apiKeyEnc()), crypto.decrypt(k.apiSecretEnc())));
    }

    private BigDecimal availableBalance(JsonNode balances, String currency) {
        if (balances != null && balances.isArray()) {
            for (JsonNode b : balances) {
                if (currency.equalsIgnoreCase(b.path("currency").asText())) {
                    return new BigDecimal(b.path("balance").asText("0"));
                }
            }
        }
        return BigDecimal.ZERO;
    }

    private Mono<Void> skip(Bot bot, Signal signal, String reason) {
        log.info("Bot {} skipped entry on {}: {}", bot.id(), signal.pair(), reason);
        return audit.record(bot.tenantId(), bot.userId(), "LIVE_ENTRY_SKIPPED", "BOT", bot.id(),
                Map.of("pair", signal.pair(), "reason", reason)).then();
    }

    private TradeOrder orderFromExchange(Bot bot, UUID signalId, JsonNode o, String clientOrderId,
                                         String pair, String side, String orderType,
                                         BigDecimal price, BigDecimal qty) {
        return new TradeOrder(UUID.randomUUID(), bot.tenantId(), bot.userId(), bot.id(), signalId,
                o.path("id").asText(null), clientOrderId, pair, side, orderType,
                bot.marketType(), "LIVE", o.path("status").asText("open").toUpperCase(),
                price, qty,
                new BigDecimal(o.path("total_quantity").asText(qty.toPlainString()))
                        .subtract(new BigDecimal(o.path("remaining_quantity").asText("0"))),
                o.path("avg_price").isMissingNode() ? null
                        : new BigDecimal(o.path("avg_price").asText("0")),
                new BigDecimal(o.path("fee_amount").asText("0")),
                null, Instant.now(), Instant.now());
    }

    /** CoinDCX candle pair (B-BTC_USDT) -> order market symbol (BTCUSDT). */
    static String pairToMarket(String pair) {
        int dash = pair.indexOf('-');
        String raw = dash >= 0 ? pair.substring(dash + 1) : pair;
        return raw.replace("_", "");
    }
}
