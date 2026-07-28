package com.cryptoalgo.backend.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Table("positions")
public record Position(@Id UUID id, UUID tenantId, UUID userId, UUID botId, String pair,
                       String side, BigDecimal quantity, BigDecimal entryPrice, BigDecimal exitPrice,
                       BigDecimal leverage, String status, BigDecimal realizedPnl,
                       Instant openedAt, Instant closedAt) {}
