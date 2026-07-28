package com.cryptoalgo.backend.repo;

import com.cryptoalgo.backend.domain.Strategy;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface StrategyRepository extends ReactiveCrudRepository<Strategy, UUID> {
    Flux<Strategy> findByTenantIdAndUserIdOrderByCreatedAtDesc(UUID tenantId, UUID userId);
    Mono<Strategy> findByIdAndTenantId(UUID id, UUID tenantId);
}
