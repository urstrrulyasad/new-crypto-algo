package com.cryptoalgo.backend.repo;

import com.cryptoalgo.backend.domain.Tenant;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface TenantRepository extends ReactiveCrudRepository<Tenant, UUID> {
    Mono<Tenant> findBySlug(String slug);
}
