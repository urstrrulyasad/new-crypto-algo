package com.cryptoalgo.backend.strategy;

import com.cryptoalgo.backend.ai.AiChainService;
import com.cryptoalgo.backend.common.AuditService;
import com.cryptoalgo.backend.config.AppProperties;
import com.cryptoalgo.backend.domain.Backtest;
import com.cryptoalgo.backend.domain.Bot;
import com.cryptoalgo.backend.domain.Strategy;
import com.cryptoalgo.backend.repo.BacktestRepository;
import com.cryptoalgo.backend.repo.StrategyRepository;
import com.cryptoalgo.backend.trading.CoinDcxFuturesClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Futures-only autonomous pipeline: generate → backtest gate → paper bot.
 */
@Service
public class StrategyPipelineService {

    private static final Logger log = LoggerFactory.getLogger(StrategyPipelineService.class);

    private final AiChainService aiChain;
    private final StrategyEngineClient engine;
    private final CoinDcxFuturesClient futures;
    private final StrategyRepository strategies;
    private final BacktestRepository backtests;
    private final R2dbcEntityTemplate template;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final AppProperties props;

    public StrategyPipelineService(AiChainService aiChain, StrategyEngineClient engine,
                                   CoinDcxFuturesClient futures, StrategyRepository strategies,
                                   BacktestRepository backtests, R2dbcEntityTemplate template,
                                   ObjectMapper mapper, AuditService audit, AppProperties props) {
        this.aiChain = aiChain;
        this.engine = engine;
        this.futures = futures;
        this.strategies = strategies;
        this.backtests = backtests;
        this.template = template;
        this.mapper = mapper;
        this.audit = audit;
        this.props = props;
    }

    /** Auto-generate a FUTURES strategy for one INR instrument. */
    public Mono<Strategy> generateFutures(UUID tenantId, UUID userId, String instrument) {
        // 5m: enough signal density for paper fills, better AI backtest quality than 1m noise.
        String timeframe = "5m";
        String goal = "INR-margined CoinDCX futures strategy for " + instrument
                + ". Long and short with strict risk. Prefer high win-rate mean-reversion or "
                + "trend-following with clear filters. Target profit_factor > 1.2, win_rate > 55%, "
                + "positive total profit, max drawdown under 35%. Avoid overtrading. Timeframe 5m.";
        String name = "FUT " + instrument;
        List<String> pairs = List.of(instrument);

        return aiChain.chain(tenantId).flatMap(chain ->
                futures.recentCsv(instrument, CoinDcxFuturesClient.toFuturesResolution(timeframe), 100)
                        .onErrorReturn("")
                        .flatMap(csv -> {
                            Map<String, Object> engineReq = new java.util.HashMap<>();
                            engineReq.put("providers", chain.chain());
                            engineReq.put("goal", goal);
                            engineReq.put("timeframe", timeframe);
                            engineReq.put("pairs", pairs);
                            engineReq.put("risk_profile", "balanced");
                            engineReq.put("market_type", "FUTURES");
                            if (!csv.isBlank()) engineReq.put("market_data", csv);
                            return engine.generateStrategy(engineReq);
                        })
                        .flatMap(result -> {
                            boolean valid = result.path("valid").asBoolean(false);
                            String providerUsed = result.path("provider_used").asText(null);
                            UUID providerId = providerUsed == null ? null
                                    : chain.providerIds().get(providerUsed);
                            JsonNode config = enrich(result.path("config"), timeframe, pairs,
                                    result.path("model_used").asText(null), providerUsed,
                                    valid ? null : result.path("errors"));
                            Strategy s = new Strategy(UUID.randomUUID(), tenantId, userId,
                                    name, 1, null, result.path("source_code").asText(""),
                                    Json.of(config.toString()), valid ? "GENERATED" : "REJECTED",
                                    "AI_GENERATED", providerId, goal,
                                    "FUTURES", instrument, "INR", Instant.now());
                            return template.insert(s)
                                    .then(audit.record(tenantId, userId,
                                            valid ? "STRATEGY_GENERATED" : "STRATEGY_REJECTED",
                                            "STRATEGY", s.id(),
                                            Map.of("instrument", instrument,
                                                    "provider", providerUsed == null ? "?" : providerUsed)))
                                    .thenReturn(s)
                                    .doOnSuccess(saved -> {
                                        if (valid) continuePipeline(saved);
                                    });
                        }));
    }

