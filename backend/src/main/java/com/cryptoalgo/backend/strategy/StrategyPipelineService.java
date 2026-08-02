package com.cryptoalgo.backend.strategy;

import com.cryptoalgo.backend.ai.AiChainService;
import com.cryptoalgo.backend.common.AuditService;
import com.cryptoalgo.backend.config.AppProperties;
import com.cryptoalgo.backend.domain.Backtest;
import com.cryptoalgo.backend.domain.Bot;
import com.cryptoalgo.backend.domain.Strategy;
import com.cryptoalgo.backend.market.MarketNewsService;
import com.cryptoalgo.backend.repo.BacktestRepository;
import com.cryptoalgo.backend.repo.BotRepository;
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

    /** Complementary styles so one coin can host several active strategies. */
    public static final List<String> STRATEGY_STYLES = List.of(
            "mean-reversion",
            "trend-following",
            "breakout-momentum"
    );

    private final AiChainService aiChain;
    private final StrategyEngineClient engine;
    private final CoinDcxFuturesClient futures;
    private final MarketNewsService news;
    private final StrategyRepository strategies;
    private final BacktestRepository backtests;
    private final BotRepository bots;
    private final R2dbcEntityTemplate template;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final AppProperties props;

    public StrategyPipelineService(AiChainService aiChain, StrategyEngineClient engine,
                                   CoinDcxFuturesClient futures, MarketNewsService news,
                                   StrategyRepository strategies,
                                   BacktestRepository backtests, BotRepository bots,
                                   R2dbcEntityTemplate template,
                                   ObjectMapper mapper, AuditService audit, AppProperties props) {
        this.aiChain = aiChain;
        this.engine = engine;
        this.futures = futures;
        this.news = news;
        this.strategies = strategies;
        this.backtests = backtests;
        this.bots = bots;
        this.template = template;
        this.mapper = mapper;
        this.audit = audit;
        this.props = props;
    }

    /** Minimum CoinDCX candle rows (excluding CSV header) required before generate/paper. */
    private static final int MIN_LIVE_CANDLES = 50;

    public Mono<Strategy> generateFutures(UUID tenantId, UUID userId, String instrument) {
        return generateFutures(tenantId, userId, instrument, STRATEGY_STYLES.get(0));
    }

    /** Auto-generate a FUTURES strategy for one INR instrument + style (+ news bias). */
    public Mono<Strategy> generateFutures(UUID tenantId, UUID userId, String instrument, String style) {
        String styleKey = normalizeStyle(style);
        // 5m: enough signal density for paper fills, better AI backtest quality than 1m noise.
        String timeframe = "5m";
        String shortLabel = styleShort(styleKey);
        String name = "FUT " + instrument + " · " + shortLabel;
        List<String> pairs = List.of(instrument);

        return requireLiveMarketData(instrument, timeframe)
                .then(Mono.zip(aiChain.chain(tenantId), news.briefForInstrument(instrument)))
                .flatMap(tuple -> {
                    var chain = tuple.getT1();
                    var brief = tuple.getT2();
                    String goal = buildGoal(instrument, styleKey, brief);
                    return futures.recentCsv(instrument, CoinDcxFuturesClient.toFuturesResolution(timeframe), 100)
                            .flatMap(csv -> {
                                if (!hasEnoughCandles(csv)) {
                                    return Mono.error(new IllegalStateException(
                                            "CoinDCX returned insufficient candles for " + instrument
                                                    + "; refusing to generate without real market data"));
                                }
                                Map<String, Object> engineReq = new java.util.HashMap<>();
                                engineReq.put("providers", chain.chain());
                                engineReq.put("goal", goal);
                                engineReq.put("timeframe", timeframe);
                                engineReq.put("pairs", pairs);
                                engineReq.put("risk_profile", riskForStyle(styleKey));
                                engineReq.put("market_type", "FUTURES");
                                engineReq.put("market_data", csv);
                                return engine.generateStrategy(engineReq);
                            })
                            .flatMap(result -> {
                                boolean valid = result.path("valid").asBoolean(false);
                                String providerUsed = result.path("provider_used").asText(null);
                                UUID providerId = providerUsed == null ? null
                                        : chain.providerIds().get(providerUsed);
                                JsonNode config = enrich(result.path("config"), timeframe, pairs,
                                        result.path("model_used").asText(null), providerUsed,
                                        valid ? null : result.path("errors"), styleKey,
                                        brief.sentiment());
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
                                                        "style", styleKey,
                                                        "sentiment", brief.sentiment(),
                                                        "provider", providerUsed == null ? "?" : providerUsed)))
                                        .thenReturn(s)
                                        .doOnSuccess(saved -> {
                                            if (valid) continuePipeline(saved);
                                        });
                            });
                });
    }

    private static String buildGoal(String instrument, String style, MarketNewsService.NewsBrief brief) {
        String styleGuide = switch (style) {
            case "trend-following" ->
                    "Primary style: TREND-FOLLOWING. Use EMA/SMA structure, pullback entries with trend, "
                            + "avoid counter-trend fades. Exits on trend break / trailing ROI.";
            case "breakout-momentum" ->
                    "Primary style: BREAKOUT/MOMENTUM. Enter on range/volatility expansion with volume "
                            + "confirmation; tight invalidation if breakout fails.";
            default ->
                    "Primary style: MEAN-REVERSION. Prefer RSI + Bollinger (or similar) fades back to "
                            + "mid/mean; fade extremes, do not chase trends.";
        };
        return "INR-margined CoinDCX futures strategy for " + instrument + ".\n"
                + styleGuide + "\n"
                + "Long and short with strict risk. Target profit_factor > 1.2, win_rate > 55%, "
                + "positive total profit, max drawdown under 35%. Avoid overtrading. Timeframe 5m.\n\n"
                + brief.promptBlock();
    }

    static String normalizeStyle(String style) {
        if (style == null || style.isBlank()) return STRATEGY_STYLES.get(0);
        String s = style.trim().toLowerCase(java.util.Locale.ROOT);
        for (String known : STRATEGY_STYLES) {
            if (known.equals(s) || s.contains(known.split("-")[0])) return known;
        }
        return STRATEGY_STYLES.get(0);
    }

    static String styleShort(String style) {
        return switch (normalizeStyle(style)) {
            case "trend-following" -> "Trend";
            case "breakout-momentum" -> "Breakout";
            default -> "MR";
        };
    }

    private static String riskForStyle(String style) {
        return switch (normalizeStyle(style)) {
            case "breakout-momentum" -> "aggressive";
            case "trend-following" -> "balanced";
            default -> "conservative";
        };
    }

    /** Public so the scheduler can resume GENERATED strategies after a restart mid-backtest. */
    public void continuePipeline(Strategy strategy) {
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
                    // Paper fills use live CoinDCX prices — do not start paper without real data.
                    return setStatus(strategy.id(), "BACKTESTED")
                            .then(requireLiveMarketData(instrument, timeframe)
                                    .then(startPaperBot(strategy, pairs))
                                    .then(setStatus(strategy.id(), "PAPER_TRADING"))
                                    .then(audit.record(strategy.tenantId(), strategy.userId(),
                                            "PAPER_TRADING_STARTED", "STRATEGY", strategy.id(),
                                            Map.of("instrument", instrument)))
                                    .onErrorResume(e -> {
                                        log.warn("Paper start blocked for {} — CoinDCX data unavailable: {}",
                                                instrument, e.getMessage());
                                        return audit.record(strategy.tenantId(), strategy.userId(),
                                                        "PAPER_START_BLOCKED_NO_MARKET_DATA", "STRATEGY",
                                                        strategy.id(),
                                                        Map.of("instrument", instrument,
                                                                "reason", String.valueOf(e.getMessage())))
                                                .then();
                                    }));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        v -> log.info("Pipeline for {} finished", strategy.id()),
                        e -> log.error("Pipeline for {} failed", strategy.id(), e));
    }

    /**
     * Ensures CoinDCX public futures API returns enough live candles for the pair.
     * Generation and paper start must not proceed on empty/error responses.
     */
    public Mono<Void> requireLiveMarketData(String instrument, String timeframe) {
        String resolution = CoinDcxFuturesClient.toFuturesResolution(timeframe);
        return futures.recentCsv(instrument, resolution, MIN_LIVE_CANDLES + 10)
                .flatMap(csv -> {
                    if (!hasEnoughCandles(csv)) {
                        return Mono.error(new IllegalStateException(
                                "CoinDCX live candles unavailable/insufficient for " + instrument));
                    }
                    return futures.lastPrice(instrument)
                            .flatMap(px -> {
                                if (px == null || px.signum() <= 0) {
                                    return Mono.error(new IllegalStateException(
                                            "CoinDCX last price missing for " + instrument));
                                }
                                return Mono.empty();
                            });
                })
                .onErrorMap(e -> e instanceof IllegalStateException ? e
                        : new IllegalStateException("CoinDCX API not usable for " + instrument
                        + ": " + e.getMessage(), e))
                .then();
    }

    static boolean hasEnoughCandles(String csv) {
        if (csv == null || csv.isBlank()) return false;
        long rows = csv.lines().skip(1).filter(l -> !l.isBlank()).count();
        return rows >= MIN_LIVE_CANDLES;
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
        return bots.findByStrategyId(strategy.id())
                .filter(b -> "PAPER".equals(b.mode()) && "RUNNING".equals(b.status()))
                .next()
                .switchIfEmpty(Mono.defer(() -> {
                    Bot bot = new Bot(UUID.randomUUID(), strategy.tenantId(), strategy.userId(), strategy.id(),
                            null, strategy.name() + " · paper", "PAPER", "FUTURES", toJson(pairs), "INR",
                            props.pipeline().paperStake(), props.pipeline().maxOpenTrades(),
                            BigDecimal.valueOf(props.pipeline().futuresLeverage()), "RUNNING", false,
                            "INR", Instant.now(), Instant.now());
                    return template.insert(bot);
                }));
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
                            String model, String provider, JsonNode errors,
                            String style, String sentiment) {
        var node = config != null && config.isObject()
                ? (com.fasterxml.jackson.databind.node.ObjectNode) config.deepCopy()
                : mapper.createObjectNode();
        node.put("timeframe", CoinDcxFuturesClient.normalizeFuturesTimeframe(
                node.hasNonNull("timeframe") ? node.get("timeframe").asText() : timeframe));
        node.set("pairs", mapper.valueToTree(pairs));
        node.put("market_type", "FUTURES");
        node.put("margin_currency", "INR");
        if (style != null) node.put("strategy_style", style);
        if (sentiment != null) node.put("news_sentiment", sentiment);
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
