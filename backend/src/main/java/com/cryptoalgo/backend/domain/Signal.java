package com.cryptoalgo.backend.domain;

import io.r2dbc.postgresql.codec.Json;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Table("signals")
public record Signal(@Id UUID id, UUID tenantId, UUID strategyId, String idempotencyKey,
                     String pair, String timeframe, String action, BigDecimal price,
                     Instant candleTs, Json payload, Instant receivedAt) {}
