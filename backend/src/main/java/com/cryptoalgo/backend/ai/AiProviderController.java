package com.cryptoalgo.backend.ai;

import com.cryptoalgo.backend.common.ApiException;
import com.cryptoalgo.backend.common.AuditService;
import com.cryptoalgo.backend.common.SecretCrypto;
import com.cryptoalgo.backend.domain.AiProvider;
import com.cryptoalgo.backend.repo.AiProviderRepository;
import com.cryptoalgo.backend.security.CurrentUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Admin-level LLM provider configuration: provider type, base URL, model,
 * API key (encrypted, write-only) and request template. Traders can only
 * see non-secret metadata to pick a provider for strategy generation.
 */
@RestController
@RequestMapping("/api/v1/ai/providers")
@Validated
public class AiProviderController {

    public record UpsertRequest(@NotBlank String providerType, @NotBlank String name,
                                @NotBlank String baseUrl, @NotBlank String model,
                                String apiKey, Map<String, Object> requestTemplate, Boolean enabled) {}
    public record ProviderView(UUID id, String providerType, String name, String baseUrl,
                               String model, boolean enabled, Instant createdAt) {
        static ProviderView of(AiProvider p) {
            return new ProviderView(p.id(), p.providerType(), p.name(), p.baseUrl(),
                    p.model(), p.enabled(), p.createdAt());
        }
    }

    private static final Set<String> TYPES = Set.of("ANTHROPIC", "GEMINI", "GROK", "OPENAI_COMPATIBLE");

    private final AiProviderRepository providers;
    private final SecretCrypto crypto;
    private final R2dbcEntityTemplate template;
    private final ObjectMapper mapper;
    private final AuditService audit;

    public AiProviderController(AiProviderRepository providers, SecretCrypto crypto,
                                R2dbcEntityTemplate template, ObjectMapper mapper, AuditService audit) {
        this.providers = providers;
        this.crypto = crypto;
        this.template = template;
        this.mapper = mapper;
        this.audit = audit;
    }

    /** Traders see enabled providers (no secrets) to choose one for generation. */
    @GetMapping
    public Flux<ProviderView> list() {
        return CurrentUser.get()
                .flatMapMany(p -> providers.findByTenantId(p.tenantId()))
                .map(ProviderView::of);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public Mono<ProviderView> create(@RequestBody UpsertRequest req) {
        if (!TYPES.contains(req.providerType()))
            return Mono.error(ApiException.badRequest("providerType must be one of " + TYPES));
        if (req.apiKey() == null || req.apiKey().isBlank())
            return Mono.error(ApiException.badRequest("apiKey is required"));
        return CurrentUser.get().flatMap(actor -> {
            AiProvider provider = new AiProvider(UUID.randomUUID(), actor.tenantId(), actor.userId(),
                    req.providerType(), req.name(), req.baseUrl(), req.model(),
                    crypto.encrypt(req.apiKey().trim()), toJson(req.requestTemplate()),
                    req.enabled() == null || req.enabled(), Instant.now(), Instant.now());
            return template.insert(provider)
                    .then(audit.record(actor.tenantId(), actor.userId(), "AI_PROVIDER_CREATED",
                            "AI_PROVIDER", provider.id(), Map.of("name", req.name(), "type", req.providerType())))
                    .thenReturn(ProviderView.of(provider));
        });
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public Mono<ProviderView> update(@PathVariable UUID id, @RequestBody UpsertRequest req) {
        return CurrentUser.get().flatMap(actor -> providers.findByIdAndTenantId(id, actor.tenantId())
                .switchIfEmpty(Mono.error(ApiException.notFound("Provider not found")))
                .flatMap(existing -> {
                    String apiKeyEnc = (req.apiKey() == null || req.apiKey().isBlank())
                            ? existing.apiKeyEnc() : crypto.encrypt(req.apiKey().trim());
                    AiProvider updated = new AiProvider(existing.id(), existing.tenantId(),
                            existing.createdBy(), req.providerType(), req.name(), req.baseUrl(),
                            req.model(), apiKeyEnc,
                            req.requestTemplate() == null ? existing.requestTemplate() : toJson(req.requestTemplate()),
                            req.enabled() == null ? existing.enabled() : req.enabled(),
                            existing.createdAt(), Instant.now());
                    return providers.save(updated).map(ProviderView::of);
                }));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public Mono<Void> delete(@PathVariable UUID id) {
        return CurrentUser.get().flatMap(actor -> providers.findByIdAndTenantId(id, actor.tenantId())
                .switchIfEmpty(Mono.error(ApiException.notFound("Provider not found")))
                .flatMap(p -> providers.deleteById(p.id())));
    }

    private Json toJson(Map<String, Object> value) {
        try {
            return Json.of(mapper.writeValueAsString(value == null ? Map.of() : value));
        } catch (Exception e) {
            throw ApiException.badRequest("Invalid requestTemplate JSON");
        }
    }
}
