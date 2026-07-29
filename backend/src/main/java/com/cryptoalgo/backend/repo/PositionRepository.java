package com.cryptoalgo.backend.repo;

import com.cryptoalgo.backend.domain.Position;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface PositionRepository extends ReactiveCrudRepository<Position, UUID> {
    Flux<Position> findByTenantIdAndUserIdOrderByOpenedAtDesc(UUID tenantId, UUID userId);
    Flux<Position> findByBotIdAndStatus(UUID botId, String status);
    Mono<Position> findByBotIdAndPairAndStatus(UUID botId, String pair, String status);
    Mono<Long> countByBotIdAndStatus(UUID botId, String status);
    Flux<Position> findByStatus(String status);
}
