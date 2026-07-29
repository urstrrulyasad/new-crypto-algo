package com.cryptoalgo.backend.strategy;

import com.cryptoalgo.backend.domain.Strategy;
import com.cryptoalgo.backend.repo.AiProviderRepository;
import com.cryptoalgo.backend.repo.BotRepository;
import com.cryptoalgo.backend.repo.StrategyRepository;
import com.cryptoalgo.backend.repo.TenantRepository;
import com.cryptoalgo.backend.repo.UserRepository;
import com.cryptoalgo.backend.config.AppProperties;
import com.cryptoalgo.backend.trading.CoinDcxFuturesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hands-off FUTURES strategy generation: top-N INR instruments per tenant,
 * one concurrent LLM job, delist archives, regen only for missing instruments.
 */
@Service
public class AutoStrategyScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutoStrategyScheduler.class);

    private final AtomicBoolean busy = new AtomicBoolean(false);

    private final TenantRepository tenants;
    private final UserRepository users;
    private final AiProviderRepository providers;
    private final StrategyRepository strategies;
    private final BotRepository bots;
    private final CoinDcxFuturesClient futures;
    private final StrategyPipelineService pipeline;
    private final AppProperties props;

    public AutoStrategyScheduler(TenantRepository tenants, UserRepository users,
                                 AiProviderRepository providers, StrategyRepository strategies,
                                 BotRepository bots, CoinDcxFuturesClient futures,
                                 StrategyPipelineService pipeline, AppProperties props) {
        this.tenants = tenants;
        this.users = users;
        this.providers = providers;
        this.strategies = strategies;
        this.bots = bots;
        this.futures = futures;
        this.pipeline = pipeline;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${app.pipeline.auto-gen-ms:300000}")
    public void tick() {
        if (!busy.compareAndSet(false, true)) return;
        tenants.findAll()
                .filter(t -> "ACTIVE".equals(t.status()))
                .concatMap(t -> processTenant(t.id())
                        .onErrorResume(e -> {
                            log.error("Auto-gen for tenant {} failed: {}", t.id(), e.getMessage());
                            return Mono.empty();
                        }))
                .then()
                .doFinally(sig -> busy.set(false))
                .subscribe(v -> {}, e -> log.error("Auto-gen cycle failed", e));
    }

    private Mono<Void> processTenant(UUID tenantId) {
        return providers.findByTenantIdAndEnabledOrderByPriorityAsc(tenantId, true)
                .hasElements()
                .flatMap(hasAi -> {
                    if (!hasAi) return Mono.empty();
                    return ownerUser(tenantId).flatMap(userId ->
                            futures.activeInstruments("INR")
                                    .flatMap(all -> {
                                        int cap = props.pipeline().maxInstruments();
                                        List<String> top = rankInstruments(all, cap);
                                        Set<String> active = new HashSet<>(all);
                                        return delistGone(tenantId, active)
                                                .then(generateMissing(tenantId, userId, top));
                                    }));
                });
    }

    /** Prefer liquid majors so auto strategies are testable; then fill remaining by API order. */
    private static List<String> rankInstruments(List<String> all, int cap) {
        List<String> prefer = List.of(
                "B-BTC_USDT", "B-ETH_USDT", "B-SOL_USDT", "B-XRP_USDT", "B-DOGE_USDT",
                "B-BNB_USDT", "B-ADA_USDT", "B-AVAX_USDT", "B-LINK_USDT", "B-DOT_USDT",
                "B-MATIC_USDT", "B-SUI_USDT", "B-NEAR_USDT", "B-APT_USDT", "B-OP_USDT");
        Set<String> set = new HashSet<>(all);
        List<String> ranked = new java.util.ArrayList<>();
        for (String p : prefer) {
            if (set.contains(p)) ranked.add(p);
        }
        for (String p : all) {
            if (!ranked.contains(p)) ranked.add(p);
            if (ranked.size() >= Math.max(cap * 3, cap)) break;
        }
        return ranked.stream().limit(cap).toList();
    }

    private Mono<UUID> ownerUser(UUID tenantId) {
        return users.findByTenantId(tenantId)
                .filter(u -> "ACTIVE".equals(u.status()))
                .filter(u -> "SUPER_ADMIN".equals(u.role()) || "TENANT_ADMIN".equals(u.role()))
                .next()
                .map(u -> u.id());
    }

    private Mono<Void> delistGone(UUID tenantId, Set<String> activeInstruments) {
        return strategies.findByTenantIdAndMarketType(tenantId, "FUTURES")
                .filter(s -> s.instrument() != null && !activeInstruments.contains(s.instrument()))
                .filter(s -> !"ARCHIVED".equals(s.status()) && !"REJECTED".equals(s.status()))
                .concatMap(s -> bots.findByStrategyId(s.id())
                        .filter(b -> "RUNNING".equals(b.status()))
                        .flatMap(b -> bots.save(new com.cryptoalgo.backend.domain.Bot(
                                b.id(), b.tenantId(), b.userId(), b.strategyId(), b.exchangeKeyId(),
                                b.name(), b.mode(), b.marketType(), b.pairs(), b.stakeCurrency(),
                                b.stakeAmount(), b.maxOpenTrades(), b.leverage(), "STOPPED",
                                b.killSwitch(), b.marginCurrency(), b.createdAt(),
                                java.time.Instant.now())))
                        .then(strategies.save(StrategyPipelineService.copy(s, "ARCHIVED"))))
                .then();
    }

    private Mono<Void> generateMissing(UUID tenantId, UUID userId, List<String> top) {
        return strategies.findByTenantIdAndMarketType(tenantId, "FUTURES")
                .collectList()
                .flatMap(existing -> {
                    Set<String> activeCovered = new HashSet<>();
                    Set<String> everTried = new HashSet<>();
                    for (Strategy s : existing) {
                        if (s.instrument() == null) continue;
                        everTried.add(s.instrument());
                        if ("REJECTED".equals(s.status()) || "ARCHIVED".equals(s.status())) continue;
                        activeCovered.add(s.instrument());
                    }
                    // Prefer never-tried instruments so REJECTED retries cannot starve the queue.
                    List<String> neverTried = top.stream()
                            .filter(inst -> !everTried.contains(inst))
                            .toList();
                    List<String> retryRejected = top.stream()
                            .filter(inst -> !activeCovered.contains(inst) && everTried.contains(inst))
                            .toList();
                    List<String> queue = !neverTried.isEmpty() ? neverTried : retryRejected;
                    return Flux.fromIterable(queue)
                            .take(1)
                            .concatMap(inst -> {
                                log.info("Auto-generating FUTURES strategy for {}", inst);
                                return pipeline.generateFutures(tenantId, userId, inst)
                                        .onErrorResume(e -> {
                                            log.warn("Generate failed for {}: {}", inst, e.getMessage());
                                            return Mono.empty();
                                        });
                            })
                            .then();
                });
    }
}
