package com.cryptoalgo.backend.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Table("orders")
public record TradeOrder(@Id UUID id, UUID tenantId, UUID userId, UUID botId, UUID signalId,
                         String exchangeOrderId, String clientOrderId, String pair, String side,
                         String orderType, String marketType, String mode, String status,
                         BigDecimal price, BigDecimal quantity, BigDecimal filledQty,
                         BigDecimal avgPrice, BigDecimal fee, String error,
                         Instant createdAt, Instant updatedAt) {}
