package com.cryptoalgo.backend.repo;

import com.cryptoalgo.backend.domain.TradeOrder;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface TradeOrderRepository extends ReactiveCrudRepository<TradeOrder, UUID> {
    Flux<TradeOrder> findByTenantIdAndUserIdOrderByCreatedAtDesc(UUID tenantId, UUID userId);
    Flux<TradeOrder> findByTenantIdAndBotIdOrderByCreatedAtDesc(UUID tenantId, UUID botId);
    Flux<TradeOrder> findByBotIdOrderByCreatedAtDesc(UUID botId);
    Flux<TradeOrder> findByStatusAndModeOrderByCreatedAtAsc(String status, String mode);
    Mono<TradeOrder> findByTenantIdAndClientOrderId(UUID tenantId, String clientOrderId);
    Flux<TradeOrder> findByTenantIdAndModeAndStatusIn(UUID tenantId, String mode, java.util.Collection<String> statuses);
}
