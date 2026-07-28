package com.cryptoalgo.backend.strategy;

import com.cryptoalgo.backend.common.ApiException;
import com.cryptoalgo.backend.domain.Backtest;
import com.cryptoalgo.backend.repo.BacktestRepository;
import com.cryptoalgo.backend.repo.StrategyRepository;
import com.cryptoalgo.backend.security.CurrentUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.annotation.Validated;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Backtest orchestration: persists runs, delegates execution to the freqtrade sidecar. */
@RestController
@RequestMapping("/api/v1/backtests")
@Validated
public class BacktestController {

    public record RunRequest(@NotNull UUID strategyId, String timeframe, List<String> pairs,
                             @NotNull Instant from, @NotNull Instant to) {}

    private static final Logger log = LoggerFactory.getLogger(BacktestController.class);

    private final BacktestRepository backtests;
    private final StrategyRepository strategies;
    private final StrategyEngineClient engine;
    private final R2dbcEntityTemplate template;
    private final ObjectMapper mapper;

    public BacktestController(BacktestRepository backtests, StrategyRepository strategies,
                              StrategyEngineClient engine, R2dbcEntityTemplate template,
                              ObjectMapper mapper) {
        this.backtests = backtests;
        this.strategies = strategies;
        this.engine = engine;
        this.template = template;
        this.mapper = mapper;
    }

    @PostMapping
    public Mono<Backtest> run(@RequestBody RunRequest req) {
        return CurrentUser.get().flatMap(p ->
                strategies.findByIdAndTenantId(req.strategyId(), p.tenantId())
                        .switchIfEmpty(Mono.error(ApiException.notFound("Strategy not found")))
                        .flatMap(strategy -> {
                            String timeframe = req.timeframe() == null ? "1h" : req.timeframe();
                            List<String> pairs = req.pairs() == null || req.pairs().isEmpty()
                                    ? List.of("B-BTC_USDT") : req.pairs();
                            Backtest bt = new Backtest(UUID.randomUUID(), p.tenantId(), strategy.id(),
                                    timeframe, toJson(pairs), req.from(), req.to(),
                                    "RUNNING", null, null, null, Instant.now(), null);
                            return template.insert(bt).doOnSuccess(saved ->
                                    execute(saved, strategy.sourceCode(), pairs, timeframe));
                        }));
    }

    /** Fire-and-track: runs the backtest via the sidecar and persists the outcome. */
    private void execute(Backtest bt, String sourceCode, List<String> pairs, String timeframe) {
        Map<String, Object> req = Map.of(
                "source_code", sourceCode,
                "pairs", pairs,
                "timeframe", timeframe,
                "start", bt.rangeStart().toString(),
                "end", bt.rangeEnd().toString());
        engine.runBacktest(req)
                .flatMap(result -> backtests.save(new Backtest(bt.id(), bt.tenantId(), bt.strategyId(),
                        bt.timeframe(), bt.pairs(), bt.rangeStart(), bt.rangeEnd(), "DONE",
                        Json.of(result.path("metrics").toString()),
                        Json.of(result.path("trades").toString()),
                        null, bt.createdAt(), Instant.now())))
                .onErrorResume(e -> {
                    log.error("Backtest {} failed", bt.id(), e);
                    return backtests.save(new Backtest(bt.id(), bt.tenantId(), bt.strategyId(),
                            bt.timeframe(), bt.pairs(), bt.rangeStart(), bt.rangeEnd(), "FAILED",
                            null, null, e.getMessage(), bt.createdAt(), Instant.now()));
                })
                .subscribe(saved -> log.info("Backtest {} finished with status {}", saved.id(), saved.status()),
                        e -> log.error("Backtest persistence failed", e));
    }

    @GetMapping("/{id}")
    public Mono<Backtest> get(@PathVariable UUID id) {
        return CurrentUser.get().flatMap(p -> backtests.findByIdAndTenantId(id, p.tenantId()))
                .switchIfEmpty(Mono.error(ApiException.notFound("Backtest not found")));
    }

    @GetMapping
    public Flux<Backtest> list(@RequestParam UUID strategyId) {
        return CurrentUser.get().flatMapMany(p ->
                backtests.findByTenantIdAndStrategyIdOrderByCreatedAtDesc(p.tenantId(), strategyId));
    }

    private Json toJson(Object value) {
        try {
            return Json.of(mapper.writeValueAsString(value));
        } catch (Exception e) {
            throw ApiException.badRequest("Serialization failed");
        }
    }
}
