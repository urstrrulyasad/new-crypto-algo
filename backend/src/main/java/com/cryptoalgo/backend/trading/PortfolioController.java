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
import com.cryptoalgo.backend.security.CurrentUser;
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
        return CurrentUser.get().flatMapMany(p -> botIds(p.tenantId(), p.userId(), mode)
                .flatMapMany(ids -> positions.findByTenantIdAndUserIdOrderByOpenedAtDesc(
                                p.tenantId(), p.userId())
                        .filter(pos -> ids.contains(pos.botId()))
                        .concatMap(this::enrichPosition)));
    }

    @GetMapping("/orders")
    public Flux<Map<String, Object>> orders(@RequestParam(defaultValue = "LIVE") String mode) {
        return CurrentUser.get().flatMapMany(p -> botIds(p.tenantId(), p.userId(), mode)
                .flatMapMany(ids -> Mono.zip(
                                orders.findByTenantIdAndUserIdOrderByCreatedAtDesc(
                                                p.tenantId(), p.userId())
                                        .filter(o -> ids.contains(o.botId()))
                                        .collectList(),
                                positions.findByTenantIdAndUserIdOrderByOpenedAtDesc(
                                                p.tenantId(), p.userId())
                                        .filter(pos -> ids.contains(pos.botId()))
                                        .collectList()
                        ).flatMapMany(t -> Flux.fromIterable(t.getT1())
                                .concatMap(o -> enrichOrder(o, t.getT2())))));
    }

    @GetMapping("/summary")
    public Mono<Map<String, Object>> summary(@RequestParam(defaultValue = "LIVE") String mode) {
        String m = normalizeMode(mode);
        return CurrentUser.get().flatMap(p ->
                botIds(p.tenantId(), p.userId(), m).flatMap(ids ->
                        positions.findByTenantIdAndUserIdOrderByOpenedAtDesc(p.tenantId(), p.userId())
                                .filter(pos -> ids.contains(pos.botId()))
                                .collectList()
                                .flatMap(list -> summarize(list, m))));
    }

    @GetMapping("/wallet")
    public Mono<Map<String, Object>> wallet() {
        return CurrentUser.get().flatMap(p ->
                keys.findByTenantIdAndUserId(p.tenantId(), p.userId())
                        .filter(k -> "ACTIVE".equals(k.status()))
                        .next()
                        .switchIfEmpty(Mono.error(ApiException.notFound("No active CoinDCX key")))
                        .flatMap(key -> futures.inrWallet(
                                        crypto.decrypt(key.apiKeyEnc()),
                                        crypto.decrypt(key.apiSecretEnc()))
                                .map(w -> {
                                    Map<String, Object> out = new HashMap<>();
                                    out.put("currency", "INR");
                                    out.put("available", w.available());
                                    out.put("locked", w.locked());
                                    out.put("walletEquity", w.walletEquity());
                                    out.put("source", "CoinDCX futures wallet");
                                    return out;
                                })));
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

    private Mono<Map<String, Object>> enrichOrder(TradeOrder o, List<Position> allPos) {
        Map<String, Object> m = orderMap(o);
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
        if ("FILLED".equals(o.status()) || "OPEN".equals(o.status())) {
            // Prefer closed position realized PnL for same bot/pair near this order.
            Position closed = allPos.stream()
                    .filter(p -> "CLOSED".equals(p.status())
                            && p.botId().equals(o.botId())
                            && p.pair().equals(o.pair())
                            && p.realizedPnl() != null)
                    .findFirst().orElse(null);
            if (closed != null && !entryLike) {
                m.put("pnl", closed.realizedPnl());
            } else if (closed != null && "FILLED".equals(o.status())) {
                m.put("pnl", closed.realizedPnl());
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
        BigDecimal realizedFinal = realized;
        int closedFinal = closed;
        int winsFinal = wins;
        int openFinal = open;

        return Flux.fromIterable(filtered)
                .filter(pos -> "OPEN".equals(pos.status()))
                .concatMap(pos -> futures.lastPrice(pos.pair())
                        .flatMap(mark -> futures.pnlInr(pos.entryPrice(), mark, pos.quantity(), pos.side()))
                        .defaultIfEmpty(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .map(unrealized -> {
                    Map<String, Object> out = new HashMap<>();
                    out.put("mode", mode);
                    out.put("openPositions", openFinal);
                    out.put("closedPositions", closedFinal);
                    out.put("realizedPnl", realizedFinal);
                    out.put("unrealizedPnl", unrealized);
                    out.put("winRate", closedFinal == 0 ? 0.0 : (double) winsFinal / closedFinal);
                    return out;
                });
    }
}
