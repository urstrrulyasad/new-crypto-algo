package com.cryptoalgo.backend.repo;

import com.cryptoalgo.backend.domain.AiProvider;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface AiProviderRepository extends ReactiveCrudRepository<AiProvider, UUID> {
    Flux<AiProvider> findByTenantId(UUID tenantId);
    Mono<AiProvider> findByIdAndTenantId(UUID id, UUID tenantId);
}
