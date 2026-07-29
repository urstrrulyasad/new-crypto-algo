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
    private final AppProperties props;
    private final ObjectMapper mapper;
    private final Set<UUID> catchupStarted = ConcurrentHashMap.newKeySet();

    public PaperEvaluationService(StrategyRepository strategies, BotRepository bots,
                                  ExchangeKeyRepository keys, BacktestRepository backtests,
                                  PaperStatsService paperStats, StrategyPipelineService pipeline,
                                  StrategyEngineClient engine, CoinDcxFuturesClient futures,
                                  SecretCrypto crypto, R2dbcEntityTemplate template,
                                  AuditService audit, AppProperties props, ObjectMapper mapper) {
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
        this.props = props;
        this.mapper = mapper;
    }

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
            return bots.findByStrategyId(strategy.id())
                    .filter(b -> "LIVE".equals(b.mode()) && "RUNNING".equals(b.status()))
                    .hasElements()
                    .flatMap(hasLive -> {
                        if (hasLive) return Mono.empty();
                        return paperStats.forStrategy(strategy.id())
                                .flatMap(stats -> createLiveBot(strategy, stats));
                    });
        }
        return paperStats.forStrategy(strategy.id()).flatMap(stats -> {
            if (stats.closedTrades() < props.pipeline().minPaperTrades()) {
                kickPaperCatchup(strategy);
                return Mono.empty();
            }
            if (stats.winRate() < props.pipeline().winRateThreshold()) return Mono.empty();
            return backtests.findByTenantIdAndStrategyIdOrderByCreatedAtDesc(
                            strategy.tenantId(), strategy.id())
                    .filter(b -> "DONE".equals(b.status()) && b.metrics() != null)
                    .next()
                    .flatMap(bt -> {
                        JsonNode metrics = readMetrics(bt.metrics());
                        if (!pipeline.passesLiveBacktestQuality(metrics)) {
                            return audit.record(strategy.tenantId(), strategy.userId(),
                                    "AUTO_LIVE_SKIPPED_BAD_BACKTEST", "STRATEGY", strategy.id(),
                                    Map.of(
                                            "paperWinRate", String.valueOf(stats.winRate()),
                                            "paperTrades", String.valueOf(stats.closedTrades()),
                                            "backtestProfit", metrics.path("profit_total_pct").asText(),
                                            "backtestWinRate", metrics.path("win_rate").asText(),
                                            "requiredPaperWinRate", String.valueOf(props.pipeline().winRateThreshold())
                                    ))
                                    .thenReturn(false);
                        }
                        return promote(strategy, stats).thenReturn(true);
                    })
                    .switchIfEmpty(audit.record(strategy.tenantId(), strategy.userId(),
                            "AUTO_LIVE_SKIPPED_NO_BACKTEST", "STRATEGY", strategy.id(), Map.of())
                            .thenReturn(false))
                    .then();
        });
    }

    /**
     * One-shot replay of recent CoinDCX candles into paper fills so the LIVE gate
     * can progress without waiting wall-clock days on 5m entries.
     */
    private void kickPaperCatchup(Strategy strategy) {
        if (strategy.instrument() == null || strategy.sourceCode() == null) return;
        if (!catchupStarted.add(strategy.id())) return;
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
                "bars", 800
        );
        log.info("Paper catchup starting for {} ({})", strategy.instrument(), strategy.id());
        engine.paperCatchup(req)
                .doOnNext(r -> log.info("Paper catchup done for {}: {}", strategy.instrument(), r))
                .doOnError(e -> {
                    catchupStarted.remove(strategy.id());
                    log.warn("Paper catchup failed for {}: {}", strategy.instrument(), e.getMessage());
                })
                .onErrorResume(e -> Mono.empty())
                .subscribe();
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
                                                    BigDecimal stake = paperBot.stakeAmount()
                                                            .min(perBot).min(maxUse);
                                                    if (stake.signum() <= 0) {
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
                                                            paperBot.pairs(), margin, stake,
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
                                                                            "stake", stake.toPlainString(),
                                                                            "available", available.toPlainString(),
                                                                            "marginCurrency", margin)));
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
