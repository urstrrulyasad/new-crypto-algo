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
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;

/** Polls CoinDCX for the status of open LIVE orders and reconciles fills. */
@Service
public class OrderReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(OrderReconciliationService.class);

    private final TradeOrderRepository orders;
    private final BotRepository bots;
    private final ExchangeKeyRepository keys;
    private final CoinDcxTradeClient trade;
    private final SecretCrypto crypto;

    public OrderReconciliationService(TradeOrderRepository orders, BotRepository bots,
                                      ExchangeKeyRepository keys, CoinDcxTradeClient trade,
                                      SecretCrypto crypto) {
        this.orders = orders;
        this.bots = bots;
        this.keys = keys;
        this.trade = trade;
        this.crypto = crypto;
    }

    @Scheduled(fixedDelayString = "${app.reconcile-ms:15000}")
    public void reconcile() {
        orders.findByStatusAndModeOrderByCreatedAtAsc("OPEN", "LIVE")
                .flatMap(this::refreshOrder, 4)
                .subscribe(o -> {}, e -> log.error("Reconciliation cycle failed", e));
    }

    private Mono<TradeOrder> refreshOrder(TradeOrder order) {
        if (order.exchangeOrderId() == null) return Mono.empty();
        return bots.findById(order.botId())
                .flatMap(bot -> keys.findById(bot.exchangeKeyId()))
                .flatMap(key -> trade.orderStatus(
                        crypto.decrypt(key.apiKeyEnc()), crypto.decrypt(key.apiSecretEnc()),
                        order.exchangeOrderId()))
                .flatMap(status -> {
                    String s = status.path("status").asText("open").toUpperCase();
                    if (s.equals(order.status())) return Mono.empty();
                    BigDecimal total = new BigDecimal(status.path("total_quantity")
                            .asText(order.quantity().toPlainString()));
                    BigDecimal remaining = new BigDecimal(status.path("remaining_quantity").asText("0"));
                    TradeOrder updated = new TradeOrder(order.id(), order.tenantId(), order.userId(),
                            order.botId(), order.signalId(), order.exchangeOrderId(),
                            order.clientOrderId(), order.pair(), order.side(), order.orderType(),
                            order.marketType(), order.mode(), s.equals("PARTIALLY_FILLED") ? "OPEN" : s,
                            order.price(), order.quantity(), total.subtract(remaining),
                            new BigDecimal(status.path("avg_price").asText("0")),
                            new BigDecimal(status.path("fee_amount").asText("0")),
                            null, order.createdAt(), Instant.now());
                    return orders.save(updated);
                })
                .onErrorResume(e -> {
                    log.warn("Order {} reconciliation failed: {}", order.id(), e.getMessage());
                    return Mono.empty();
                });
    }
}
