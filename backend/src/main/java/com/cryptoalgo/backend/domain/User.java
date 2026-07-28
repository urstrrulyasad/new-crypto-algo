package com.cryptoalgo.backend.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("users")
public record User(@Id UUID id, UUID tenantId, String email, String passwordHash,
                   String displayName, String role, String status,
                   Instant createdAt, Instant updatedAt) {}
