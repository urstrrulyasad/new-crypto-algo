package com.cryptoalgo.backend.strategy;

import com.cryptoalgo.backend.ai.AiChainService;
import com.cryptoalgo.backend.common.AuditService;
import com.cryptoalgo.backend.config.AppProperties;
import com.cryptoalgo.backend.domain.Backtest;
import com.cryptoalgo.backend.domain.Bot;
import com.cryptoalgo.backend.domain.Strategy;
import com.cryptoalgo.backend.market.CandleService;
import com.cryptoalgo.backend.repo.BacktestRepository;
import com.cryptoalgo.backend.repo.StrategyRepository;
import com.cryptoalgo.backend.security.AuthPrincipal;
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
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The autonomous strategy pipeline. One call runs the whole flow:
 *
 *   AI generation (with model/provider rate-limit failover, live candles in
 *   the prompt) -> validation -> automatic backtest -> automatic paper bot.
 *
 * The paper-trade gate ({@link PaperEvaluationService}) later promotes the
 * strategy to live trading once it proves itself. There is no manual path.
 */
@Service
public class StrategyPipelineService {

    private static final Logger log = LoggerFactory.getLogger(StrategyPipelineService.class);

    private final AiChainService aiChain;
    private final StrategyEngineClient engine;
    private final CandleService candles;
    private final StrategyRepository strategies;
    private final BacktestRepository backtests;
    private final R2dbcEntityTemplate template;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final AppProperties props;

    public StrategyPipelineService(AiChainService aiChain, StrategyEngineClient engine,
                                   CandleService candles, StrategyRepository strategies,
                                   BacktestRepository backtests, R2dbcEntityTemplate template,
                                   ObjectMapper mapper, AuditService audit, AppProperties props) {
        this.aiChain = aiChain;
        this.engine = engine;
        this.candles = candles;
        this.strategies = strategies;
        this.backtests = backtests;
        this.template = template;
        this.mapper = mapper;
        this.audit = audit;
        this.props = props;
    }

    /**
     * Generates a strategy through the provider failover chain and kicks off
     * the automatic backtest + paper bot in the background. Returns as soon as
     * the strategy row exists (status GENERATED or REJECTED).
     */
    public Mono<Strategy> generate(AuthPrincipal p, String name, String goal, String timeframe,
                                   List<String> pairs, String riskProfile) {
        List<String> effPairs = pairs == null || pairs.isEmpty() ? List.of("B-BTC_USDT") : pairs;
        String effTimeframe = normalizeTimeframe(timeframe == null || timeframe.isBlank() ? "1h" : timeframe);
        String effGoal = goal == null || goal.isBlank()
                ? "Generate a consistently profitable trend/momentum strategy with strict risk control."
                : goal;
        String effRisk = riskProfile == null || riskProfile.isBlank() ? "balanced" : riskProfile;
        String effName = name == null || name.isBlank()
                ? "AI " + effPairs.get(0) + " " + DateTimeFormatter.ofPattern("MMdd-HHmm")
                        .format(Instant.now().atZone(java.time.ZoneOffset.UTC))
                : name;

        return aiChain.chain(p.tenantId()).flatMap(chain ->
                candles.recentCsv(effPairs.get(0), effTimeframe, 100)
                        .onErrorReturn("")
                        .flatMap(csv -> {
                            Map<String, Object> engineReq = new java.util.HashMap<>();
                            engineReq.put("providers", chain.chain());
                            engineReq.put("goal", effGoal);
                            engineReq.put("timeframe", effTimeframe);
                            engineReq.put("pairs", effPairs);
                            engineReq.put("risk_profile", effRisk);
                            if (!csv.isBlank()) engineReq.put("market_data", csv);
                            return engine.generateStrategy(engineReq);
                        })
                        .flatMap(result -> {
                            boolean valid = result.path("valid").asBoolean(false);
                            String providerUsed = result.path("provider_used").asText(null);
                            UUID providerId = providerUsed == null ? null
                                    : chain.providerIds().get(providerUsed);
                            JsonNode config = enrich(result.path("config"), effTimeframe, effPairs,
                                    result.path("model_used").asText(null), providerUsed,
                                    valid ? null : result.path("errors"));
                            Strategy s = new Strategy(UUID.randomUUID(), p.tenantId(), p.userId(),
                                    effName, 1, null, result.path("source_code").asText(""),
                                    Json.of(config.toString()), valid ? "GENERATED" : "REJECTED",
                                    "AI_GENERATED", providerId, effGoal, Instant.now());
                            return template.insert(s)
                                    .then(audit.record(p.tenantId(), p.userId(),
                                            valid ? "STRATEGY_GENERATED" : "STRATEGY_REJECTED",
                                            "STRATEGY", s.id(),
                                            Map.of("provider", providerUsed == null ? "?" : providerUsed,
                                                    "model", result.path("model_used").asText("?"))))
                                    .thenReturn(s)
                                    .doOnSuccess(saved -> {
                                        if (valid) continuePipeline(saved, effPairs, effTimeframe);
                                    });
                        }));
    }

