package com.cryptoalgo.backend.strategy;

import com.cryptoalgo.backend.common.AuditService;
import com.cryptoalgo.backend.common.SecretCrypto;
import com.cryptoalgo.backend.config.AppProperties;
import com.cryptoalgo.backend.domain.Bot;
import com.cryptoalgo.backend.domain.Strategy;
import com.cryptoalgo.backend.repo.BacktestRepository;
import com.cryptoalgo.backend.repo.BotRepository;
import com.cryptoalgo.backend.repo.ExchangeKeyRepository;
import com.cryptoalgo.backend.repo.StrategyRepository;
import com.cryptoalgo.backend.trading.CoinDcxFuturesClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class PaperEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(PaperEvaluationService.class);

    private final StrategyRepository strategies;
    private final BotRepository bots;
    private final ExchangeKeyRepository keys;
    private final BacktestRepository backtests;
    private final PaperStatsService paperStats;
    private final StrategyPipelineService pipeline;
    private final StrategyEngineClient engine;
    private final CoinDcxFuturesClient futures;
    private final SecretCrypto crypto;
    private final R2dbcEntityTemplate template;
    private final AuditService audit;
    private final StrategyRegenCooldown regenCooldown;
    private final StrategyPurgeService purge;
    private final AppProperties props;
    private final ObjectMapper mapper;
    private final Set<UUID> catchupStarted = ConcurrentHashMap.newKeySet();
    /** Avoid flooding audit_log every evaluate tick for the same BAD_BACKTEST. */
    private final Set<UUID> badBacktestLogged = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, Long> catchupLastStartedMs = new ConcurrentHashMap<>();
    /** Only one paper-catchup at a time — parallel 2500-bar replays kill the engine. */
    private final AtomicBoolean catchupBusy = new AtomicBoolean(false);
    private static final long CATCHUP_COOLDOWN_MS = 5 * 60_000L;
    private static final int CATCHUP_BARS = 8000;

    public PaperEvaluationService(StrategyRepository strategies, BotRepository bots,
                                  ExchangeKeyRepository keys, BacktestRepository backtests,
                                  PaperStatsService paperStats, StrategyPipelineService pipeline,
                                  StrategyEngineClient engine, CoinDcxFuturesClient futures,
                                  SecretCrypto crypto, R2dbcEntityTemplate template,
                                  AuditService audit, StrategyRegenCooldown regenCooldown,
                                  StrategyPurgeService purge,
                                  AppProperties props, ObjectMapper mapper) {
        this.strategies = strategies;
        this.bots = bots;
        this.keys = keys;
        this.backtests = backtests;
        this.paperStats = paperStats;
        this.pipeline = pipeline;
        this.engine = engine;
        this.futures = futures;
        this.crypto = crypto;
        this.template = template;
        this.audit = audit;
        this.regenCooldown = regenCooldown;
        this.purge = purge;
        this.props = props;
        this.mapper = mapper;
    }

    /** LIVE when paper trades met and (WR ≥ 60% OR paper profit ≥ 60% of stake). */
    boolean passesLivePaperGate(PaperStatsService.PaperStats stats) {
        if (stats.closedTrades() < props.pipeline().minPaperTrades()) return false;
        double stake = props.pipeline().paperStake().doubleValue();
        double profitPct = stake <= 0 ? 0 : (stats.totalPnl().doubleValue() / stake) * 100.0;
        return stats.winRate() >= props.pipeline().winRateThreshold()
                || profitPct >= props.pipeline().minPaperProfitPct();
    }

    /**
     * Manual approve — same gates as auto promotion (no force). Returns skip reason or null on success.
     */
    public Mono<ApproveLiveResult> approveLive(Strategy strategy) {
        if (!"PAPER_TRADING".equals(strategy.status())) {
            return Mono.just(new ApproveLiveResult(false, "strategy not in PAPER_TRADING", null));
        }
        return paperStats.forStrategy(strategy.id()).flatMap(stats ->
                backtests.findByTenantIdAndStrategyIdOrderByCreatedAtDesc(
                                strategy.tenantId(), strategy.id())
                        .filter(b -> "DONE".equals(b.status()) && b.metrics() != null)
                        .next()
                        .flatMap(bt -> {
                            JsonNode metrics = readMetrics(bt.metrics());
                            if (!pipeline.passesLiveBacktestQuality(metrics)) {
                                return Mono.just(new ApproveLiveResult(false,
                                        "backtest quality gate failed", stats));
                            }
                            if (!passesLivePaperGate(stats)) {
                                return Mono.just(new ApproveLiveResult(false,
                                        "paper gate not met (trades/WR/profit)", stats));
                            }
                            return promote(strategy, stats)
                                    .then(audit.record(strategy.tenantId(), strategy.userId(),
                                            "STRATEGY_MANUAL_PROMOTED_LIVE", "STRATEGY", strategy.id(),
                                            Map.of("closedTrades", String.valueOf(stats.closedTrades()),
                                                    "winRate", String.valueOf(stats.winRate()))))
                                    .thenReturn(new ApproveLiveResult(true, null, stats));
                        })
                        .switchIfEmpty(Mono.just(new ApproveLiveResult(false,
                                "no DONE backtest", stats))));
    }

    public record ApproveLiveResult(boolean ok, String reason, PaperStatsService.PaperStats paper) {}

    @Scheduled(fixedDelayString = "${app.pipeline.evaluate-ms:60000}")
    public void evaluate() {
        strategies.findByStatus("PAPER_TRADING")
                .concatWith(strategies.findByStatus("LIVE_APPROVED"))
                .filter(s -> "FUTURES".equals(s.marketType()))
                .concatMap(s -> evaluateOne(s).onErrorResume(e -> {
                    log.error("Paper evaluation for strategy {} failed", s.id(), e);
                    return Mono.empty();
                }))
                .subscribe(v -> {}, e -> log.error("Paper evaluation cycle failed", e));
    }

    Mono<Void> evaluateOne(Strategy strategy) {
        if ("LIVE_APPROVED".equals(strategy.status())) {
            return paperStats.forStrategy(strategy.id()).flatMap(stats -> {
                // Re-check current gate so config raises (e.g. 10 → 100) demote premature LIVE.
                if (!passesLivePaperGate(stats)) {
                    log.warn("Demoting {} from LIVE_APPROVED — paper {}/{} trades wr={} pnl={}",
                            strategy.instrument(), stats.closedTrades(), props.pipeline().minPaperTrades(),
                            stats.winRate(), stats.totalPnl());
                    return bots.findByStrategyId(strategy.id())
                            .filter(b -> "LIVE".equals(b.mode()) && "RUNNING".equals(b.status()))
                            .flatMap(b -> bots.save(withStatus(b, "STOPPED")))
                            .then(strategies.save(StrategyPipelineService.copy(strategy, "PAPER_TRADING")).then())
                            .then(bots.findByStrategyId(strategy.id())
                                    .filter(b -> "PAPER".equals(b.mode()))
                                    .next()
                                    .flatMap(b -> bots.save(withStatus(b, "RUNNING")))
                                    .then())
                            .then(audit.record(strategy.tenantId(), strategy.userId(),
                                    "AUTO_LIVE_DEMOTED_GATE", "STRATEGY", strategy.id(),
                                    Map.of(
                                            "paperTrades", String.valueOf(stats.closedTrades()),
                                            "requiredPaperTrades", String.valueOf(props.pipeline().minPaperTrades()),
                                            "paperWinRate", String.valueOf(stats.winRate()),
                                            "reason", "paper stats below current LIVE gate"
                                    )));
                }
                return bots.findByStrategyId(strategy.id())
                        .filter(b -> "LIVE".equals(b.mode()) && "RUNNING".equals(b.status()))
                        .hasElements()
                        .flatMap(hasLive -> {
                            if (hasLive) return Mono.empty();
                            return createLiveBot(strategy, stats);
                        });
            });
        }
        // PAPER_TRADING: never purge on backtest LIVE quality — smoke gate already allowed paper.
        // Accumulate paper fills (catchup + live signals); promote only when paper gate + LIVE BT pass.
        return ensurePaperBotRunning(strategy).then(paperStats.forStrategy(strategy.id()).flatMap(stats -> {
            if (stats.closedTrades() < props.pipeline().minPaperTrades()) {
                return kickPaperCatchup(strategy);
            }
            if (!passesLivePaperGate(stats)) {
                double stake = props.pipeline().paperStake().doubleValue();
                double profitPct = stake <= 0 ? 0
                        : (stats.totalPnl().doubleValue() / stake) * 100.0;
                log.info("Paper gate trades met for {} ({}/{}) but WR {} / profit {}% — need WR>={} or profit>={}%",
                        strategy.instrument(), stats.closedTrades(),
                        props.pipeline().minPaperTrades(),
                        stats.winRate(), String.format("%.1f", profitPct),
                        props.pipeline().winRateThreshold(),
                        props.pipeline().minPaperProfitPct());
                return Mono.empty();
            }
            return backtests.findByTenantIdAndStrategyIdOrderByCreatedAtDesc(
                            strategy.tenantId(), strategy.id())
                    .filter(b -> "DONE".equals(b.status()) && b.metrics() != null)
                    .next()
                    .flatMap(bt -> {
                        JsonNode metrics = readMetrics(bt.metrics());
                        if (!pipeline.passesLiveBacktestQuality(metrics)) {
                            // Paper passed gate but BT cannot support LIVE — keep paper, do not purge.
                            if (badBacktestLogged.add(strategy.id())) {
                                log.info("Paper OK for {} but LIVE backtest quality FAIL — staying on paper",
                                        strategy.instrument());
                            }
                            return Mono.empty();
                        }
                        badBacktestLogged.remove(strategy.id());
                        return promote(strategy, stats);
                    })
                    .switchIfEmpty(audit.record(strategy.tenantId(), strategy.userId(),
                                    "AUTO_LIVE_SKIPPED_NO_BACKTEST", "STRATEGY",
                                    strategy.id(), Map.of())
                            .then());
        }));
    }

    /** Keep paper bots RUNNING for PAPER_TRADING strategies (no LIVE sibling). */
    private Mono<Void> ensurePaperBotRunning(Strategy strategy) {
        return bots.findByStrategyId(strategy.id())
                .filter(b -> "LIVE".equals(b.mode()) && "RUNNING".equals(b.status()))
                .hasElements()
                .flatMap(hasLive -> {
                    if (hasLive) return Mono.empty();
                    return bots.findByStrategyId(strategy.id())
                            .filter(b -> "PAPER".equals(b.mode()))
                            .next()
                            .flatMap(b -> {
                                if ("RUNNING".equals(b.status()) && !b.killSwitch()) return Mono.empty();
                                if (b.killSwitch()) return Mono.empty();
                                return bots.save(withStatus(b, "RUNNING")).then();
                            });
                });
    }

    private Mono<Void> archiveUnpromotable(Strategy strategy, PaperStatsService.PaperStats stats,
                                           JsonNode metrics) {
        if (!badBacktestLogged.add(strategy.id())) {
            return Mono.empty();
        }
        String style = AutoStrategyScheduler.inferStyle(strategy);
        log.warn("Archiving unpromotable {} ({}) — btProfit={} btWr={} paper={}/{}",
                strategy.instrument(), style,
                metrics.path("profit_total_pct").asText(),
                metrics.path("win_rate").asText(),
                stats.closedTrades(), props.pipeline().minPaperTrades());
        // Soft cool this style only so other styles/coins keep generating.
        regenCooldown.block(strategy.instrument(), style, java.time.Duration.ofHours(2));
        UUID sid = strategy.id();
        return audit.record(strategy.tenantId(), strategy.userId(),
                        "STRATEGY_PURGED_BAD_BACKTEST", "STRATEGY", sid,
                        Map.of(
                                "style", style,
                                "paperTrades", String.valueOf(stats.closedTrades()),
                                "backtestProfit", metrics.path("profit_total_pct").asText(),
                                "backtestWinRate", metrics.path("win_rate").asText(),
                                "backtestPF", metrics.path("profit_factor").asText(),
                                "reason", "backtest cannot meet LIVE quality — purged"
                        ))
                .then(purge.purgeStrategy(sid));
    }

    /**
     * Replay CoinDCX candles into paper fills. Serialized globally.
     * Allowed for any PAPER_TRADING strategy (smoke already passed); LIVE BT quality
     * is only required at promotion time.
     */
    private Mono<Void> kickPaperCatchup(Strategy strategy) {
        if (strategy.instrument() == null || strategy.sourceCode() == null) return Mono.empty();
        long now = System.currentTimeMillis();
        Long last = catchupLastStartedMs.get(strategy.id());
        if (last != null && now - last < CATCHUP_COOLDOWN_MS) return Mono.empty();
        if (catchupBusy.get()) return Mono.empty();
        if (!catchupStarted.add(strategy.id())) return Mono.empty();
        if (!catchupBusy.compareAndSet(false, true)) {
            catchupStarted.remove(strategy.id());
            return Mono.empty();
        }
        catchupLastStartedMs.put(strategy.id(), now);
        String tf = "5m";
        try {
            if (strategy.config() != null) {
                tf = mapper.readTree(strategy.config().asString()).path("timeframe").asText("5m");
            }
        } catch (Exception ignored) {
            // default 5m
        }
        Map<String, Object> req = Map.of(
                "tenant_id", strategy.tenantId().toString(),
                "strategy_id", strategy.id().toString(),
                "source_code", strategy.sourceCode(),
                "pairs", List.of(strategy.instrument()),
                "timeframe", tf,
                "market_type", "FUTURES",
                "bars", CATCHUP_BARS
        );
        log.info("Paper catchup starting for {} ({}) bars={}",
                strategy.instrument(), strategy.id(), CATCHUP_BARS);
        return engine.paperCatchup(req)
                .doOnNext(r -> log.info("Paper catchup done for {}: {}", strategy.instrument(), r))
                .doOnError(e -> log.warn("Paper catchup failed for {}: {}",
                        strategy.instrument(), e.getMessage()))
                .doFinally(sig -> {
                    catchupStarted.remove(strategy.id());
                    catchupBusy.set(false);
                })
                .onErrorResume(e -> Mono.empty())
                .then();
    }

    private JsonNode readMetrics(io.r2dbc.postgresql.codec.Json json) {
        try {
            return json == null ? mapper.createObjectNode() : mapper.readTree(json.asString());
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private Mono<Void> promote(Strategy strategy, PaperStatsService.PaperStats stats) {
        return bots.countByTenantIdAndMarketTypeAndModeAndStatus(
                        strategy.tenantId(), "FUTURES", "LIVE", "RUNNING")
                .flatMap(liveCount -> {
                    if (liveCount >= props.pipeline().maxLiveBots()) {
                        return audit.record(strategy.tenantId(), strategy.userId(),
                                "AUTO_LIVE_SKIPPED_MAX_BOTS", "STRATEGY", strategy.id(),
                                Map.of("liveCount", String.valueOf(liveCount))).then();
                    }
                    Mono<Void> stopPaper = bots.findByStrategyId(strategy.id())
                            .filter(b -> "PAPER".equals(b.mode()) && "RUNNING".equals(b.status()))
                            .flatMap(b -> bots.save(withStatus(b, "STOPPED")))
                            .then();
                    Mono<Void> mark = strategies.save(
                            StrategyPipelineService.copy(strategy, "LIVE_APPROVED")).then();
                    return stopPaper.then(mark).then(createLiveBot(strategy, stats));
                });
    }

    /** Spawn a LIVE futures bot when exchange key + futures wallet allow it. */
    private Mono<Void> createLiveBot(Strategy strategy, PaperStatsService.PaperStats stats) {
        return bots.countByTenantIdAndMarketTypeAndModeAndStatus(
                        strategy.tenantId(), "FUTURES", "LIVE", "RUNNING")
                .flatMap(liveCount -> {
                    if (liveCount >= props.pipeline().maxLiveBots()) {
                        return audit.record(strategy.tenantId(), strategy.userId(),
                                "AUTO_LIVE_SKIPPED_MAX_BOTS", "STRATEGY", strategy.id(),
                                Map.of("liveCount", String.valueOf(liveCount))).then();
                    }
                    return bots.findByStrategyId(strategy.id())
                            .filter(b -> "PAPER".equals(b.mode()))
                            .next()
                            .flatMap(paperBot -> keys.findByTenantIdAndUserId(
                                            strategy.tenantId(), strategy.userId())
                                    .filter(k -> "ACTIVE".equals(k.status()))
                                    .next()
                                    .switchIfEmpty(audit.record(strategy.tenantId(), strategy.userId(),
                                                    "AUTO_LIVE_SKIPPED_NO_EXCHANGE_KEY", "STRATEGY",
                                                    strategy.id(), Map.of("reason", "no CoinDCX key"))
                                            .then(Mono.empty()))
                                    .flatMap(key -> {
                                        String apiKey = crypto.decrypt(key.apiKeyEnc());
                                        String apiSecret = crypto.decrypt(key.apiSecretEnc());
                                        return futures.availableInrBalance(apiKey, apiSecret)
                                                .doOnNext(bal -> log.info(
                                                        "INR futures wallet for strategy {}: {}",
                                                        strategy.id(), bal))
                                                .onErrorResume(e -> {
                                                    log.error("INR balance fetch failed for {}: {}",
                                                            strategy.id(), e.getMessage());
                                                    return Mono.just(BigDecimal.ZERO);
                                                })
                                                .flatMap(available -> {
                                                    String margin = "INR";
                                                    long slotsLeft = Math.max(1,
                                                            props.pipeline().maxLiveBots() - liveCount);
                                                    BigDecimal maxUse = available.multiply(
                                                            BigDecimal.valueOf(props.pipeline().maxWalletPct()));
                                                    BigDecimal perBot = maxUse.divide(
                                                            BigDecimal.valueOf(slotsLeft),
                                                            java.math.MathContext.DECIMAL64);
                                                    // Planning stake only — LIVE execution re-sizes from fresh meta.
                                                    BigDecimal stake = paperBot.stakeAmount()
                                                            .min(perBot).min(maxUse);
                                                    String instrument = strategy.instrument();
                                                    Mono<BigDecimal> planned = (instrument == null || instrument.isBlank())
                                                            ? Mono.just(stake)
                                                            : futures.usdtInrRate()
                                                            .zipWith(futures.instrumentDetails(instrument, margin)
                                                                    .onErrorResume(e -> Mono.empty()))
                                                            .zipWith(futures.lastPrice(instrument)
                                                                    .onErrorResume(e -> Mono.empty()))
                                                            .map(t -> {
                                                                BigDecimal usdtInr = t.getT1().getT1();
                                                                var inst = t.getT1().getT2();
                                                                BigDecimal px = t.getT2();
                                                                if (inst == null || !inst.hasRequiredSizingFields()
                                                                        || px == null || px.signum() <= 0
                                                                        || usdtInr.signum() <= 0) {
                                                                    return stake;
                                                                }
                                                                int lev = paperBot.leverage() == null ? 1
                                                                        : paperBot.leverage().intValue();
                                                                if (inst.maxLeverage() != null
                                                                        && BigDecimal.valueOf(lev)
                                                                        .compareTo(inst.maxLeverage()) > 0) {
                                                                    lev = inst.maxLeverage().intValue();
                                                                }
                                                                BigDecimal minQty = inst.minNotional()
                                                                        .divide(px, java.math.MathContext.DECIMAL64)
                                                                        .max(inst.minQuantity());
                                                                BigDecimal need = minQty.multiply(px).multiply(usdtInr)
                                                                        .divide(BigDecimal.valueOf(Math.max(1, lev)),
                                                                                java.math.MathContext.DECIMAL64);
                                                                return stake.max(need).min(maxUse);
                                                            })
                                                            .defaultIfEmpty(stake);
                                                    return planned.flatMap(finalStake -> {
                                                    if (finalStake.signum() <= 0) {
                                                        return audit.record(strategy.tenantId(),
                                                                        strategy.userId(),
                                                                        "AUTO_LIVE_SKIPPED_NO_BALANCE",
                                                                        "STRATEGY", strategy.id(),
                                                                        Map.of("available", available.toPlainString(),
                                                                                "currency", margin,
                                                                                "reason", "INR futures wallet empty or too small"))
                                                                .then();
                                                    }
                                                    Bot live = new Bot(UUID.randomUUID(), strategy.tenantId(),
                                                            strategy.userId(), strategy.id(), key.id(),
                                                            strategy.name() + " · live", "LIVE", "FUTURES",
                                                            paperBot.pairs(), margin, finalStake,
                                                            paperBot.maxOpenTrades(), paperBot.leverage(),
                                                            "RUNNING", false, margin,
                                                            Instant.now(), Instant.now());
                                                    return template.insert(live)
                                                            .then(audit.record(strategy.tenantId(),
                                                                    strategy.userId(),
                                                                    "STRATEGY_AUTO_PROMOTED_LIVE",
                                                                    "STRATEGY", strategy.id(),
                                                                    Map.of("winRate", String.valueOf(stats.winRate()),
                                                                            "closedTrades", String.valueOf(stats.closedTrades()),
                                                                            "stake", finalStake.toPlainString(),
                                                                            "available", available.toPlainString(),
                                                                            "marginCurrency", margin)));
                                                    });
                                                });
                                    }));
                });
    }

    private Bot withStatus(Bot b, String status) {
        return new Bot(b.id(), b.tenantId(), b.userId(), b.strategyId(), b.exchangeKeyId(), b.name(),
                b.mode(), b.marketType(), b.pairs(), b.stakeCurrency(), b.stakeAmount(),
                b.maxOpenTrades(), b.leverage(), status, b.killSwitch(), b.marginCurrency(),
                b.createdAt(), Instant.now());
    }
}