    private void continuePipeline(Strategy strategy) {
        String instrument = strategy.instrument();
        List<String> pairs = List.of(instrument);
        String timeframe = CoinDcxFuturesClient.normalizeFuturesTimeframe(
                readTimeframe(strategy.config()));
        Instant end = Instant.now();
        Instant start = end.minus(Duration.ofDays(props.pipeline().backtestDays()));
        Backtest bt = new Backtest(UUID.randomUUID(), strategy.tenantId(), strategy.id(),
                timeframe, toJson(pairs), start, end, "RUNNING", null, null, null, Instant.now(), null);
        int leverage = props.pipeline().futuresLeverage();

        template.insert(bt)
                .flatMap(saved -> engine.runBacktest(Map.of(
                                "source_code", strategy.sourceCode(),
                                "pairs", pairs,
                                "timeframe", timeframe,
                                "start", start.toString(),
                                "end", end.toString(),
                                "stake", props.pipeline().paperStake(),
                                "leverage", leverage,
                                "market_type", "FUTURES"))
                        .flatMap(result -> {
                            JsonNode metrics = result.path("metrics");
                            boolean pass = passesBacktestGate(metrics);
                            return backtests.save(new Backtest(saved.id(), saved.tenantId(),
                                            saved.strategyId(), saved.timeframe(), saved.pairs(),
                                            saved.rangeStart(), saved.rangeEnd(), "DONE",
                                            Json.of(metrics.toString()),
                                            Json.of(result.path("trades").toString()),
                                            null, saved.createdAt(), Instant.now()))
                                    .then(Mono.just(pass));
                        })
                        .onErrorResume(e -> {
                            log.warn("Auto-backtest for {} failed: {}", strategy.id(), e.getMessage());
                            return backtests.save(new Backtest(saved.id(), saved.tenantId(),
                                            saved.strategyId(), saved.timeframe(), saved.pairs(),
                                            saved.rangeStart(), saved.rangeEnd(), "FAILED",
                                            null, null, e.getMessage(), saved.createdAt(), Instant.now()))
                                    .thenReturn(false);
                        }))
                .flatMap(pass -> {
                    if (!pass) {
                        return setStatus(strategy.id(), "REJECTED")
                                .then(audit.record(strategy.tenantId(), strategy.userId(),
                                        "STRATEGY_BACKTEST_GATE_FAILED", "STRATEGY", strategy.id(), Map.of(
                                                "instrument", instrument,
                                                "reason", "backtest quality gate")));
                    }
                    return setStatus(strategy.id(), "BACKTESTED")
                            .then(startPaperBot(strategy, pairs))
                            .then(setStatus(strategy.id(), "PAPER_TRADING"))
                            .then(audit.record(strategy.tenantId(), strategy.userId(),
                                    "PAPER_TRADING_STARTED", "STRATEGY", strategy.id(),
                                    Map.of("instrument", instrument)));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        v -> log.info("Pipeline for {} finished", strategy.id()),
                        e -> log.error("Pipeline for {} failed", strategy.id(), e));
    }

    private boolean passesBacktestGate(JsonNode metrics) {
        // Paper entry is a smoke screen: enough trades and non-catastrophic DD.
        // LIVE promotion re-checks quality metrics + 75% paper win-rate.
        int trades = metrics.path("trades").asInt(0);
        double maxDd = metrics.path("max_drawdown_pct").asDouble(100);
        boolean pass = trades >= props.pipeline().minBacktestTrades()
                && maxDd <= props.pipeline().maxBacktestDrawdownPct();
        if (!pass) {
            log.info("Backtest smoke gate FAIL trades={} dd={} (need trades>={} dd<={})",
                    trades, maxDd,
                    props.pipeline().minBacktestTrades(),
                    props.pipeline().maxBacktestDrawdownPct());
        }
        return pass;
    }

    /** Stricter quality check used before LIVE promotion. */
    public boolean passesLiveBacktestQuality(JsonNode metrics) {
        int trades = metrics.path("trades").asInt(0);
        double maxDd = metrics.path("max_drawdown_pct").asDouble(100);
        double winRate = metrics.path("win_rate").asDouble(0);
        double profitPct = metrics.path("profit_total_pct").asDouble(-100);
        double profitFactor = metrics.path("profit_factor").asDouble(0);
        boolean pass = trades >= props.pipeline().minBacktestTrades()
                && maxDd <= props.pipeline().maxBacktestDrawdownPct()
                && winRate >= props.pipeline().minBacktestWinRate()
                && profitPct >= props.pipeline().minBacktestProfitPct()
                && profitFactor >= props.pipeline().minBacktestProfitFactor();
        if (!pass) {
            log.info("LIVE backtest quality FAIL trades={} wr={} profit={} pf={} dd={}",
                    trades, winRate, profitPct, profitFactor, maxDd);
        }
        return pass;
    }

    private Mono<Bot> startPaperBot(Strategy strategy, List<String> pairs) {
        Bot bot = new Bot(UUID.randomUUID(), strategy.tenantId(), strategy.userId(), strategy.id(),
                null, strategy.name() + " · paper", "PAPER", "FUTURES", toJson(pairs), "INR",
                props.pipeline().paperStake(), props.pipeline().maxOpenTrades(),
                BigDecimal.valueOf(props.pipeline().futuresLeverage()), "RUNNING", false,
                "INR", Instant.now(), Instant.now());
        return template.insert(bot);
    }

    private Mono<Void> setStatus(UUID strategyId, String status) {
        return strategies.findById(strategyId)
                .flatMap(s -> strategies.save(copy(s, status)))
                .then();
    }

    static Strategy copy(Strategy s, String status) {
        return new Strategy(s.id(), s.tenantId(), s.userId(), s.name(), s.version(), s.parentId(),
                s.sourceCode(), s.config(), status, s.origin(), s.aiProviderId(), s.prompt(),
                s.marketType(), s.instrument(), s.marginCurrency(), s.createdAt());
    }

    private String readTimeframe(Json config) {
        try {
            JsonNode n = mapper.readTree(config.asString());
            return n.path("timeframe").asText("1h");
        } catch (Exception e) {
            return "1h";
        }
    }

    private JsonNode enrich(JsonNode config, String timeframe, List<String> pairs,
                            String model, String provider, JsonNode errors) {
        var node = config != null && config.isObject()
                ? (com.fasterxml.jackson.databind.node.ObjectNode) config.deepCopy()
                : mapper.createObjectNode();
        node.put("timeframe", CoinDcxFuturesClient.normalizeFuturesTimeframe(
                node.hasNonNull("timeframe") ? node.get("timeframe").asText() : timeframe));
        node.set("pairs", mapper.valueToTree(pairs));
        node.put("market_type", "FUTURES");
        node.put("margin_currency", "INR");
        if (provider != null) node.put("provider_used", provider);
        if (model != null) node.put("model_used", model);
        if (errors != null && !errors.isMissingNode()) node.set("generation_errors", errors);
        return node;
    }

    private Json toJson(Object value) {
        try {
            return Json.of(mapper.writeValueAsString(value));
        } catch (Exception e) {
            return Json.of("[]");
        }
    }
}