    /** Async: backtest, then auto-create + start the paper bot. */
    private void continuePipeline(Strategy strategy, List<String> pairs, String timeframe) {
        Instant end = Instant.now();
        Instant start = end.minus(Duration.ofDays(props.pipeline().backtestDays()));
        Backtest bt = new Backtest(UUID.randomUUID(), strategy.tenantId(), strategy.id(),
                timeframe, toJson(pairs), start, end, "RUNNING", null, null, null, Instant.now(), null);

        template.insert(bt)
                .flatMap(saved -> engine.runBacktest(Map.of(
                                "source_code", strategy.sourceCode(),
                                "pairs", pairs,
                                "timeframe", timeframe,
                                "start", start.toString(),
                                "end", end.toString()))
                        .flatMap(result -> backtests.save(new Backtest(saved.id(), saved.tenantId(),
                                saved.strategyId(), saved.timeframe(), saved.pairs(),
                                saved.rangeStart(), saved.rangeEnd(), "DONE",
                                Json.of(result.path("metrics").toString()),
                                Json.of(result.path("trades").toString()),
                                null, saved.createdAt(), Instant.now())))
                        .onErrorResume(e -> {
                            log.warn("Auto-backtest for strategy {} failed: {}", strategy.id(), e.getMessage());
                            return backtests.save(new Backtest(saved.id(), saved.tenantId(),
                                    saved.strategyId(), saved.timeframe(), saved.pairs(),
                                    saved.rangeStart(), saved.rangeEnd(), "FAILED",
                                    null, null, e.getMessage(), saved.createdAt(), Instant.now()));
                        }))
                .then(setStatus(strategy.id(), "BACKTESTED"))
                .then(startPaperBot(strategy, pairs))
                .then(setStatus(strategy.id(), "PAPER_TRADING"))
                .then(audit.record(strategy.tenantId(), strategy.userId(), "PAPER_TRADING_STARTED",
                        "STRATEGY", strategy.id(), Map.of("pairs", pairs.toString())))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        v -> log.info("Pipeline for strategy {} reached PAPER_TRADING", strategy.id()),
                        e -> log.error("Pipeline for strategy {} failed", strategy.id(), e));
    }

    private Mono<Bot> startPaperBot(Strategy strategy, List<String> pairs) {
        Bot bot = new Bot(UUID.randomUUID(), strategy.tenantId(), strategy.userId(), strategy.id(),
                null, strategy.name() + " · paper", "PAPER", "SPOT", toJson(pairs), "USDT",
                props.pipeline().paperStake(), props.pipeline().maxOpenTrades(),
                BigDecimal.ONE, "RUNNING", false, Instant.now(), Instant.now());
        return template.insert(bot);
    }

    private Mono<Void> setStatus(UUID strategyId, String status) {
        return strategies.findById(strategyId)
                .flatMap(s -> strategies.save(new Strategy(s.id(), s.tenantId(), s.userId(), s.name(),
                        s.version(), s.parentId(), s.sourceCode(), s.config(), status, s.origin(),
                        s.aiProviderId(), s.prompt(), s.createdAt())))
                .then();
    }

    /** Fold generation metadata into the stored config so the UI can show it. */
    private JsonNode enrich(JsonNode config, String timeframe, List<String> pairs,
                            String model, String provider, JsonNode errors) {
        var node = config != null && config.isObject()
                ? (com.fasterxml.jackson.databind.node.ObjectNode) config.deepCopy()
                : mapper.createObjectNode();
        // force the timeframe onto CoinDCX's supported set even if the LLM drifted
        node.put("timeframe", normalizeTimeframe(
                node.hasNonNull("timeframe") ? node.get("timeframe").asText() : timeframe));
        node.set("pairs", mapper.valueToTree(pairs));
        if (provider != null) node.put("provider_used", provider);
        if (model != null) node.put("model_used", model);
        if (errors != null && !errors.isMissingNode()) node.set("generation_errors", errors);
        return node;
    }

    /** CoinDCX public candles only serve 1m/15m/1h/1d; coerce anything else. */
    static String normalizeTimeframe(String tf) {
        return switch (tf) {
            case "1m", "15m", "1h", "1d" -> tf;
            case "3m", "5m" -> "1m";
            case "10m", "30m" -> "15m";
            case "2h", "4h", "6h", "8h", "12h" -> "1h";
            default -> tf.endsWith("d") || tf.endsWith("w") || tf.endsWith("M") ? "1d" : "1h";
        };
    }

    private Json toJson(Object value) {
        try {
            return Json.of(mapper.writeValueAsString(value));
        } catch (Exception e) {
            return Json.of("[]");
        }
    }
}
