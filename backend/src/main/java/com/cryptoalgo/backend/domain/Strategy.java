package com.cryptoalgo.backend.domain;

import io.r2dbc.postgresql.codec.Json;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("strategies")
public record Strategy(@Id UUID id, UUID tenantId, UUID userId, String name, int version,
                       UUID parentId, String sourceCode, Json config, String status,
                       String origin, UUID aiProviderId, String prompt, Instant createdAt) {}
