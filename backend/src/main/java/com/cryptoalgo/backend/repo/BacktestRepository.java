package com.cryptoalgo.backend.repo;

import com.cryptoalgo.backend.domain.Backtest;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface BacktestRepository extends ReactiveCrudRepository<Backtest, UUID> {
    Flux<Backtest> findByTenantIdAndStrategyIdOrderByCreatedAtDesc(UUID tenantId, UUID strategyId);
    Mono<Backtest> findByIdAndTenantId(UUID id, UUID tenantId);
}
