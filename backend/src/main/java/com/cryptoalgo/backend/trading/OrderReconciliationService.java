package com.cryptoalgo.backend.trading;

import com.cryptoalgo.backend.common.SecretCrypto;
import com.cryptoalgo.backend.domain.TradeOrder;
import com.cryptoalgo.backend.repo.BotRepository;
import com.cryptoalgo.backend.repo.ExchangeKeyRepository;
import com.cryptoalgo.backend.repo.TradeOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;

/** Polls CoinDCX for OPEN + PENDING_RECONCILE LIVE orders and settles them. */
@Service
public class OrderReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(OrderReconciliationService.class);

    private final TradeOrderRepository orders;
    private final BotRepository bots;
    private final ExchangeKeyRepository keys;
    private final CoinDcxTradeClient trade;
    private final CoinDcxFuturesClient futures;
    private final SecretCrypto crypto;
    private final TenantLiveRiskGate riskGate;

    public OrderReconciliationService(TradeOrderRepository orders, BotRepository bots,
                                      ExchangeKeyRepository keys, CoinDcxTradeClient trade,
                                      CoinDcxFuturesClient futures, SecretCrypto crypto,
                                      TenantLiveRiskGate riskGate) {
        this.orders = orders;
        this.bots = bots;
        this.keys = keys;
        this.trade = trade;
        this.futures = futures;
        this.crypto = crypto;
        this.riskGate = riskGate;
    }

    @Scheduled(fixedDelayString = "${app.reconcile-ms:15000}")
    public void reconcile() {
        Flux.merge(
                        orders.findByStatusAndModeOrderByCreatedAtAsc("OPEN", "LIVE"),
                        orders.findByStatusAndModeOrderByCreatedAtAsc("PENDING_RECONCILE", "LIVE"),
                        orders.findByStatusAndModeOrderByCreatedAtAsc("UNKNOWN", "LIVE"),
                        orders.findByStatusAndModeOrderByCreatedAtAsc("SUBMITTING", "LIVE")
                )
                .flatMap(this::refreshOrder, 4)
                .subscribe(o -> {}, e -> log.error("Reconciliation cycle failed", e));
    }

    private Mono<TradeOrder> refreshOrder(TradeOrder order) {
        return bots.findById(order.botId())
                .flatMap(bot -> keys.findById(bot.exchangeKeyId())
                        .flatMap(key -> {
                            String apiKey = crypto.decrypt(key.apiKeyEnc());
                            String apiSecret = crypto.decrypt(key.apiSecretEnc());
                            if ("FUTURES".equals(order.marketType())
                                    && (order.exchangeOrderId() == null
                                    || "PENDING_RECONCILE".equals(order.status())
                                    || "UNKNOWN".equals(order.status())
                                    || "SUBMITTING".equals(order.status()))) {
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
        String s = status.path("status").asText("open").toUpperCase();
        String exchangeId = status.path("id").asText(order.exchangeOrderId());
        BigDecimal total = new BigDecimal(status.path("total_quantity")
                .asText(order.quantity() == null ? "0" : order.quantity().toPlainString()));
        BigDecimal remaining = new BigDecimal(status.path("remaining_quantity").asText("0"));
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
                new BigDecimal(status.path("avg_price").asText(
                        order.avgPrice() == null ? "0" : order.avgPrice().toPlainString())),
                new BigDecimal(status.path("fee_amount").asText(
                        order.fee() == null ? "0" : order.fee().toPlainString())),
                order.error(), order.createdAt(), Instant.now());
        return orders.save(updated)
                .flatMap(saved -> {
                    if ("REJECTED".equals(mapped) || "CANCELLED".equals(mapped)
                            || "FAILED".equals(mapped)) {
                        BigDecimal release = order.price() == null || order.quantity() == null
                                ? BigDecimal.ZERO : order.quantity(); // best-effort; reservation uses margin INR
                        // Release any leftover reservation tied to ambiguous submit (qty not INR —
                        // use stake proxy via error path already released on hard fail).
                        return riskGate.release(order.tenantId(),
                                        order.price() != null && order.quantity() != null
                                                ? order.price().multiply(order.quantity())
                                                : BigDecimal.ZERO)
                                .thenReturn(saved);
                    }
                    return Mono.just(saved);
                });
    }

    private static TradeOrder copyStatus(TradeOrder order, String status, String error) {
        return new TradeOrder(order.id(), order.tenantId(), order.userId(), order.botId(),
                order.signalId(), order.exchangeOrderId(), order.clientOrderId(), order.pair(),
                order.side(), order.orderType(), order.marketType(), order.mode(), status,
                order.price(), order.quantity(), order.filledQty(), order.avgPrice(), order.fee(),
                error, order.createdAt(), Instant.now());
    }
}
