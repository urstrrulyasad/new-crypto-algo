package com.cryptoalgo.backend.trading;

import com.cryptoalgo.backend.common.SecretCrypto;
import com.cryptoalgo.backend.domain.TradeOrder;
import com.cryptoalgo.backend.repo.BotRepository;
import com.cryptoalgo.backend.repo.ExchangeKeyRepository;
import com.cryptoalgo.backend.repo.TradeOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/** Polls CoinDCX for OPEN + PENDING_RECONCILE LIVE orders and settles them. */
@Service
public class OrderReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(OrderReconciliationService.class);
    private final AtomicBoolean reconcileInFlight = new AtomicBoolean();

    private final TradeOrderRepository orders;
    private final BotRepository bots;
    private final ExchangeKeyRepository keys;
    private final CoinDcxTradeClient trade;
    private final CoinDcxFuturesClient futures;
    private final SecretCrypto crypto;
    private final TenantLiveRiskGate riskGate;
    private final DatabaseClient db;

    public OrderReconciliationService(TradeOrderRepository orders, BotRepository bots,
                                      ExchangeKeyRepository keys, CoinDcxTradeClient trade,
                                      CoinDcxFuturesClient futures, SecretCrypto crypto,
                                      TenantLiveRiskGate riskGate, DatabaseClient db) {
        this.orders = orders;
        this.bots = bots;
        this.keys = keys;
        this.trade = trade;
        this.futures = futures;
        this.crypto = crypto;
        this.riskGate = riskGate;
        this.db = db;
    }

    @Scheduled(fixedDelayString = "${app.reconcile-ms:15000}")
    public void reconcile() {
        if (!reconcileInFlight.compareAndSet(false, true)) {
            log.debug("Skipping reconciliation because the previous cycle is still running");
            return;
        }
        Flux.merge(
                        orders.findByStatusAndModeOrderByCreatedAtAsc("OPEN", "LIVE"),
                        orders.findByStatusAndModeOrderByCreatedAtAsc("PENDING_RECONCILE", "LIVE"),
                        orders.findByStatusAndModeOrderByCreatedAtAsc("UNKNOWN", "LIVE"),
                        orders.findByStatusAndModeOrderByCreatedAtAsc("SUBMITTING", "LIVE")
                )
                .distinct(TradeOrder::id)
                .flatMap(order -> refreshOrder(order)
                        .onErrorResume(error -> {
                            log.warn("Unable to reconcile order {} without stopping the cycle", order.id(), error);
                            return Mono.empty();
                        }), 4)
                .doFinally(signal -> reconcileInFlight.set(false))
                .subscribe(o -> {}, e -> log.error("Reconciliation cycle failed", e));
    }

    private Mono<TradeOrder> refreshOrder(TradeOrder order) {
        return bots.findById(order.botId())
                .flatMap(bot -> keys.findById(bot.exchangeKeyId())
                        .flatMap(key -> {
                            String apiKey = crypto.decrypt(key.apiKeyEnc());
                            String apiSecret = crypto.decrypt(key.apiSecretEnc());
                            if ("FUTURES".equals(order.marketType())) {
                                // Futures never use spot /orders/status (404). Always list by pair/client id.
                                return futures.findOrderByClientOrderId(apiKey, apiSecret,
                                                order.pair(), order.clientOrderId())
                                        .flatMap(ex -> settleFromExchange(order, ex))
                                        .switchIfEmpty(Mono.defer(() -> {
                                            // Still unknown — leave PENDING_RECONCILE; never blind-retry place.
                                            if ("SUBMITTING".equals(order.status())) {
                                                TradeOrder pending = copyStatus(order, "PENDING_RECONCILE",
                                                        order.error());
                                                return orders.save(pending);
                                            }
                                            return Mono.empty();
                                        }));
                            }
                            if (order.exchangeOrderId() == null) return Mono.empty();
                            return trade.orderStatus(apiKey, apiSecret, order.exchangeOrderId())
                                    .flatMap(status -> settleFromExchange(order, status));
                        }));
    }

    private Mono<TradeOrder> settleFromExchange(TradeOrder order, com.fasterxml.jackson.databind.JsonNode status) {
        String s = firstText(status, "status", "order_status", "state", "open").toUpperCase();
        String exchangeId = firstText(status, "id", "order_id", "exchange_order_id", order.exchangeOrderId());
        BigDecimal total = decimal(status, order.quantity(), "total_quantity", "orig_qty", "quantity");
        BigDecimal remaining = decimal(status, BigDecimal.ZERO, "remaining_quantity", "remaining_qty", "leaves_qty");
        String mapped = s.contains("PARTIAL") ? "OPEN"
                : s.contains("FILL") ? "FILLED"
                : s.contains("CANCEL") ? "CANCELLED"
                : s.contains("REJECT") ? "REJECTED"
                : s;
        TradeOrder updated = new TradeOrder(order.id(), order.tenantId(), order.userId(),
                order.botId(), order.signalId(), exchangeId,
                order.clientOrderId(), order.pair(), order.side(), order.orderType(),
                order.marketType(), order.mode(), mapped,
                order.price(), order.quantity(), total.subtract(remaining),
                decimal(status, order.avgPrice(), "avg_price", "average_price", "price"),
                decimal(status, order.fee(), "fee_amount", "fee", "fees"),
                order.error(), order.createdAt(), Instant.now());
        return orders.save(updated)
                .flatMap(saved -> {
                    if ("REJECTED".equals(mapped) || "CANCELLED".equals(mapped)
                            || "FAILED".equals(mapped) || "FILLED".equals(mapped)) {
                        return riskGate.releaseOrderReservation(order.tenantId(), order.id())
                                .thenReturn(saved);
                    }
                    return Mono.just(saved);
                });
    }

    private static String firstText(com.fasterxml.jackson.databind.JsonNode node, String... names) {
        for (String name : names) {
            if (name == null) continue;
            String value = node.path(name).asText("");
            if (!value.isBlank() && !"null".equalsIgnoreCase(value)) return value;
        }
        return "";
    }

    private static BigDecimal decimal(com.fasterxml.jackson.databind.JsonNode node,
                                      BigDecimal fallback, String... names) {
        String raw = firstText(node, names);
        if (raw.isBlank()) return fallback == null ? BigDecimal.ZERO : fallback;
        try {
            BigDecimal value = new BigDecimal(raw);
            return value.signum() < 0 ? (fallback == null ? BigDecimal.ZERO : fallback) : value;
        } catch (NumberFormatException ignored) {
            return fallback == null ? BigDecimal.ZERO : fallback;
        }
    }

    private static TradeOrder copyStatus(TradeOrder order, String status, String error) {
        return new TradeOrder(order.id(), order.tenantId(), order.userId(), order.botId(),
                order.signalId(), order.exchangeOrderId(), order.clientOrderId(), order.pair(),
                order.side(), order.orderType(), order.marketType(), order.mode(), status,
                order.price(), order.quantity(), order.filledQty(), order.avgPrice(), order.fee(),
                error, order.createdAt(), Instant.now());
    }
}
