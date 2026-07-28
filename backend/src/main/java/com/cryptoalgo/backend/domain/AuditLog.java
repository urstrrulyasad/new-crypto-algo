package com.cryptoalgo.backend.domain;

import io.r2dbc.postgresql.codec.Json;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("audit_log")
public record AuditLog(@Id UUID id, UUID tenantId, UUID userId, String action,
                       String entityType, UUID entityId, Json details, Instant createdAt) {}
