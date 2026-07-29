package com.cryptoalgo.backend.ai;

import com.cryptoalgo.backend.common.ApiException;
import com.cryptoalgo.backend.common.AuditService;
import com.cryptoalgo.backend.common.SecretCrypto;
import com.cryptoalgo.backend.domain.AiProvider;
import com.cryptoalgo.backend.repo.AiProviderRepository;
import com.cryptoalgo.backend.security.CurrentUser;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin-level AI provider configuration. Preset-based: the admin picks a
 * provider from the built-in catalog and pastes an API key - nothing else.
 * Keys are stored AES-256-GCM encrypted and never returned.
 */
@RestController
@RequestMapping("/api/v1/ai/providers")
@Validated
public class AiProviderController {

    public record UpsertRequest(@NotBlank String providerType, String apiKey,
                                Integer priority, Boolean enabled) {}
    public record ProviderView(UUID id, String providerType, String displayName,
                               List<String> models, int priority, boolean enabled, Instant createdAt) {
        static ProviderView of(AiProvider p) {
            var preset = ProviderCatalog.byType(p.providerType()).orElse(null);
            return new ProviderView(p.id(), p.providerType(),
                    preset == null ? p.providerType() : preset.displayName(),
                    preset == null ? List.of() : preset.models(),
                    p.priority(), p.enabled(), p.createdAt());
        }
    }
    public record CatalogEntry(String type, String displayName, List<String> models) {}

    private final AiProviderRepository providers;
    private final SecretCrypto crypto;
    private final R2dbcEntityTemplate template;
    private final AuditService audit;

    public AiProviderController(AiProviderRepository providers, SecretCrypto crypto,
                                R2dbcEntityTemplate template, AuditService audit) {
        this.providers = providers;
        this.crypto = crypto;
        this.template = template;
        this.audit = audit;
    }

    /** Built-in provider presets so the UI can render the dropdown. */
    @GetMapping("/catalog")
    public List<CatalogEntry> catalog() {
        return ProviderCatalog.PRESETS.stream()
                .map(p -> new CatalogEntry(p.type(), p.displayName(), p.models()))
                .toList();
    }

    @GetMapping
    public Flux<ProviderView> list() {
        return CurrentUser.get()
                .flatMapMany(p -> providers.findByTenantId(p.tenantId()))
                .map(ProviderView::of);
    }

    /** Create or update the key for a provider type (one row per type per tenant). */
    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')")
    public Mono<ProviderView> upsert(@RequestBody UpsertRequest req) {
        if (ProviderCatalog.byType(req.providerType()).isEmpty())
            return Mono.error(ApiException.badRequest("Unknown provider type; use one of "
                    + ProviderCatalog.PRESETS.stream().map(ProviderCatalog.Preset::type).toList()));
        if (req.apiKey() == null || req.apiKey().isBlank())
            return Mono.error(ApiException.badRequest("apiKey is required"));
        return CurrentUser.get().flatMap(actor ->
                providers.findByTenantIdAndProviderType(actor.tenantId(), req.providerType())
                        .flatMap(existing -> providers.save(new AiProvider(existing.id(),
                                existing.tenantId(), existing.createdBy(), existing.providerType(),
                                crypto.encrypt(req.apiKey().trim()),
                                req.priority() == null ? existing.priority() : req.priority(),
                                req.enabled() == null || req.enabled(),
                                existing.createdAt(), Instant.now())))
                        .switchIfEmpty(Mono.defer(() -> {
                            AiProvider provider = new AiProvider(UUID.randomUUID(), actor.tenantId(),
                                    actor.userId(), req.providerType(),
                                    crypto.encrypt(req.apiKey().trim()),
                                    req.priority() == null ? 100 : req.priority(),
                                    req.enabled() == null || req.enabled(),
                                    Instant.now(), Instant.now());
                            return template.insert(provider);
                        }))
                        .flatMap(saved -> audit.record(actor.tenantId(), actor.userId(),
                                        "AI_PROVIDER_CONFIGURED", "AI_PROVIDER", saved.id(),
                                        Map.of("type", saved.providerType()))
                                .thenReturn(ProviderView.of(saved))));
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
                            existing.createdBy(), existing.providerType(), apiKeyEnc,
                            req.priority() == null ? existing.priority() : req.priority(),
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
}
