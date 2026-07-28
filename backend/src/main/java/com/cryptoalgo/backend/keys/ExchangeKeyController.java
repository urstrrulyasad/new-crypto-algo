package com.cryptoalgo.backend.keys;

import com.cryptoalgo.backend.common.ApiException;
import com.cryptoalgo.backend.common.AuditService;
import com.cryptoalgo.backend.common.SecretCrypto;
import com.cryptoalgo.backend.domain.ExchangeKey;
import com.cryptoalgo.backend.repo.ExchangeKeyRepository;
import com.cryptoalgo.backend.security.CurrentUser;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * User-level CoinDCX API key management. Secrets are write-only:
 * stored AES-256-GCM encrypted, never returned; only the last 4 chars are shown.
 */
@RestController
@RequestMapping("/api/v1/keys")
@Validated
public class ExchangeKeyController {

    public record CreateKeyRequest(@NotBlank String label, @NotBlank String apiKey,
                                   @NotBlank String apiSecret) {}
    public record KeyView(UUID id, String exchange, String label, String keyLast4,
                          String status, Instant createdAt) {
        static KeyView of(ExchangeKey k) {
            return new KeyView(k.id(), k.exchange(), k.label(), k.keyLast4(), k.status(), k.createdAt());
        }
    }

    private final ExchangeKeyRepository keys;
    private final SecretCrypto crypto;
    private final R2dbcEntityTemplate template;
    private final AuditService audit;

    public ExchangeKeyController(ExchangeKeyRepository keys, SecretCrypto crypto,
                                 R2dbcEntityTemplate template, AuditService audit) {
        this.keys = keys;
        this.crypto = crypto;
        this.template = template;
        this.audit = audit;
    }

    @GetMapping
    public Flux<KeyView> list() {
        return CurrentUser.get()
                .flatMapMany(p -> keys.findByTenantIdAndUserId(p.tenantId(), p.userId()))
                .map(KeyView::of);
    }

    @PostMapping
    public Mono<KeyView> create(@RequestBody CreateKeyRequest req) {
        return CurrentUser.get().flatMap(p -> {
            String apiKey = req.apiKey().trim();
            ExchangeKey key = new ExchangeKey(UUID.randomUUID(), p.tenantId(), p.userId(), "COINDCX",
                    req.label(), crypto.encrypt(apiKey), crypto.encrypt(req.apiSecret().trim()),
                    apiKey.substring(Math.max(0, apiKey.length() - 4)), "ACTIVE", Instant.now());
            return template.insert(key)
                    .then(audit.record(p.tenantId(), p.userId(), "EXCHANGE_KEY_ADDED",
                            "EXCHANGE_KEY", key.id(), Map.of("label", req.label())))
                    .thenReturn(KeyView.of(key));
        });
    }

    @DeleteMapping("/{id}")
    public Mono<Void> delete(@PathVariable UUID id) {
        return CurrentUser.get().flatMap(p -> keys.findByIdAndTenantId(id, p.tenantId())
                .filter(k -> k.userId().equals(p.userId()) || p.isTenantAdmin())
                .switchIfEmpty(Mono.error(ApiException.notFound("Key not found")))
                .flatMap(k -> keys.deleteById(k.id())
                        .then(audit.record(p.tenantId(), p.userId(), "EXCHANGE_KEY_DELETED",
                                "EXCHANGE_KEY", k.id(), Map.of("label", k.label())))));
    }
}
