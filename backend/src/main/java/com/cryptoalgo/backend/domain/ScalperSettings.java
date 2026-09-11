package com.cryptoalgo.backend.domain;

import io.r2dbc.postgresql.codec.Json;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Table("scalper_settings")
public record ScalperSettings(@Id UUID id, UUID tenantId, UUID userId, UUID strategyId, boolean enabled,
                              String mode, Json instruments, String timeframe,
                              BigDecimal stakeAmount, int maxOpenTrades, int cooldownSeconds,
                              BigDecimal dailyLossLimit, boolean killSwitch,
                              String lastDecision, String lastError, Instant lastHeartbeat,
                              Instant createdAt, Instant updatedAt) {}
