package com.cryptoalgo.backend.repo;

import com.cryptoalgo.backend.domain.ExchangeKey;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ExchangeKeyRepository extends ReactiveCrudRepository<ExchangeKey, UUID> {
    Flux<ExchangeKey> findByTenantIdAndUserId(UUID tenantId, UUID userId);
    Mono<ExchangeKey> findByIdAndTenantId(UUID id, UUID tenantId);
}
