package com.cryptoalgo.backend.strategy;

import com.cryptoalgo.backend.common.ApiException;
import com.cryptoalgo.backend.config.AppProperties;
import com.cryptoalgo.backend.domain.Strategy;
import com.cryptoalgo.backend.repo.StrategyRepository;
import com.cryptoalgo.backend.security.CurrentUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Strategy registry. Strategies are exclusively AI-generated through the
 * autonomous pipeline; there is no manual creation or manual approval.
 * Promotion to live happens automatically via the paper-trade gate.
 */
@RestController
@RequestMapping("/api/v1/strategies")
@Validated
public class StrategyController {

    public record GenerateRequest(String name, String goal, String timeframe,
                                  List<String> pairs, String riskProfile) {}
    public record PaperProgress(long closedTrades, long wins, double winRate,
                                BigDecimal totalPnl, int requiredTrades, double requiredWinRate) {}
    public record StrategyView(UUID id, UUID tenantId, String name, int version, String status,
                               String origin, String sourceCode, JsonNode config, String prompt,
                               Instant createdAt, PaperProgress paper) {}

    private final StrategyRepository strategies;
    private final StrategyPipelineService pipeline;
    private final PaperStatsService paperStats;
    private final ObjectMapper mapper;
    private final AppProperties props;

    public StrategyController(StrategyRepository strategies, StrategyPipelineService pipeline,
                              PaperStatsService paperStats, ObjectMapper mapper, AppProperties props) {
        this.strategies = strategies;
        this.pipeline = pipeline;
        this.paperStats = paperStats;
        this.mapper = mapper;
        this.props = props;
    }

    @GetMapping
    public Flux<StrategyView> list() {
        return CurrentUser.get()
                .flatMapMany(p -> strategies.findByTenantIdAndUserIdOrderByCreatedAtDesc(p.tenantId(), p.userId()))
                .concatMap(this::withStats);
    }

    @GetMapping("/{id}")
    public Mono<StrategyView> get(@PathVariable UUID id) {
        return CurrentUser.get().flatMap(p -> strategies.findByIdAndTenantId(id, p.tenantId()))
                .switchIfEmpty(Mono.error(ApiException.notFound("Strategy not found")))
                .flatMap(this::withStats);
    }

    /**
     * Launch the autonomous pipeline: AI generation (provider failover) ->
     * auto backtest -> auto paper bot. All inputs optional.
     */
    @PostMapping("/generate")
    public Mono<StrategyView> generate(@RequestBody(required = false) GenerateRequest req) {
        GenerateRequest r = req == null
                ? new GenerateRequest(null, null, null, null, null) : req;
        return CurrentUser.get()
                .flatMap(p -> pipeline.generate(p, r.name(), r.goal(), r.timeframe(),
                        r.pairs(), r.riskProfile()))
                .flatMap(this::withStats);
    }

    private Mono<StrategyView> withStats(Strategy s) {
        return paperStats.forStrategy(s.id())
                .map(st -> new StrategyView(s.id(), s.tenantId(), s.name(), s.version(), s.status(),
                        s.origin(), s.sourceCode(), readJson(s.config()), s.prompt(), s.createdAt(),
                        new PaperProgress(st.closedTrades(), st.wins(), st.winRate(), st.totalPnl(),
                                props.pipeline().minPaperTrades(),
                                props.pipeline().winRateThreshold())));
    }

    private JsonNode readJson(Json json) {
        try {
            return json == null ? mapper.createObjectNode() : mapper.readTree(json.asString());
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }
}
