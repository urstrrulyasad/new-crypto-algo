package com.cryptoalgo.backend.trading;

import com.cryptoalgo.backend.common.ApiException;
import com.cryptoalgo.backend.common.SecretCrypto;
import com.cryptoalgo.backend.domain.Bot;
import com.cryptoalgo.backend.domain.Position;
import com.cryptoalgo.backend.domain.TradeOrder;
import com.cryptoalgo.backend.repo.BotRepository;
import com.cryptoalgo.backend.repo.ExchangeKeyRepository;
import com.cryptoalgo.backend.repo.PositionRepository;
import com.cryptoalgo.backend.repo.TradeOrderRepository;
import com.cryptoalgo.backend.security.AuthPrincipal;
import com.cryptoalgo.backend.security.CurrentUser;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Portfolio money views. Default mode=LIVE (Dashboard account money).
 * Pass mode=PAPER only for explicit paper views — never mix into Dashboard.
 */
@RestController
@RequestMapping("/api/v1/portfolio")
public class PortfolioController {

    private final PositionRepository positions;
    private final TradeOrderRepository orders;
    private final BotRepository bots;
    private final ExchangeKeyRepository keys;
    private final CoinDcxFuturesClient futures;
    private final SecretCrypto crypto;

    public PortfolioController(PositionRepository positions, TradeOrderRepository orders,
                               BotRepository bots, ExchangeKeyRepository keys,
                               CoinDcxFuturesClient futures, SecretCrypto crypto) {
        this.positions = positions;
        this.orders = orders;
        this.bots = bots;
        this.keys = keys;
        this.futures = futures;
        this.crypto = crypto;
    }

    @GetMapping("/positions")
    public Flux<Map<String, Object>> positions(@RequestParam(defaultValue = "LIVE") String mode) {
        String m = normalizeMode(mode);
        return CurrentUser.get().flatMapMany(p -> botIds(p.tenantId(), p.userId(), m)
                .flatMapMany(ids -> {
                    Flux<Position> rows = positions.findByTenantIdAndUserIdOrderByOpenedAtDesc(
                                    p.tenantId(), p.userId())
                            .filter(pos -> ids.contains(pos.botId()));
                    if (!"LIVE".equals(m)) {
                        return rows.concatMap(this::enrichPosition);
                    }
                    return liveExchangeByPair(p).flatMapMany(ex ->
                            rows.concatMap(pos -> enrichLivePosition(pos, ex.get(pos.pair()))));
                }));
    }

    @GetMapping("/orders")
    public Flux<Map<String, Object>> orders(@RequestParam(defaultValue = "LIVE") String mode) {
        String m = normalizeMode(mode);
        return CurrentUser.get().flatMapMany(p -> botIds(p.tenantId(), p.userId(), m)
                .flatMapMany(ids -> Mono.zip(
                                orders.findByTenantIdAndUserIdOrderByCreatedAtDesc(
                                                p.tenantId(), p.userId())
                                        .filter(o -> ids.contains(o.botId()))
                                        .collectList(),
                                positions.findByTenantIdAndUserIdOrderByOpenedAtDesc(
                                                p.tenantId(), p.userId())
                                        .filter(pos -> ids.contains(pos.botId()))
                                        .collectList(),
                                settleRateForDisplay(p, m)
                        ).flatMapMany(t -> Flux.fromIterable(t.getT1())
                                .concatMap(o -> enrichOrder(o, t.getT2(), t.getT3())))));
    }

    /** CoinDCX INR order size uses settlement rate (~102), not spot USDTINR ticker. */
    private Mono<BigDecimal> settleRateForDisplay(AuthPrincipal p, String mode) {
        Mono<BigDecimal> ticker = futures.usdtInrRate().defaultIfEmpty(BigDecimal.valueOf(100));
        if (!"LIVE".equals(mode)) return ticker;
        return liveExchangeByPair(p).flatMap(ex -> ticker.map(rate -> {
            for (JsonNode n : ex.values()) {
                BigDecimal s = settleRate(n, rate);
                if (s != null && s.signum() > 0) return s;
            }
            return rate;
        }));
    }

