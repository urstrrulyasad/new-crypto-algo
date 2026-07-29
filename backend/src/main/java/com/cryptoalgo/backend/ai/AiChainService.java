package com.cryptoalgo.backend.ai;

import com.cryptoalgo.backend.common.ApiException;
import com.cryptoalgo.backend.common.SecretCrypto;
import com.cryptoalgo.backend.domain.AiProvider;
import com.cryptoalgo.backend.repo.AiProviderRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the ordered LLM failover chain for a tenant: enabled providers by
 * priority, each expanded with its preset dialect, base URL and model list.
 * The strategy engine walks this chain on rate limits.
 */
@Service
public class AiChainService {

    public record ChainResult(List<Map<String, Object>> chain, Map<String, UUID> providerIds) {}

    private final AiProviderRepository providers;
    private final SecretCrypto crypto;

    public AiChainService(AiProviderRepository providers, SecretCrypto crypto) {
        this.providers = providers;
        this.crypto = crypto;
    }

    public Mono<ChainResult> chain(UUID tenantId) {
        return providers.findByTenantIdAndEnabledOrderByPriorityAsc(tenantId, true)
                .collectList()
                .flatMap(list -> {
                    List<Map<String, Object>> chain = new java.util.ArrayList<>();
                    Map<String, UUID> ids = new LinkedHashMap<>();
                    for (AiProvider p : list) {
                        var preset = ProviderCatalog.byType(p.providerType()).orElse(null);
                        if (preset == null) continue;
                        chain.add(Map.of(
                                "provider_type", preset.type(),
                                "dialect", preset.dialect(),
                                "base_url", preset.baseUrl(),
                                "api_key", crypto.decrypt(p.apiKeyEnc()),
                                "models", preset.models()));
                        ids.put(preset.type(), p.id());
                    }
                    if (chain.isEmpty())
                        return Mono.error(ApiException.badRequest(
                                "No AI provider configured. Ask your admin to add an API key "
                                        + "for Gemini, Groq, OpenRouter, Mistral or Cerebras."));
                    return Mono.just(new ChainResult(chain, ids));
                });
    }
}
