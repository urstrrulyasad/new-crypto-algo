package com.cryptoalgo.backend.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Preset-based AI provider configuration: the admin only supplies the API key;
 * base URL, dialect and the ordered model fallback chain come from
 * {@link com.cryptoalgo.backend.ai.ProviderCatalog}.
 */
@Table("ai_providers")
public record AiProvider(@Id UUID id, UUID tenantId, UUID createdBy, String providerType,
                         String apiKeyEnc, int priority, boolean enabled,
                         Instant createdAt, Instant updatedAt) {}