    @GetMapping("/summary")
    public Mono<Map<String, Object>> summary(@RequestParam(defaultValue = "LIVE") String mode) {
        String m = normalizeMode(mode);
        return CurrentUser.get().flatMap(p ->
                botIds(p.tenantId(), p.userId(), m).flatMap(ids ->
                        positions.findByTenantIdAndUserIdOrderByOpenedAtDesc(p.tenantId(), p.userId())
                                .filter(pos -> ids.contains(pos.botId()))
                                .collectList()
                                .flatMap(list -> "LIVE".equals(m)
                                        ? liveExchangeByPair(p).flatMap(ex -> summarizeLive(list, ex))
                                        : summarize(list, m))));
    }

    /**
     * CoinDCX-aligned futures wallet snapshot.
     * <ul>
     *   <li>Docs: walletBalance = balance + locked_balance</li>
     *   <li>UI Current Value (INR) = walletBalance + activePnl</li>
     *   <li>UI Est. total Futures = INR currentValue + USDT futures valued in INR</li>
     * </ul>
     */
    @GetMapping("/wallet")
    public Mono<Map<String, Object>> wallet() {
        return CurrentUser.get().flatMap(p ->
                keys.findByTenantIdAndUserId(p.tenantId(), p.userId())
                        .filter(k -> "ACTIVE".equals(k.status()))
                        .next()
                        .switchIfEmpty(Mono.error(ApiException.notFound("No active CoinDCX key")))
                        .flatMap(key -> {
                            String apiKey = crypto.decrypt(key.apiKeyEnc());
                            String apiSecret = crypto.decrypt(key.apiSecretEnc());
                            return Mono.zip(
                                            futures.futuresWallets(apiKey, apiSecret),
                                            futures.usdtInrRate().defaultIfEmpty(BigDecimal.valueOf(100)),
                                            liveActivePnlInr(p)
                                    )
                                    .map(t -> {
                                        BigDecimal availInr = BigDecimal.ZERO;
                                        BigDecimal lockedInr = BigDecimal.ZERO;
                                        BigDecimal availUsdt = BigDecimal.ZERO;
                                        BigDecimal lockedUsdt = BigDecimal.ZERO;
                                        for (CoinDcxFuturesClient.CurrencyWallet w : t.getT1()) {
                                            if ("INR".equalsIgnoreCase(w.currency())) {
                                                availInr = w.available();
                                                lockedInr = w.locked();
                                            } else if ("USDT".equalsIgnoreCase(w.currency())) {
                                                availUsdt = w.available();
                                                lockedUsdt = w.locked();
                                            }
                                        }
                                        BigDecimal usdtInr = t.getT2();
                                        BigDecimal activePnl = t.getT3();
                                        BigDecimal walletBalanceInr = availInr.add(lockedInr);
                                        BigDecimal walletBalanceUsdt = availUsdt.add(lockedUsdt);
                                        BigDecimal usdtValueInr = walletBalanceUsdt.multiply(usdtInr);
                                        // CoinDCX Assets row: Current Value = Wallet Balance + Active PNL
                                        BigDecimal currentValueInr = walletBalanceInr.add(activePnl);
                                        // CoinDCX Futures header: Est. total = sum of currency current values
                                        BigDecimal estTotalFutures = currentValueInr.add(usdtValueInr);
                                        BigDecimal futuresWalletBalance =
                                                walletBalanceInr.add(usdtValueInr);

                                        Map<String, Object> out = new HashMap<>();
                                        out.put("currency", "INR");
                                        out.put("available", availInr);
                                        out.put("locked", lockedInr);
                                        out.put("walletBalance", walletBalanceInr);
                                        out.put("walletEquity", walletBalanceInr); // back-compat
                                        out.put("activePnl", activePnl);
                                        out.put("currentValue", currentValueInr);
                                        out.put("usdtAvailable", availUsdt);
                                        out.put("usdtLocked", lockedUsdt);
                                        out.put("usdtWalletBalance", walletBalanceUsdt);
                                        out.put("usdtValueInr", usdtValueInr);
                                        out.put("usdtInrRate", usdtInr);
                                        out.put("futuresWalletBalance", futuresWalletBalance);
                                        out.put("estTotalFutures", estTotalFutures);
                                        out.put("source", "CoinDCX futures wallets");
                                        return out;
                                    });
                        }));
    }

