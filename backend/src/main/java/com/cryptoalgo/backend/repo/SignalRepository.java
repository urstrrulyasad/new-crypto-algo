package com.cryptoalgo.backend.repo;

import com.cryptoalgo.backend.domain.Signal;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface SignalRepository extends ReactiveCrudRepository<Signal, UUID> {
    Mono<Boolean> existsByIdempotencyKey(String idempotencyKey);
}
