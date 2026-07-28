package com.cryptoalgo.backend.repo;

import com.cryptoalgo.backend.domain.Bot;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface BotRepository extends ReactiveCrudRepository<Bot, UUID> {
    Flux<Bot> findByTenantIdAndUserIdOrderByCreatedAtDesc(UUID tenantId, UUID userId);
    Mono<Bot> findByIdAndTenantId(UUID id, UUID tenantId);
    Flux<Bot> findByStrategyIdAndStatus(UUID strategyId, String status);
    Flux<Bot> findByStatus(String status);
}
