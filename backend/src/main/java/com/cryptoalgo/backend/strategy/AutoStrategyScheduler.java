package com.cryptoalgo.backend.strategy;

import com.cryptoalgo.backend.domain.Strategy;
import com.cryptoalgo.backend.repo.AiProviderRepository;
import com.cryptoalgo.backend.repo.BacktestRepository;
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

import java.time.Duration;
import java.time.Instant;
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
    private final BacktestRepository backtests;
    private final CoinDcxFuturesClient futures;
    private final StrategyPipelineService pipeline;
    private final StrategyRegenCooldown regenCooldown;
    private final StrategyPurgeService purge;
    private final AppProperties props;

    public AutoStrategyScheduler(TenantRepository tenants, UserRepository users,
                                 AiProviderRepository providers, StrategyRepository strategies,
                                 BotRepository bots, BacktestRepository backtests,
                                 CoinDcxFuturesClient futures,
                                 StrategyPipelineService pipeline,
                                 StrategyRegenCooldown regenCooldown, StrategyPurgeService purge,
                                 AppProperties props) {
        this.tenants = tenants;
        this.users = users;
        this.providers = providers;
        this.strategies = strategies;
        this.bots = bots;
        this.backtests = backtests;
        this.futures = futures;
        this.pipeline = pipeline;
        this.regenCooldown = regenCooldown;
        this.purge = purge;
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
                                        if (all == null || all.isEmpty()) {
                                            log.warn("CoinDCX returned no INR instruments — skipping auto-gen");
                                            return Mono.empty();
                                        }
                                        int cap = props.pipeline().maxInstruments();
                                        List<String> top = rankInstruments(all, cap);
                                        Set<String> active = new HashSet<>(all);
                                        String probe = top.isEmpty() ? all.get(0) : top.get(0);
                                        // Refuse the whole cycle if public CoinDCX market data is down.
                                        return pipeline.requireLiveMarketData(probe, "5m")
                                                .then(delistGone(tenantId, active))
                                                .then(resumeStuckGenerated(tenantId))
                                                .then(resumeBacktestedAwaitingPaper(tenantId))
                                                .then(generateMissing(tenantId, userId, top))
                                                .onErrorResume(e -> {
                                                    log.warn("Skipping auto-gen — CoinDCX not ready: {}",
                                                            e.getMessage());
                                                    return Mono.empty();
                                                });
                                    })
                                    .onErrorResume(e -> {
                                        log.warn("CoinDCX instruments API failed — skipping auto-gen: {}",
                                                e.getMessage());
                                        return Mono.empty();
                                    }));
                });
    }

    /**
     * GENERATED + mid-flight backtest dies on backend restart (fire-and-forget subscribe).
     * Fail stale RUNNING rows and re-kick continuePipeline so ETH etc. reach paper.
     */
    private Mono<Void> resumeStuckGenerated(UUID tenantId) {
        Instant staleBefore = Instant.now().minus(Duration.ofMinutes(5));
        return strategies.findByTenantIdAndMarketType(tenantId, "FUTURES")
                .filter(s -> "GENERATED".equals(s.status()))
                .concatMap(s -> backtests.findByTenantIdAndStrategyIdOrderByCreatedAtDesc(tenantId, s.id())
                        .next()
                        .flatMap(bt -> {
                            if ("RUNNING".equals(bt.status()) && bt.createdAt().isBefore(staleBefore)) {
                                log.warn("Stale RUNNING backtest {} for {}; re-queuing pipeline",
                                        bt.id(), s.instrument());
                                return backtests.save(new com.cryptoalgo.backend.domain.Backtest(
                                                bt.id(), bt.tenantId(), bt.strategyId(), bt.timeframe(),
                                                bt.pairs(), bt.rangeStart(), bt.rangeEnd(), "FAILED",
                                                bt.metrics(), bt.trades(), "interrupted (stale RUNNING)",
                                                bt.createdAt(), Instant.now()))
                                        .doOnSuccess(v -> pipeline.continuePipeline(s))
                                        .thenReturn(true);
                            }
                            if ("FAILED".equals(bt.status()) || "DONE".equals(bt.status())) {
                                log.info("Resuming GENERATED {} after backtest {}", s.instrument(), bt.status());
                                pipeline.continuePipeline(s);
                                return Mono.just(true);
                            }
                            return Mono.just(false);
                        })
                        .switchIfEmpty(Mono.fromCallable(() -> {
                            log.info("Resuming GENERATED {} with no backtest row", s.instrument());
                            pipeline.continuePipeline(s);
                            return true;
                        })))
                .then();
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
                        .then(purge.purgeStrategy(s.id())))
                .then();
    }

    /**
     * BACKTESTED strategies that never got a paper bot (CoinDCX was down at gate time).
     * Re-kick continuePipeline once live market data is available again.
     */
    private Mono<Void> resumeBacktestedAwaitingPaper(UUID tenantId) {
        return strategies.findByTenantIdAndMarketType(tenantId, "FUTURES")
                .filter(s -> "BACKTESTED".equals(s.status()))
                .concatMap(s -> bots.findByStrategyId(s.id())
                        .filter(b -> "PAPER".equals(b.mode()) && "RUNNING".equals(b.status()))
                        .hasElements()
                        .flatMap(hasPaper -> {
                            if (hasPaper) return Mono.empty();
                            log.info("Resuming BACKTESTED {} — no paper bot yet (CoinDCX gate)",
                                    s.instrument());
                            pipeline.continuePipeline(s);
                            return Mono.just(true);
                        }))
                .then();
    }

    private Mono<Void> generateMissing(UUID tenantId, UUID userId, List<String> top) {
        int maxPer = Math.max(1, props.pipeline().maxStrategiesPerInstrument());
        List<String> styles = StrategyPipelineService.STRATEGY_STYLES;
        return strategies.findByTenantIdAndMarketType(tenantId, "FUTURES")
                .collectList()
                .flatMap(existing -> {
                    // instrument -> count of active strategies; instrument -> styles already used
                    java.util.Map<String, Integer> activeCount = new java.util.HashMap<>();
                    java.util.Map<String, Set<String>> usedStyles = new java.util.HashMap<>();
                    for (Strategy s : existing) {
                        if (s.instrument() == null) continue;
                        String style = inferStyle(s);
                        // Terminal rows do not occupy active slots and do not seed
                        // long cooldowns (that blocked continuous LLM generation).
                        if ("REJECTED".equalsIgnoreCase(s.status())
                                || "ARCHIVED".equalsIgnoreCase(s.status())) {
                            continue;
                        }
                        activeCount.merge(s.instrument(), 1, Integer::sum);
                        usedStyles.computeIfAbsent(s.instrument(), k -> new HashSet<>()).add(style);
                    }
                    record Job(String instrument, String style) {}
                    List<Job> queue = new java.util.ArrayList<>();
                    for (String inst : top) {
                        int n = activeCount.getOrDefault(inst, 0);
                        if (n >= maxPer) continue;
                        if (styles.stream().allMatch(st -> regenCooldown.isBlocked(inst, st))) {
                            log.info("Skip {} — soft regen cooldown active", inst);
                            continue;
                        }
                        Set<String> have = usedStyles.getOrDefault(inst, Set.of());
                        for (String style : styles) {
                            if (have.contains(style)) continue;
                            if (regenCooldown.isBlocked(inst, style)) {
                                log.info("Skip {} / {} — soft regen cooldown", inst, style);
                                continue;
                            }
                            queue.add(new Job(inst, style));
                            break; // one new style per instrument per pass
                        }
                    }
                    // Prefer coins with fewer strategies, then list order.
                    queue.sort((a, b) -> Integer.compare(
                            activeCount.getOrDefault(a.instrument(), 0),
                            activeCount.getOrDefault(b.instrument(), 0)));
                    if (queue.isEmpty()) {
                        log.info("Auto-gen: nothing to create (active slots full or soft cooldown)");
                        return Mono.empty();
                    }
                    // Continuous generation: up to 2 instruments per tick when LLMs are healthy.
                    int batch = Math.min(2, queue.size());
                    return Flux.fromIterable(queue)
                            .take(batch)
                            .concatMap(job -> {
                                log.info("Auto-generating FUTURES {} strategy for {} ({}/{} active)",
                                        job.style(), job.instrument(),
                                        activeCount.getOrDefault(job.instrument(), 0), maxPer);
                                return pipeline.generateFutures(tenantId, userId, job.instrument(), job.style())
                                        .onErrorResume(e -> {
                                            log.warn("Generate failed for {} / {}: {}",
                                                    job.instrument(), job.style(), e.getMessage());
                                            return Mono.empty();
                                        });
                            })
                            .then();
                });
    }

    /**
     * Resolve style for slot/cooldown dedupe.
     * Prefer config.strategy_style and name labels — never scan the full prompt
     * (goals say "do not chase trends" / "momentum" and mis-tag MR as Trend/Breakout).
     */
    static String inferStyle(Strategy s) {
        try {
            if (s.config() != null) {
                String cfg = s.config().asString();
                String fromCfg = extractJsonString(cfg, "strategy_style");
                if (fromCfg != null && !fromCfg.isBlank()) {
                    return StrategyPipelineService.normalizeStyle(fromCfg);
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        String name = (s.name() == null ? "" : s.name()).toLowerCase(java.util.Locale.ROOT);
        if (name.contains(" · mr") || name.endsWith(" mr") || name.contains(" mean-reversion")) {
            return "mean-reversion";
        }
        if (name.contains(" · trend") || name.contains(" · tf")) {
            return "trend-following";
        }
        if (name.contains(" · breakout") || name.contains(" · bm") || name.contains(" · momentum")) {
            return "breakout-momentum";
        }
        // Legacy unnamed futures row → mean-reversion slot.
        return "mean-reversion";
    }

    /** Tiny key extract so scheduler does not need a full JSON parse dependency path. */
    private static String extractJsonString(String json, String key) {
        if (json == null || key == null) return null;
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) return null;
        int colon = json.indexOf(':', i + needle.length());
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }
}
