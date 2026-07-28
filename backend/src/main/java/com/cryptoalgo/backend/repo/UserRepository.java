package com.cryptoalgo.backend.repo;

import com.cryptoalgo.backend.domain.User;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface UserRepository extends ReactiveCrudRepository<User, UUID> {
    Mono<User> findByEmail(String email);
    Mono<User> findByTenantIdAndEmail(UUID tenantId, String email);
    Flux<User> findByTenantId(UUID tenantId);
    Mono<Long> count();
}