    /** Live Active PNL in INR for open LIVE positions (candle mark × settle). */
    private Mono<BigDecimal> liveActivePnlInr(AuthPrincipal p) {
        return botIds(p.tenantId(), p.userId(), "LIVE").flatMap(ids ->
                positions.findByTenantIdAndUserIdOrderByOpenedAtDesc(p.tenantId(), p.userId())
                        .filter(pos -> ids.contains(pos.botId()) && "OPEN".equals(pos.status()))
                        .collectList()
                        .flatMap(list -> {
                            if (list.isEmpty()) return Mono.just(BigDecimal.ZERO);
                            return liveExchangeByPair(p).flatMap(ex -> summarizeLive(list, ex)
                                    .map(m -> {
                                        Object u = m.get("unrealizedPnl");
                                        if (u instanceof BigDecimal bd) return bd;
                                        if (u instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
                                        return BigDecimal.ZERO;
                                    }));
                        }));
    }

    private Mono<Map<String, JsonNode>> liveExchangeByPair(AuthPrincipal p) {
        return keys.findByTenantIdAndUserId(p.tenantId(), p.userId())
                .filter(k -> "ACTIVE".equals(k.status()))
                .next()
                .flatMap(key -> futures.listPositions(
                                crypto.decrypt(key.apiKeyEnc()),
                                crypto.decrypt(key.apiSecretEnc()),
                                "INR")
                        .map(resp -> {
                            Map<String, JsonNode> byPair = new HashMap<>();
                            if (resp != null && resp.isArray()) {
                                for (JsonNode n : resp) {
                                    if (n.path("active_pos").asDouble(0) == 0) continue;
                                    byPair.put(n.path("pair").asText(), n);
                                }
                            }
                            return byPair;
                        }))
                .defaultIfEmpty(Map.of());
    }

    private Mono<Map<String, Object>> enrichLivePosition(Position pos, JsonNode exchange) {
        if (exchange != null && "OPEN".equals(pos.status())) {
            Map<String, Object> m = positionMap(pos);
            // CoinDCX UI mark moves live; positions API mark_price is often stale (= avg).
            // Prefer fresh futures candle close, fall back to exchange mark.
            return Mono.zip(
                            futures.usdtInrRate().defaultIfEmpty(BigDecimal.ZERO),
                            futures.lastPrice(pos.pair()).defaultIfEmpty(BigDecimal.ZERO)
                    )
                    .map(t -> {
                        BigDecimal settle = settleRate(exchange, t.getT1());
                        BigDecimal liveMark = t.getT2().signum() > 0 ? t.getT2() : decimal(exchange, "mark_price");
                        BigDecimal avg = decimal(exchange, "avg_price");
                        if (avg == null) avg = pos.entryPrice();
                        BigDecimal active = decimal(exchange, "active_pos");
                        if (active == null) {
                            BigDecimal q = pos.quantity() == null ? BigDecimal.ZERO : pos.quantity();
                            active = "SHORT".equalsIgnoreCase(pos.side()) ? q.negate() : q;
                        }
                        BigDecimal u = unrealizedInr(avg, liveMark, active, settle);
                        BigDecimal lockedUsdt = decimal(exchange, "locked_margin");
                        BigDecimal marginInr = null;
                        if (lockedUsdt != null && settle.signum() > 0) {
                            marginInr = lockedUsdt.multiply(settle);
                            m.put("marginInr", marginInr);
                        }
                        if (liveMark != null && liveMark.signum() > 0 && settle.signum() > 0) {
                            m.put("markPrice", liveMark);
                            m.put("sizeInr", active.abs().multiply(liveMark).multiply(settle));
                        }
                        if (avg != null) m.put("entryPrice", avg);
                        m.put("unrealizedPnl", u);
                        m.put("pnl", u);
                        if (marginInr != null && marginInr.signum() > 0) {
                            m.put("roePct", u.multiply(BigDecimal.valueOf(100))
                                    .divide(marginInr, 4, java.math.RoundingMode.HALF_UP));
                        }
                        return m;
                    });
        }
        return enrichPosition(pos);
    }

    private static BigDecimal unrealizedInr(BigDecimal avg, BigDecimal mark,
                                            BigDecimal activePos, BigDecimal settle) {
        if (avg == null || mark == null || activePos == null
                || settle == null || settle.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        // active_pos is signed (short negative); CoinDCX INR = USDT move × settle rate
        return mark.subtract(avg).multiply(activePos).multiply(settle);
    }

    private static BigDecimal exchangeUnrealizedInr(JsonNode exchange, BigDecimal settle) {
        return unrealizedInr(
                decimal(exchange, "avg_price"),
                decimal(exchange, "mark_price"),
                decimal(exchange, "active_pos"),
                settle);
    }

    private static BigDecimal settleRate(JsonNode exchange, BigDecimal fallbackUsdtInr) {
        BigDecimal settle = decimal(exchange, "settlement_currency_avg_price");
        if (settle != null && settle.signum() > 0) return settle;
        return fallbackUsdtInr == null ? BigDecimal.ZERO : fallbackUsdtInr;
    }

    private static BigDecimal decimal(JsonNode n, String field) {
        if (n == null || !n.has(field) || n.get(field).isNull()) return null;
        String t = n.get(field).asText("");
        if (t == null || t.isBlank()) return null;
        try {
            return new BigDecimal(t);
        } catch (Exception e) {
            return null;
        }
    }

    private Mono<Map<String, Object>> enrichPosition(Position pos) {
        Map<String, Object> m = positionMap(pos);
        if (!"OPEN".equals(pos.status())) {
            m.put("pnl", pos.realizedPnl());
            return Mono.just(m);
        }
        return futures.lastPrice(pos.pair())
                .flatMap(mark -> futures.pnlInr(pos.entryPrice(), mark, pos.quantity(), pos.side())
                        .map(u -> {
                            m.put("markPrice", mark);
                            m.put("unrealizedPnl", u);
                            m.put("pnl", u);
                            return m;
                        }))
                .defaultIfEmpty(m);
    }

    private Mono<Map<String, Object>> enrichOrder(TradeOrder o, List<Position> allPos, BigDecimal settle) {
        Map<String, Object> m = orderMap(o);
        BigDecimal fill = o.avgPrice() != null && o.avgPrice().signum() > 0 ? o.avgPrice() : o.price();
        BigDecimal qty = o.quantity() == null ? BigDecimal.ZERO : o.quantity();
        BigDecimal s = settle == null || settle.signum() <= 0 ? BigDecimal.valueOf(100) : settle;
        if (fill != null && fill.signum() > 0 && qty.signum() > 0) {
            // CoinDCX Futures History "Filled / Size" INR = qty × avg × settlement_rate
            m.put("sizeInr", qty.multiply(fill).multiply(s));
            m.put("avgPrice", fill);
            m.put("settleRate", s);
        }
        if (o.fee() != null && o.fee().signum() != 0) {
            // Stored fee is USDT notionals for INR-M; convert like CoinDCX transaction fees.
            m.put("feeInr", o.fee().multiply(s).negate());
        }
        boolean entryLike = "BUY".equalsIgnoreCase(o.side()) || "LONG".equalsIgnoreCase(o.side());
        boolean openish = "OPEN".equals(o.status()) || "SUBMITTING".equals(o.status())
                || "PENDING_RECONCILE".equals(o.status());
        if (openish) {
            Position open = allPos.stream()
                    .filter(p -> "OPEN".equals(p.status())
                            && p.botId().equals(o.botId())
                            && p.pair().equals(o.pair()))
                    .findFirst().orElse(null);
            if (open != null) {
                return futures.lastPrice(open.pair())
                        .flatMap(mark -> futures.pnlInr(open.entryPrice(), mark, open.quantity(), open.side())
                                .map(u -> {
                                    m.put("pnl", u);
                                    m.put("markPrice", mark);
                                    return m;
                                }))
                        .defaultIfEmpty(m);
            }
            return Mono.just(m);
        }
        if ("FILLED".equals(o.status())) {
            // Attach round-trip realized only on the exit leg (SELL for long / BUY for short).
            Position closed = allPos.stream()
                    .filter(p -> "CLOSED".equals(p.status())
                            && p.botId().equals(o.botId())
                            && p.pair().equals(o.pair())
                            && p.realizedPnl() != null
                            && p.closedAt() != null
                            && Math.abs(java.time.Duration.between(p.closedAt(), o.createdAt()).toSeconds()) < 120)
                    .findFirst().orElse(null);
            if (closed != null) {
                boolean exitLeg = ("LONG".equals(closed.side()) && !entryLike)
                        || ("SHORT".equals(closed.side()) && entryLike);
                if (exitLeg) m.put("pnl", closed.realizedPnl());
            }
        }
        return Mono.just(m);
    }

    private static Map<String, Object> positionMap(Position pos) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", pos.id());
        m.put("botId", pos.botId());
        m.put("pair", pos.pair());
        m.put("side", pos.side());
        m.put("quantity", pos.quantity());
        m.put("entryPrice", pos.entryPrice());
        m.put("exitPrice", pos.exitPrice());
        m.put("status", pos.status());
        m.put("realizedPnl", pos.realizedPnl());
        m.put("leverage", pos.leverage());
        m.put("slPrice", pos.slPrice());
        m.put("targetPrice", pos.targetPrice());
        m.put("marginCurrency", pos.marginCurrency());
        m.put("openedAt", pos.openedAt());
        m.put("closedAt", pos.closedAt());
        return m;
    }

    private static Map<String, Object> orderMap(TradeOrder o) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", o.id());
        m.put("botId", o.botId());
        m.put("pair", o.pair());
        m.put("side", o.side());
        m.put("status", o.status());
        m.put("price", o.price());
        m.put("quantity", o.quantity());
        m.put("avgPrice", o.avgPrice());
        m.put("mode", o.mode());
        m.put("error", o.error());
        m.put("createdAt", o.createdAt());
        m.put("updatedAt", o.updatedAt());
        return m;
    }

