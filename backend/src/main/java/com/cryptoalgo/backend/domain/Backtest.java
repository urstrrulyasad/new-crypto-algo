package com.cryptoalgo.backend.domain;

import io.r2dbc.postgresql.codec.Json;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("backtests")
public record Backtest(@Id UUID id, UUID tenantId, UUID strategyId, String timeframe, Json pairs,
                       Instant rangeStart, Instant rangeEnd, String status, Json metrics,
                       Json trades, String error, Instant createdAt, Instant finishedAt) {}
