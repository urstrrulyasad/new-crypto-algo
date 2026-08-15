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
import com.cryptoalgo.backend.repo.TradeOrderRepository;
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

    /** Round-trip taker fee (~0.075% per side) — mirrors backtest FEE_RATE. */
    private static final BigDecimal TAKER_FEE_RATE = new BigDecimal("0.00075");


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
    private final LiveFuturesSizingService sizing;
    private final TenantLiveRiskGate riskGate;
    private final LivePortfolioRiskService portfolioRisk;
    private final TradeOrderRepository orders;

    public ExecutionService(BotRepository bots, PositionRepository positions,
                            ExchangeKeyRepository keys, StrategyRepository strategies,
                            CoinDcxTradeClient trade, CoinDcxFuturesClient futuresClient,
                            TickerService ticker, MarketRulesService rules, SecretCrypto crypto,
                            R2dbcEntityTemplate template, AuditService audit,
                            ObjectMapper mapper, AppProperties props,
                            LiveFuturesSizingService sizing, TenantLiveRiskGate riskGate,
                            LivePortfolioRiskService portfolioRisk, TradeOrderRepository orders) {
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
        this.sizing = sizing;
        this.riskGate = riskGate;
        this.portfolioRisk = portfolioRisk;
        this.orders = orders;
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
        // Catchup is paper-only historical replay — never place LIVE exchange orders from it.
        if ("LIVE".equals(bot.mode()) && isPaperCatchup(signal)) {
            log.info("Bot {} skipped LIVE entry on catchup signal {}", bot.id(), signal.id());
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
                                    return liveEnter(bot, signal, side, price, slPrice, targetPrice, slots, risk);
                                })))
                .thenReturn(bot);
    }

    // ------------------------------------------------------------------- exit

    private Mono<Bot> exit(Bot bot, Signal signal) {
        if ("LIVE".equals(bot.mode()) && isPaperCatchup(signal)) {
            log.info("Bot {} skipped LIVE exit on catchup signal {}", bot.id(), signal.id());
            return Mono.empty();
        }
        return positions.findByBotIdAndPairAndStatus(bot.id(), signal.pair(), "OPEN")
                .flatMap(pos -> resolveFillPrice(bot, signal)
                        .flatMap(price -> "PAPER".equals(bot.mode())
                                ? closePaperPosition(bot, pos, price, signal.id(), "SIGNAL_EXIT")
                                : liveExit(bot, pos, price, signal.id(), "SIGNAL_EXIT")))
                .thenReturn(bot);
    }

    // ---------------------------------------------------------------- pricing

    /**
     * FUTURES live: CoinDCX last/mark only — never invent a price.
     * FUTURES paper catchup: use the CoinDCX candle close from the signal
     * (real historical exchange data) so paper can accumulate closed trades.
     * Missing price → skip fill (fail closed).
     * SPOT: prefer live ticker, fall back to signal close.
     */
    private Mono<BigDecimal> resolveFillPrice(Bot bot, Signal signal) {
        if ("FUTURES".equals(bot.marketType())) {
            if ("PAPER".equals(bot.mode()) && isPaperCatchup(signal)
                    && signal.price() != null && signal.price().signum() > 0) {
                return Mono.just(signal.price());
            }
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
        // INR-margined futures: PnL = USDT move × qty × side × USDTINR (no leverage factor).
        boolean inrMargin = pos.marginCurrency() == null
                || "INR".equalsIgnoreCase(pos.marginCurrency());
        // Round-trip taker fee on the actual notional (entry + exit legs), in USDT terms.
        // Paper PnL used to be gross, so the LIVE-promotion gate rewarded fee-free
        // profits that evaporate once real 0.075%×2 costs apply. Net it out here so
        // paper realized_pnl matches the fee-aware backtest.
        BigDecimal feeUsdt = pos.entryPrice().add(exitPrice)
                .multiply(pos.quantity())
                .multiply(TAKER_FEE_RATE);
        Mono<BigDecimal> pnlMono = inrMargin
                ? Mono.zip(
                        futuresClient.pnlInr(pos.entryPrice(), exitPrice, pos.quantity(), pos.side()),
                        futuresClient.usdtInrRate())
                    .map(t -> t.getT1().subtract(feeUsdt.multiply(t.getT2())))
                : Mono.just(CoinDcxFuturesClient.pnlUsdt(
                        pos.entryPrice(), exitPrice, pos.quantity(), pos.side()).subtract(feeUsdt));
        return pnlMono.flatMap(pnl -> {
            Position closed = new Position(pos.id(), pos.tenantId(), pos.userId(), pos.botId(), pos.pair(),
                    pos.side(), pos.quantity(), pos.entryPrice(), exitPrice, pos.leverage(), "CLOSED",
                    pnl, pos.slPrice(), pos.targetPrice(), pos.slOrderId(), pos.marginCurrency(),
                    pos.openedAt(), Instant.now());

            return positions.save(closed).then();
        });
    }

    // ------------------------------------------------------------------ live

    /**
     * Live entry: size the order against the available balance, place a
     * market buy, then rest a stop_limit SL order on the exchange.
     */
    private Mono<Void> liveEnter(Bot bot, Signal signal, String posSide, BigDecimal price,
                                 BigDecimal slPrice, BigDecimal targetPrice, int slots, Risk risk) {
        if ("FUTURES".equals(bot.marketType())) {
            return liveFuturesEnter(bot, signal, posSide, price, slPrice, targetPrice, slots, risk);
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

    private Mono<Void> liveFuturesEnter(Bot bot, Signal signal, String posSide, BigDecimal priceHint,
                                        BigDecimal slPrice, BigDecimal targetPrice, int slots, Risk risk) {
        String pair = signal.pair();
        String side = "SHORT".equals(posSide) ? "sell" : "buy";
        int configuredLev = bot.leverage() == null ? props.pipeline().futuresLeverage()
                : bot.leverage().intValue();
        String margin = "INR";
        // Must stay ≤ orders.client_order_id VARCHAR(80). Compact deterministic id.
        String clientOrderId = compactClientOrderId(signal.id(), bot.id());

        return riskGate.isProtectionDegraded(bot.tenantId()).flatMap(degraded -> {
            if (degraded) {
                return failLiveFutures(bot, signal, pair, side, priceHint, BigDecimal.ZERO,
                        clientOrderId, "tenant LIVE protection degraded — new entries blocked");
            }
            return orders.findByTenantIdAndClientOrderId(bot.tenantId(), clientOrderId)
                    .map(java.util.Optional::of)
                    .defaultIfEmpty(java.util.Optional.empty())
                    .flatMap(opt -> {
                        if (opt.isPresent()) {
                            TradeOrder existing = opt.get();
                            if ("FAILED".equals(existing.status()) || "REJECTED".equals(existing.status())) {
                                // Retry after sizing/instrument fixes.
                                return orders.deleteById(existing.id()).then(Mono.defer(() ->
                                        submitSizedLiveEntry(bot, signal, pair, side, margin, configuredLev,
                                                priceHint, posSide, slPrice, targetPrice, clientOrderId, slots, risk)));
                            }
                            log.info("Idempotent skip — order {} already {}", existing.id(), existing.status());
                            return Mono.empty();
                        }
                        return submitSizedLiveEntry(bot, signal, pair, side, margin, configuredLev,
                                priceHint, posSide, slPrice, targetPrice, clientOrderId, slots, risk);
                    });
        });
    }

    private Mono<Void> submitSizedLiveEntry(Bot bot, Signal signal, String pair, String side,
                                            String margin, int configuredLev, BigDecimal priceHint,
                                            String posSide, BigDecimal slPrice, BigDecimal targetPrice,
                                            String clientOrderId, int slots, Risk risk) {
        return withKey(bot).flatMap(key ->
                futuresClient.availableInrBalance(key.apiKey(), key.apiSecret())
                        .switchIfEmpty(Mono.error(new IllegalStateException("INR balance unavailable")))
                        .flatMap(available0 -> {
                            Mono<BigDecimal> funded = available0.signum() > 0
                                    ? Mono.just(available0)
                                    : futuresClient.transferSpotToFutures(key.apiKey(), key.apiSecret(),
                                                    "INR", bot.stakeAmount() == null
                                                            ? BigDecimal.ONE : bot.stakeAmount())
                                            .onErrorResume(e -> Mono.empty())
                                            .then(futuresClient.availableInrBalance(
                                                    key.apiKey(), key.apiSecret()))
                                            .defaultIfEmpty(available0);
                            return funded.flatMap(available -> Mono.zip(
                                            futuresClient.usdtInrRate(),
                                            futuresClient.lastPrice(pair)
                                                    .switchIfEmpty(priceHint != null && priceHint.signum() > 0
                                                            ? Mono.just(priceHint) : Mono.empty())
                                                    .switchIfEmpty(Mono.error(new IllegalStateException(
                                                            "mark price unavailable"))),
                                            futuresClient.instrumentDetails(pair, margin),
                                            riskGate.reservedMargin(bot.tenantId()),
                                            portfolioRisk.openStakeForTenant(bot.tenantId())
                                    ).flatMap(tuple -> {
                                        BigDecimal usdtInr = tuple.getT1();
                                        BigDecimal mark = tuple.getT2();
                                        var inst = tuple.getT3();
                                        BigDecimal reserved = tuple.getT4();
                                        BigDecimal openStake = tuple.getT5();

                                        if (!inst.hasRequiredSizingFields()) {
                                            return failLiveFutures(bot, signal, pair, side, mark,
                                                    BigDecimal.ZERO, clientOrderId,
                                                    "live instrument missing required sizing fields"
                                                            + " minQty=" + inst.minQuantity()
                                                            + " minNotional=" + inst.minNotional()
                                                            + " maxLev=" + inst.maxLeverage());
                                        }

                                        // `available` is CoinDCX free INR wallet balance. Do NOT
                                        // subtract openStake again — that double-counts locked margin.
                                        BigDecimal walletCap = available.multiply(
                                                BigDecimal.valueOf(props.pipeline().maxWalletPct()));
                                        BigDecimal assetCap = available.multiply(
                                                BigDecimal.valueOf(props.pipeline().maxAssetExposurePct()));
                                        BigDecimal stratCap = available.multiply(
                                                BigDecimal.valueOf(props.pipeline().maxStrategyExposurePct()));
                                        BigDecimal maxUsable = walletCap
                                                .subtract(reserved)
                                                .min(assetCap)
                                                .min(stratCap)
                                                .max(BigDecimal.ZERO);
                                        BigDecimal preferred = bot.stakeAmount() == null
                                                ? maxUsable
                                                : bot.stakeAmount().min(maxUsable);

                                        var sized = sizing.size(new LiveFuturesSizingService.SizeRequest(
                                                mark, usdtInr, configuredLev, preferred, maxUsable,
                                                risk.stoploss(), inst));
                                        if (!sized.ok()) {
                                            return failLiveFutures(bot, signal, pair, side, mark,
                                                    sized.qty(), clientOrderId, sized.failReason());
                                        }

                                        return portfolioRisk.evaluateEntry(bot, pair, sized.marginInr(),
                                                        available, reserved)
                                                .flatMap(decision -> {
                                                    if (!decision.ok()) {
                                                        return failLiveFutures(bot, signal, pair, side, mark,
                                                                sized.qty(), clientOrderId, decision.reason());
                                                    }
                                                    BigDecimal maxReservable = walletCap;
                                                    return riskGate.tryReserve(bot.tenantId(),
                                                                    sized.marginInr(), maxReservable)
                                                            .flatMap(reservedOk -> {
                                                                if (!reservedOk) {
                                                                    return failLiveFutures(bot, signal, pair, side, mark,
                                                                            sized.qty(), clientOrderId,
                                                                            "concurrent wallet reservation failed");
                                                                }
                                                                TradeOrder pending = new TradeOrder(
                                                                        UUID.randomUUID(), bot.tenantId(), bot.userId(),
                                                                        bot.id(), signal.id(), null, clientOrderId,
                                                                        pair, side.toUpperCase(), "MARKET_ORDER",
                                                                        "FUTURES", "LIVE", "SUBMITTING",
                                                                        mark, sized.qty(), BigDecimal.ZERO, null,
                                                                        BigDecimal.ZERO, null,
                                                                        Instant.now(), Instant.now());
                                                                return template.insert(pending)
                                                                        .then(submitFuturesEntry(bot, signal, key, pair,
                                                                                side, margin, configuredLev, mark,
                                                                                posSide, slPrice, targetPrice,
                                                                                sized, clientOrderId, pending.id()));
                                                            });
                                                });
                                    }));
                        }));
    }

    private Mono<Void> submitFuturesEntry(Bot bot, Signal signal, DecryptedKey key, String pair,
                                          String side, String margin, int leverage, BigDecimal mark,
                                          String posSide, BigDecimal slPrice, BigDecimal targetPrice,
                                          LiveFuturesSizingService.SizeResult sized, String clientOrderId,
                                          UUID orderId) {
        return futuresClient.placeOrder(key.apiKey(), key.apiSecret(), pair, side,
                        sized.qty(), leverage, margin, null, null, clientOrderId)
                .flatMap(resp -> {
                    log.info("LIVE placeOrder OK clientId={} pair={} resp={}",
                            clientOrderId, pair, resp);
                    String exchangeId = extractExchangeOrderId(resp);
                    Mono<String> eidMono = (exchangeId != null && !exchangeId.isBlank())
                            ? Mono.just(exchangeId)
                            : futuresClient.findOrderByClientOrderId(
                                            key.apiKey(), key.apiSecret(), pair, clientOrderId)
                                    .map(ExecutionService::extractExchangeOrderId)
                                    .filter(id -> id != null && !id.isBlank())
                                    .switchIfEmpty(Mono.error(new IllegalStateException(
                                            "LIVE order accepted but exchange id missing; "
                                                    + "clientOrderId=" + clientOrderId)));
                    return eidMono.flatMap(eid -> completeLiveFuturesFill(
                            bot, signal, pair, side, margin, mark, posSide, slPrice, targetPrice,
                            sized, clientOrderId, orderId, eid));
                })
                .onErrorResume(e -> {
                    boolean ambiguous = isAmbiguousSubmitError(e);
                    if (ambiguous) {
                        log.warn("Ambiguous LIVE submit for {} — marking PENDING_RECONCILE: {}",
                                clientOrderId, e.getMessage());
                        // Keep reservation until reconcile settles.
                        return template.getDatabaseClient().sql("""
                                        UPDATE orders SET status = 'PENDING_RECONCILE', error = :err, updated_at = :now
                                        WHERE id = :id
                                        """)
                                .bind("err", String.valueOf(e.getMessage()))
                                .bind("now", Instant.now())
                                .bind("id", orderId)
                                .fetch().rowsUpdated()
                                .then(audit.record(bot.tenantId(), bot.userId(),
                                        "LIVE_ORDER_PENDING_RECONCILE", "ORDER", orderId,
                                        Map.of("clientOrderId", clientOrderId,
                                                "error", String.valueOf(e.getMessage()))))
                                .then();
                    }
                    log.error("CoinDCX futures placeOrder failed for bot {}: {}", bot.id(), e.getMessage());
                    return riskGate.release(bot.tenantId(), sized.marginInr())
                            .then(template.getDatabaseClient().sql("""
                                            UPDATE orders SET status = 'FAILED', error = :err, updated_at = :now
                                            WHERE id = :id
                                            """)
                                    .bind("err", String.valueOf(e.getMessage()))
                                    .bind("now", Instant.now())
                                    .bind("id", orderId)
                                    .fetch().rowsUpdated())
                            .then(audit.record(bot.tenantId(), bot.userId(),
                                    "LIVE_FUTURES_ORDER_FAILED", "ORDER", orderId,
                                    Map.of("pair", pair, "error", String.valueOf(e.getMessage()))))
                            .then();
                });
    }

    private Mono<Void> completeLiveFuturesFill(Bot bot, Signal signal, String pair, String side,
                                               String margin, BigDecimal mark, String posSide,
                                               BigDecimal slPrice, BigDecimal targetPrice,
                                               LiveFuturesSizingService.SizeResult sized,
                                               String clientOrderId, UUID orderId, String exchangeId) {
        Position pos = new Position(UUID.randomUUID(), bot.tenantId(), bot.userId(),
                bot.id(), pair, posSide, sized.qty(), mark, null, bot.leverage(),
                "OPEN", null, slPrice, targetPrice, null, margin,
                Instant.now(), null);
        var update = template.getDatabaseClient().sql("""
                        UPDATE orders SET exchange_order_id = :eid, status = 'OPEN',
                          filled_qty = :fq, avg_price = :ap, updated_at = :now
                        WHERE id = :id
                        """)
                .bind("eid", exchangeId)
                .bind("fq", sized.qty())
                .bind("ap", mark)
                .bind("now", Instant.now())
                .bind("id", orderId)
                .fetch().rowsUpdated();
        return update
                .then(template.insert(pos))
                .then(riskGate.release(bot.tenantId(), sized.marginInr()))
                .then(armProtection(bot, pos))
                .then(audit.record(bot.tenantId(), bot.userId(),
                        "LIVE_FUTURES_ORDER", "ORDER", orderId,
                        Map.of("pair", pair, "side", side,
                                "qty", sized.qty().toPlainString(),
                                "marginInr", sized.marginInr().toPlainString(),
                                "bumped", String.valueOf(sized.bumped()),
                                "liqCheck", String.valueOf(sized.liquidationCheck()),
                                "exchangeOrderId", exchangeId)));
    }

    static String extractExchangeOrderId(com.fasterxml.jackson.databind.JsonNode resp) {
        if (resp == null || resp.isNull() || resp.isMissingNode()) return null;
        String[] direct = {
                textId(resp, "id"),
                textId(resp, "order_id"),
                textId(resp.path("order"), "id"),
                textId(resp.path("order"), "order_id"),
                textId(resp.path("data"), "id"),
                textId(resp.path("data").path("order"), "id"),
                textId(resp.path("orders").path(0), "id"),
                textId(resp.path("orders").path(0), "order_id"),
        };
        for (String id : direct) {
            if (id != null && !id.isBlank() && !"null".equalsIgnoreCase(id)) return id;
        }
        if (resp.isArray() && !resp.isEmpty()) {
            String id = textId(resp.get(0), "id");
            if (id == null) id = textId(resp.get(0), "order_id");
            if (id != null && !id.isBlank()) return id;
        }
        return null;
    }

    private static String textId(com.fasterxml.jackson.databind.JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.has(field)) return null;
        var v = node.get(field);
        if (v == null || v.isNull() || v.isMissingNode()) return null;
        String s = v.asText(null);
        return s == null || s.isBlank() ? null : s;
    }

    private Mono<Void> armProtection(Bot bot, Position pos) {
        if (pos.slPrice() == null || pos.targetPrice() == null) {
            return emergencyProtectionFailure(bot, pos, "missing SL/target on position");
        }
        return positions.findById(pos.id())
                .switchIfEmpty(Mono.defer(() ->
                        emergencyProtectionFailure(bot, pos, "position missing after fill")
                                .then(Mono.empty())))
                .flatMap(saved -> audit.record(bot.tenantId(), bot.userId(),
                                "LIVE_PROTECTION_ARMED", "POSITION", saved.id(),
                                Map.of("pair", saved.pair(),
                                        "sl", String.valueOf(saved.slPrice()),
                                        "target", String.valueOf(saved.targetPrice())))
                        .then());
    }

    private Mono<Void> emergencyProtectionFailure(Bot bot, Position pos, String reason) {
        log.error("LIVE protection failure bot={} pos={}: {}", bot.id(), pos.id(), reason);
        Bot stopped = new Bot(bot.id(), bot.tenantId(), bot.userId(), bot.strategyId(),
                bot.exchangeKeyId(), bot.name(), bot.mode(), bot.marketType(), bot.pairs(),
                bot.stakeCurrency(), bot.stakeAmount(), bot.maxOpenTrades(), bot.leverage(),
                "STOPPED", true, bot.marginCurrency(), bot.createdAt(), Instant.now());
        return bots.save(stopped)
                .then(riskGate.setProtectionDegraded(bot.tenantId(), true))
                .then(audit.record(bot.tenantId(), bot.userId(), "LIVE_PROTECTION_FAILED",
                        "POSITION", pos.id(), Map.of("reason", reason, "pair", pos.pair())))
                .then(Mono.defer(() -> {
                    // Attempt safe market close if we have a mark; fail closed if not.
                    return futuresClient.lastPrice(pos.pair())
                            .flatMap(px -> liveFuturesExit(bot, pos, px, null, "PROTECTION_FAILED"))
                            .onErrorResume(e -> audit.record(bot.tenantId(), bot.userId(),
                                            "LIVE_EMERGENCY_EXIT_FAILED", "POSITION", pos.id(),
                                            Map.of("error", String.valueOf(e.getMessage())))
                                    .then());
                }));
    }

    private Mono<Void> failLiveFutures(Bot bot, Signal signal, String pair, String side,
                                       BigDecimal price, BigDecimal qty, String clientOrderId,
                                       String reason) {
        log.warn("Bot {} skipped LIVE futures entry: {}", bot.id(), reason);
        // Reuse compact clientOrderId (≤80). Never append UUIDs — that blew VARCHAR(80).
        String failClientId = clientOrderId == null || clientOrderId.isBlank()
                ? compactClientOrderId(signal.id(), bot.id())
                : clientOrderId;
        TradeOrder failed = new TradeOrder(UUID.randomUUID(), bot.tenantId(), bot.userId(), bot.id(),
                signal.id(), null, failClientId,
                pair, side.toUpperCase(), "MARKET_ORDER", "FUTURES", "LIVE", "FAILED",
                price, qty == null ? BigDecimal.ZERO : qty.max(BigDecimal.ZERO),
                BigDecimal.ZERO, null, BigDecimal.ZERO, reason, Instant.now(), Instant.now());
        return template.insert(failed)
                .onErrorResume(e -> {
                    log.warn("Could not persist FAILED LIVE order for {}: {}", failClientId, e.toString());
                    return Mono.empty();
                })
                .then(audit.record(bot.tenantId(), bot.userId(), "LIVE_FUTURES_ORDER_FAILED",
                        "ORDER", failed.id(), Map.of("pair", pair, "side", side, "error", reason)))
                .then(skip(bot, signal, reason));
    }

    /**
     * Deterministic client order id for LIVE futures.
     * CoinDCX rejects ids longer than 36 characters (DB column is 80).
     */
    static String compactClientOrderId(UUID signalId, UUID botId) {
        String sig = signalId.toString().replace("-", "");
        String bot = botId.toString().replace("-", "");
        // 2 + 16 + 16 = 34 ≤ 36
        return "ca" + sig.substring(0, 16) + bot.substring(0, 16);
    }

    private static boolean isAmbiguousSubmitError(Throwable e) {
        Throwable c = e;
        while (c != null) {
            String n = c.getClass().getName();
            String m = String.valueOf(c.getMessage()).toLowerCase();
            if (n.contains("Timeout") || m.contains("timeout") || m.contains("timed out")
                    || m.contains("connection reset") || m.contains("premature")
                    || m.contains("exchange id missing") || m.contains("bindnull")) {
                return true;
            }
            c = c.getCause();
        }
        return false;
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
                                    "ORDER", order.id(), Map.of(
                                            "pair", signal.pair(),
                                            "market", market,
                                            "side", posSide,
                                            "qty", qty.toPlainString(),
                                            "entryPrice", price.toPlainString(),
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
        String clientOrderId = compactClientOrderId(
                signalId != null ? signalId : pos.id(), bot.id());
        return withKey(bot).flatMap(key -> futuresClient.placeOrder(
                        key.apiKey(), key.apiSecret(), pos.pair(), closeSide,
                        pos.quantity(), leverage, margin, null, null, clientOrderId)
                .flatMap(resp -> {
                    log.info("LIVE exit placeOrder OK clientId={} pair={} resp={}",
                            clientOrderId, pos.pair(), resp);
                    String exchangeId = extractExchangeOrderId(resp);
                    if (exchangeId == null || exchangeId.isBlank()) {
                        exchangeId = clientOrderId; // persist somehow; position still closes
                    }
                    TradeOrder order = new TradeOrder(UUID.randomUUID(), bot.tenantId(),
                            bot.userId(), bot.id(), signalId,
                            exchangeId, clientOrderId, pos.pair(), closeSide.toUpperCase(),
                            "MARKET_ORDER", "FUTURES", "LIVE", "FILLED",
                            price, pos.quantity(), pos.quantity(), price, BigDecimal.ZERO, null,
                            Instant.now(), Instant.now());
                    return template.insert(order).then(closePosition(pos, price))
                            .then(audit.record(bot.tenantId(), bot.userId(), "LIVE_FUTURES_EXIT",
                                    "POSITION", pos.id(), Map.of("pair", pos.pair(),
                                            "price", price.toPlainString(), "reason", reason,
                                            "exchangeOrderId", exchangeId)));
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

    /** Paper catchup signals carry real CoinDCX candle closes in signal.price. */
    private boolean isPaperCatchup(Signal signal) {
        if (signal.payload() == null) return false;
        try {
            return mapper.readTree(signal.payload().asString()).path("catchup").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }
}