    private Mono<Set<UUID>> botIds(UUID tenantId, UUID userId, String mode) {
        String m = normalizeMode(mode);
        return bots.findByTenantIdAndUserIdOrderByCreatedAtDesc(tenantId, userId)
                .filter(b -> m.equalsIgnoreCase(b.mode()))
                .map(Bot::id)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static String normalizeMode(String mode) {
        return mode == null || mode.isBlank() ? "LIVE" : mode.toUpperCase();
    }

    private Mono<Map<String, Object>> summarize(List<Position> filtered, String mode) {
        Counts c = counts(filtered);
        return Flux.fromIterable(filtered)
                .filter(pos -> "OPEN".equals(pos.status()))
                .concatMap(pos -> futures.lastPrice(pos.pair())
                        .flatMap(mark -> futures.pnlInr(pos.entryPrice(), mark, pos.quantity(), pos.side()))
                        .defaultIfEmpty(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .map(unrealized -> summaryMap(mode, c, unrealized));
    }

    private Mono<Map<String, Object>> summarizeLive(List<Position> filtered, Map<String, JsonNode> ex) {
        Counts c = counts(filtered);
        return futures.usdtInrRate().defaultIfEmpty(BigDecimal.ZERO).flatMap(tickerRate ->
                Flux.fromIterable(filtered)
                        .filter(pos -> "OPEN".equals(pos.status()))
                        .concatMap(pos -> {
                            JsonNode n = ex.get(pos.pair());
                            BigDecimal settle = n != null ? settleRate(n, tickerRate) : tickerRate;
                            BigDecimal avg = n != null ? decimal(n, "avg_price") : pos.entryPrice();
                            BigDecimal active = n != null ? decimal(n, "active_pos") : null;
                            if (active == null) {
                                BigDecimal q = pos.quantity() == null ? BigDecimal.ZERO : pos.quantity();
                                active = "SHORT".equalsIgnoreCase(pos.side()) ? q.negate() : q;
                            }
                            BigDecimal activeFinal = active;
                            BigDecimal avgFinal = avg;
                            BigDecimal exMark = n != null ? decimal(n, "mark_price") : null;
                            return futures.lastPrice(pos.pair())
                                    .switchIfEmpty(Mono.just(exMark != null ? exMark : BigDecimal.ZERO))
                                    .map(mark -> unrealizedInr(avgFinal, mark, activeFinal, settle))
                                    .defaultIfEmpty(BigDecimal.ZERO);
                        })
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .map(u -> summaryMap("LIVE", c, u)));
    }

    private record Counts(BigDecimal realized, int open, int closed, int wins) {}

    private static Counts counts(List<Position> filtered) {
        BigDecimal realized = BigDecimal.ZERO;
        int open = 0, closed = 0, wins = 0;
        for (Position pos : filtered) {
            if ("CLOSED".equals(pos.status())) {
                closed++;
                if (pos.realizedPnl() != null) {
                    realized = realized.add(pos.realizedPnl());
                    if (pos.realizedPnl().signum() > 0) wins++;
                }
            } else {
                open++;
            }
        }
        return new Counts(realized, open, closed, wins);
    }

    private static Map<String, Object> summaryMap(String mode, Counts c, BigDecimal unrealized) {
        Map<String, Object> out = new HashMap<>();
        out.put("mode", mode);
        out.put("openPositions", c.open());
        out.put("closedPositions", c.closed());
        out.put("realizedPnl", c.realized());
        out.put("unrealizedPnl", unrealized);
        out.put("winRate", c.closed() == 0 ? 0.0 : (double) c.wins() / c.closed());
        return out;
    }
}
