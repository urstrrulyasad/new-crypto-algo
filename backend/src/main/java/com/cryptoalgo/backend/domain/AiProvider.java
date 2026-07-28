package com.cryptoalgo.backend.domain;

import io.r2dbc.postgresql.codec.Json;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("ai_providers")
public record AiProvider(@Id UUID id, UUID tenantId, UUID createdBy, String providerType,
                         String name, String baseUrl, String model, String apiKeyEnc,
                         Json requestTemplate, boolean enabled,
                         Instant createdAt, Instant updatedAt) {}
