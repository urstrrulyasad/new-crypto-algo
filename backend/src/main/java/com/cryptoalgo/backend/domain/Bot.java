package com.cryptoalgo.backend.domain;

import io.r2dbc.postgresql.codec.Json;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Table("bots")
public record Bot(@Id UUID id, UUID tenantId, UUID userId, UUID strategyId, UUID exchangeKeyId,
                  String name, String mode, String marketType, Json pairs, String stakeCurrency,
                  BigDecimal stakeAmount, int maxOpenTrades, BigDecimal leverage, String status,
                  boolean killSwitch, String marginCurrency,
                  Instant createdAt, Instant updatedAt) {}
