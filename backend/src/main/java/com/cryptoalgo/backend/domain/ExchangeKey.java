package com.cryptoalgo.backend.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("exchange_keys")
public record ExchangeKey(@Id UUID id, UUID tenantId, UUID userId, String exchange, String label,
                          String apiKeyEnc, String apiSecretEnc, String keyLast4, String status,
                          Instant createdAt) {}
