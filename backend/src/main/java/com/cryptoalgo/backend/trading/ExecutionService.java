package com.cryptoalgo.backend.trading;

import com.cryptoalgo.backend.common.AuditService;
import com.cryptoalgo.backend.common.SecretCrypto;
import com.cryptoalgo.backend.domain.Bot;
import com.cryptoalgo.backend.domain.Position;
import com.cryptoalgo.backend.domain.Signal;
import com.cryptoalgo.backend.domain.TradeOrder;
import com.cryptoalgo.backend.market.TickerService;
import com.cryptoalgo.backend.repo.BotRepository;
import com.cryptoalgo.backend.repo.ExchangeKeyRepository;
import com.cryptoalgo.backend.repo.PositionRepository;
import com.cryptoalgo.backend.repo.TradeOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
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
 */
@Service
public class ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

    private final BotRepository bots;
    private final PositionRepository positions;
    private final TradeOrderRepository orders;
    private final ExchangeKeyRepository keys;
    private final CoinDcxTradeClient trade;
    private final TickerService ticker;
    private final SecretCrypto crypto;
    private final R2dbcEntityTemplate template;
    private final AuditService audit;

    public ExecutionService(BotRepository bots, PositionRepository positions,
                            TradeOrderRepository orders, ExchangeKeyRepository keys,
                            CoinDcxTradeClient trade, TickerService ticker, SecretCrypto crypto,
                            R2dbcEntityTemplate template, AuditService audit) {
        this.bots = bots;
        this.positions = positions;
        this.orders = orders;
        this.keys = keys;
        this.trade = trade;
        this.ticker = ticker;
        this.crypto = crypto;
        this.template = template;
        this.audit = audit;
    }

    /** Returns the number of bots that acted on the signal. */
    public Mono<Long> process(Signal signal) {
        return bots.findByStrategyIdAndStatus(signal.strategyId(), "RUNNING")
                .filter(bot -> !bot.killSwitch())
                .filter(bot -> bot.tenantId().equals(signal.tenantId()))
                .filter(bot -> botTradesPair(bot, signal.pair()))
                .flatMap(bot -> handle(bot, signal)
                        .onErrorResume(e -> {
                            log.error("Bot {} failed to handle signal {}", bot.id(), signal.id(), e);
                            return Mono.empty();
                        }))
                .count();
    }

    private boolean botTradesPair(Bot bot, String pair) {
        return bot.pairs() != null && bot.pairs().asString().contains("\"" + pair + "\"");
    }

    private Mono<Bot> handle(Bot bot, Signal signal) {
        boolean isEntry = signal.action().startsWith("ENTRY");
        return isEntry ? enter(bot, signal) : exit(bot, signal);
    }

    // ------------------------------------------------------------------ entry

    private Mono<Bot> enter(Bot bot, Signal signal) {
        String side = signal.action().equals("ENTRY_SHORT") ? "SHORT" : "LONG";
        if ("SHORT".equals(side) && "SPOT".equals(bot.marketType())) {
            log.debug("Bot {} ignores short entry on spot market", bot.id());
            return Mono.empty();
        }
        return positions.countByBotIdAndStatus(bot.id(), "OPEN")
                .filter(open -> open < bot.maxOpenTrades())
                .flatMap(x -> positions.findByBotIdAndPairAndStatus(bot.id(), signal.pair(), "OPEN")
                        .hasElement())
                .filter(hasOpen -> !hasOpen)
                .flatMap(x -> {
                    BigDecimal price = fillPrice(signal);
                    BigDecimal qty = bot.stakeAmount()
                            .divide(price, MathContext.DECIMAL64)
                            .setScale(8, RoundingMode.DOWN);
                    if (qty.signum() <= 0) return Mono.empty();
                    return "PAPER".equals(bot.mode())
                            ? paperFill(bot, signal, side, "BUY", qty, price)
                            : liveOrder(bot, signal, side, "buy", qty, price);
                })
                .thenReturn(bot);
    }

    // ------------------------------------------------------------------- exit

    private Mono<Bot> exit(Bot bot, Signal signal) {
        return positions.findByBotIdAndPairAndStatus(bot.id(), signal.pair(), "OPEN")
                .flatMap(pos -> {
                    BigDecimal price = fillPrice(signal);
                    return "PAPER".equals(bot.mode())
                            ? closePaperPosition(bot, signal, pos, price)
                            : liveOrder(bot, signal, pos.side(), "sell", pos.quantity(), price)
                                .then(closePosition(pos, price));
                })
                .thenReturn(bot);
    }

    // ---------------------------------------------------------------- fills

    /** Prefer the live ticker price; fall back to the signal's candle close. */
    private BigDecimal fillPrice(Signal signal) {
        String market = pairToMarket(signal.pair());
        TickerService.Tick tick = ticker.last(market);
        return tick != null ? tick.lastPrice() : signal.price();
    }

    private Mono<Void> paperFill(Bot bot, Signal signal, String posSide,
                                 String orderSide, BigDecimal qty, BigDecimal price) {
        TradeOrder order = new TradeOrder(UUID.randomUUID(), bot.tenantId(), bot.userId(), bot.id(),
                signal.id(), null, "paper-" + UUID.randomUUID(), signal.pair(), orderSide,
                "MARKET_ORDER", bot.marketType(), "PAPER", "FILLED", price, qty, qty, price,
                BigDecimal.ZERO, null, Instant.now(), Instant.now());
        Position pos = new Position(UUID.randomUUID(), bot.tenantId(), bot.userId(), bot.id(),
                signal.pair(), posSide, qty, price, null, bot.leverage(), "OPEN", null,
                Instant.now(), null);
        return template.insert(order).then(template.insert(pos))
                .then(audit.record(bot.tenantId(), bot.userId(), "PAPER_ENTRY", "POSITION", pos.id(),
                        Map.of("pair", signal.pair(), "qty", qty.toPlainString(), "price", price.toPlainString())))
                .then();
    }

    private Mono<Void> closePaperPosition(Bot bot, Signal signal, Position pos, BigDecimal price) {
        TradeOrder order = new TradeOrder(UUID.randomUUID(), bot.tenantId(), bot.userId(), bot.id(),
                signal.id(), null, "paper-" + UUID.randomUUID(), signal.pair(), "SELL",
                "MARKET_ORDER", bot.marketType(), "PAPER", "FILLED", price, pos.quantity(),
                pos.quantity(), price, BigDecimal.ZERO, null, Instant.now(), Instant.now());
        return template.insert(order).then(closePosition(pos, price))
                .then(audit.record(bot.tenantId(), bot.userId(), "PAPER_EXIT", "POSITION", pos.id(),
                        Map.of("pair", signal.pair(), "exitPrice", price.toPlainString())))
                .then();
    }

    private Mono<Void> closePosition(Position pos, BigDecimal exitPrice) {
        BigDecimal direction = "SHORT".equals(pos.side()) ? BigDecimal.valueOf(-1) : BigDecimal.ONE;
        BigDecimal pnl = exitPrice.subtract(pos.entryPrice())
                .multiply(pos.quantity()).multiply(direction).multiply(pos.leverage());
        Position closed = new Position(pos.id(), pos.tenantId(), pos.userId(), pos.botId(), pos.pair(),
                pos.side(), pos.quantity(), pos.entryPrice(), exitPrice, pos.leverage(), "CLOSED",
                pnl, pos.openedAt(), Instant.now());
        return positions.save(closed).then();
    }

    // ------------------------------------------------------------------ live

    private Mono<Void> liveOrder(Bot bot, Signal signal, String posSide, String side,
                                 BigDecimal qty, BigDecimal price) {
        if (bot.exchangeKeyId() == null) {
            return Mono.error(new IllegalStateException("Live bot has no exchange key configured"));
        }
        String clientOrderId = "ca-" + UUID.randomUUID();
        String market = pairToMarket(signal.pair());
        return keys.findById(bot.exchangeKeyId())
                .switchIfEmpty(Mono.error(new IllegalStateException("Exchange key missing")))
                .flatMap(key -> trade.placeMarketOrder(
                                crypto.decrypt(key.apiKeyEnc()), crypto.decrypt(key.apiSecretEnc()),
                                market, side, qty, clientOrderId)
                        .flatMap(resp -> {
                            var o = resp.path("orders").path(0);
                            TradeOrder order = new TradeOrder(UUID.randomUUID(), bot.tenantId(),
                                    bot.userId(), bot.id(), signal.id(),
                                    o.path("id").asText(null), clientOrderId, signal.pair(),
                                    side.toUpperCase(), "MARKET_ORDER", bot.marketType(), "LIVE",
                                    o.path("status").asText("open").toUpperCase(),
                                    price, qty,
                                    new BigDecimal(o.path("total_quantity").asText(qty.toPlainString()))
                                            .subtract(new BigDecimal(o.path("remaining_quantity").asText("0"))),
                                    o.path("avg_price").isMissingNode() ? null
                                            : new BigDecimal(o.path("avg_price").asText("0")),
                                    new BigDecimal(o.path("fee_amount").asText("0")),
                                    null, Instant.now(), Instant.now());
                            Mono<Void> position = "buy".equals(side)
                                    ? template.insert(new Position(UUID.randomUUID(), bot.tenantId(),
                                            bot.userId(), bot.id(), signal.pair(), posSide, qty, price,
                                            null, bot.leverage(), "OPEN", null, Instant.now(), null)).then()
                                    : Mono.empty();
                            return template.insert(order).then(position)
                                    .then(audit.record(bot.tenantId(), bot.userId(), "LIVE_ORDER_PLACED",
                                            "ORDER", order.id(), Map.of("market", market, "side", side,
                                                    "qty", qty.toPlainString())));
                        }));
    }

    /** CoinDCX candle pair (B-BTC_USDT) -> order market symbol (BTCUSDT). */
    static String pairToMarket(String pair) {
        int dash = pair.indexOf('-');
        String raw = dash >= 0 ? pair.substring(dash + 1) : pair;
        return raw.replace("_", "");
    }
}
