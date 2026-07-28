package com.cryptoalgo.backend.strategy;

import com.cryptoalgo.backend.common.ApiException;
import com.cryptoalgo.backend.common.AuditService;
import com.cryptoalgo.backend.common.SecretCrypto;
import com.cryptoalgo.backend.domain.Strategy;
import com.cryptoalgo.backend.repo.AiProviderRepository;
import com.cryptoalgo.backend.repo.StrategyRepository;
import com.cryptoalgo.backend.security.CurrentUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Strategy registry + AI generation + validation. */
@RestController
@RequestMapping("/api/v1/strategies")
@Validated
public class StrategyController {

    public record GenerateRequest(@NotBlank String name, UUID aiProviderId, @NotBlank String goal,
                                  String timeframe, java.util.List<String> pairs, String riskProfile) {}
    public record SaveRequest(@NotBlank String name, @NotBlank String sourceCode,
                              Map<String, Object> config) {}
    public record StrategyView(UUID id, String name, int version, String status, String origin,
                               String sourceCode, JsonNode config, String prompt, Instant createdAt) {}

    private final StrategyRepository strategies;
    private final AiProviderRepository providers;
    private final StrategyEngineClient engine;
    private final SecretCrypto crypto;
    private final R2dbcEntityTemplate template;
    private final ObjectMapper mapper;
    private final AuditService audit;

    public StrategyController(StrategyRepository strategies, AiProviderRepository providers,
                              StrategyEngineClient engine, SecretCrypto crypto,
                              R2dbcEntityTemplate template, ObjectMapper mapper, AuditService audit) {
        this.strategies = strategies;
        this.providers = providers;
        this.engine = engine;
        this.crypto = crypto;
        this.template = template;
        this.mapper = mapper;
        this.audit = audit;
    }

    @GetMapping
    public Flux<StrategyView> list() {
        return CurrentUser.get()
                .flatMapMany(p -> strategies.findByTenantIdAndUserIdOrderByCreatedAtDesc(p.tenantId(), p.userId()))
                .map(this::view);
    }

    @GetMapping("/{id}")
    public Mono<StrategyView> get(@PathVariable UUID id) {
        return CurrentUser.get().flatMap(p -> strategies.findByIdAndTenantId(id, p.tenantId()))
                .switchIfEmpty(Mono.error(ApiException.notFound("Strategy not found")))
                .map(this::view);
    }

    /** AI-generate a strategy using an admin-configured provider, then validate it. */
    @PostMapping("/generate")
    public Mono<StrategyView> generate(@RequestBody GenerateRequest req) {
        return CurrentUser.get().flatMap(p ->
                providers.findByIdAndTenantId(req.aiProviderId(), p.tenantId())
                        .filter(com.cryptoalgo.backend.domain.AiProvider::enabled)
                        .switchIfEmpty(Mono.error(ApiException.badRequest(
                                "AI provider not found or disabled; ask your admin to configure one")))
                        .flatMap(provider -> {
                            Map<String, Object> engineReq = new HashMap<>();
                            engineReq.put("provider_type", provider.providerType());
                            engineReq.put("base_url", provider.baseUrl());
                            engineReq.put("model", provider.model());
                            engineReq.put("api_key", crypto.decrypt(provider.apiKeyEnc()));
                            engineReq.put("request_template", readJson(provider.requestTemplate()));
                            engineReq.put("goal", req.goal());
                            engineReq.put("timeframe", req.timeframe() == null ? "1h" : req.timeframe());
                            engineReq.put("pairs", req.pairs() == null ? java.util.List.of("B-BTC_USDT") : req.pairs());
                            engineReq.put("risk_profile", req.riskProfile() == null ? "balanced" : req.riskProfile());
                            return engine.generateStrategy(engineReq)
                                    .flatMap(result -> {
                                        if (!result.path("valid").asBoolean(false)) {
                                            return Mono.error(ApiException.upstream("Generated strategy failed validation: "
                                                    + result.path("errors").toString()));
                                        }
                                        Strategy s = new Strategy(UUID.randomUUID(), p.tenantId(), p.userId(),
                                                req.name(), 1, null, result.get("source_code").asText(),
                                                toJson(result.path("config")), "VALIDATED", "AI_GENERATED",
                                                provider.id(), req.goal(), Instant.now());
                                        return template.insert(s)
                                                .then(audit.record(p.tenantId(), p.userId(), "STRATEGY_GENERATED",
                                                        "STRATEGY", s.id(), Map.of("provider", provider.name())))
                                                .thenReturn(view(s));
                                    });
                        }));
    }

    /** Save a manual strategy (validated through the engine before persisting). */
    @PostMapping
    public Mono<StrategyView> save(@RequestBody SaveRequest req) {
        return CurrentUser.get().flatMap(p ->
                engine.validateStrategy(Map.of("source_code", req.sourceCode()))
                        .flatMap(result -> {
                            boolean valid = result.path("valid").asBoolean(false);
                            Strategy s = new Strategy(UUID.randomUUID(), p.tenantId(), p.userId(),
                                    req.name(), 1, null, req.sourceCode(),
                                    toJson(mapper.valueToTree(req.config() == null ? Map.of() : req.config())),
                                    valid ? "VALIDATED" : "DRAFT", "MANUAL", null, null, Instant.now());
                            return template.insert(s).thenReturn(view(s));
                        }));
    }

    @PostMapping("/{id}/approve")
    public Mono<StrategyView> approve(@PathVariable UUID id) {
        return CurrentUser.get().flatMap(p -> strategies.findByIdAndTenantId(id, p.tenantId())
                .switchIfEmpty(Mono.error(ApiException.notFound("Strategy not found")))
                .flatMap(s -> {
                    if (!"VALIDATED".equals(s.status()))
                        return Mono.error(ApiException.badRequest("Only VALIDATED strategies can be approved"));
                    Strategy approved = new Strategy(s.id(), s.tenantId(), s.userId(), s.name(), s.version(),
                            s.parentId(), s.sourceCode(), s.config(), "APPROVED", s.origin(),
                            s.aiProviderId(), s.prompt(), s.createdAt());
                    return strategies.save(approved)
                            .then(audit.record(p.tenantId(), p.userId(), "STRATEGY_APPROVED",
                                    "STRATEGY", s.id(), Map.of()))
                            .thenReturn(view(approved));
                }));
    }

    private StrategyView view(Strategy s) {
        return new StrategyView(s.id(), s.name(), s.version(), s.status(), s.origin(),
                s.sourceCode(), readJson(s.config()), s.prompt(), s.createdAt());
    }

    private JsonNode readJson(Json json) {
        try {
            return json == null ? mapper.createObjectNode() : mapper.readTree(json.asString());
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private Json toJson(JsonNode node) {
        return Json.of(node == null || node.isMissingNode() ? "{}" : node.toString());
    }
}
