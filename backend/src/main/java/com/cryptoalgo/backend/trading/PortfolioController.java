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
    public Flux<Position> positions(@RequestParam(defaultValue = "LIVE") String mode) {
        return CurrentUser.get().flatMapMany(p -> botIds(p.tenantId(), p.userId(), mode)
                .flatMapMany(ids -> positions.findByTenantIdAndUserIdOrderByOpenedAtDesc(
                                p.tenantId(), p.userId())
                        .filter(pos -> ids.contains(pos.botId()))));
    }

    @GetMapping("/orders")
    public Flux<TradeOrder> orders(@RequestParam(defaultValue = "LIVE") String mode) {
        return CurrentUser.get().flatMapMany(p -> botIds(p.tenantId(), p.userId(), mode)
                .flatMapMany(ids -> orders.findByTenantIdAndUserIdOrderByCreatedAtDesc(
                                p.tenantId(), p.userId())
                        .filter(o -> ids.contains(o.botId()))));
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
                        .flatMap(key -> futures.availableInrBalance(
                                        crypto.decrypt(key.apiKeyEnc()),
                                        crypto.decrypt(key.apiSecretEnc()))
                                .map(bal -> Map.<String, Object>of(
                                        "currency", "INR",
                                        "available", bal,
                                        "source", "CoinDCX futures wallet"))));
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
                        .map(mark -> {
                            BigDecimal dir = "SHORT".equals(pos.side())
                                    ? BigDecimal.valueOf(-1) : BigDecimal.ONE;
                            return mark.subtract(pos.entryPrice())
                                    .multiply(pos.quantity()).multiply(dir).multiply(pos.leverage());
                        })
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
