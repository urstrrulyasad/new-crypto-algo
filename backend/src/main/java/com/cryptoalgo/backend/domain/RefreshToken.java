package com.cryptoalgo.backend.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("refresh_tokens")
public record RefreshToken(@Id UUID id, UUID userId, String tokenHash,
                           Instant expiresAt, boolean revoked, Instant createdAt) {}
