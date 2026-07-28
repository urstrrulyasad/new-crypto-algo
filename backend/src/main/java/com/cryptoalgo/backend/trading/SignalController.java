package com.cryptoalgo.backend.trading;

import com.cryptoalgo.backend.common.ApiException;
import com.cryptoalgo.backend.config.AppProperties;
import com.cryptoalgo.backend.domain.Signal;
import com.cryptoalgo.backend.repo.SignalRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Internal endpoint receiving entry/exit signals from the Python strategy engine.
 * Authenticated by shared internal token; idempotent per signal key.
 */
@RestController
@RequestMapping("/api/v1/internal/signals")
@Validated
public class SignalController {

    public record SignalRequest(@NotNull UUID tenantId, @NotNull UUID strategyId,
                                @NotBlank String idempotencyKey, @NotBlank String pair,
                                @NotBlank String timeframe, @NotBlank String action,
                                @NotNull BigDecimal price, @NotNull Instant candleTs,
                                Map<String, Object> payload) {}

    private final AppProperties props;
    private final SignalRepository signals;
    private final R2dbcEntityTemplate template;
    private final ExecutionService execution;
    private final ObjectMapper mapper;

    public SignalController(AppProperties props, SignalRepository signals,
                            R2dbcEntityTemplate template, ExecutionService execution,
                            ObjectMapper mapper) {
        this.props = props;
        this.signals = signals;
        this.template = template;
        this.execution = execution;
        this.mapper = mapper;
    }

    @PostMapping
    public Mono<Map<String, Object>> receive(@RequestHeader("X-Internal-Token") String token,
                                             @RequestBody SignalRequest req) {
        if (!props.internal().token().equals(token))
            return Mono.error(ApiException.unauthorized("Bad internal token"));
        return signals.existsByIdempotencyKey(req.idempotencyKey())
                .flatMap(exists -> {
                    if (exists) return Mono.just(Map.of("status", (Object) "DUPLICATE"));
                    Signal signal = new Signal(UUID.randomUUID(), req.tenantId(), req.strategyId(),
                            req.idempotencyKey(), req.pair(), req.timeframe(), req.action(),
                            req.price(), req.candleTs(), toJson(req.payload()), Instant.now());
                    return template.insert(signal)
                            .flatMap(saved -> execution.process(saved)
                                    .map(executed -> Map.of("status", (Object) "ACCEPTED",
                                            "botsTriggered", executed)));
                });
    }

    private Json toJson(Map<String, Object> payload) {
        try {
            return Json.of(mapper.writeValueAsString(payload == null ? Map.of() : payload));
        } catch (Exception e) {
            return Json.of("{}");
        }
    }
}
