package com.cryptoalgo.backend.repo;

import com.cryptoalgo.backend.domain.Position;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.data.r2dbc.repository.Query;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface PositionRepository extends ReactiveCrudRepository<Position, UUID> {
    Flux<Position> findByTenantIdAndUserIdOrderByOpenedAtDesc(UUID tenantId, UUID userId);
    Flux<Position> findByBotIdAndStatus(UUID botId, String status);
    Flux<Position> findByBotIdOrderByOpenedAtDesc(UUID botId);
    Mono<Position> findByTenantIdAndUserIdAndBotIdAndPairAndSideAndStatus(
            UUID tenantId, UUID userId, UUID botId, String pair, String side, String status);

    @Query("SELECT * FROM positions WHERE tenant_id = :tenantId AND user_id = :userId "
            + "AND bot_id = :botId AND pair = :pair AND side = :side AND status = :status "
            + "AND opened_at >= :dayStart AND opened_at < :dayEnd "
            + "ORDER BY opened_at DESC LIMIT 1")
    Mono<Position> findOpenPositionForUtcDay(UUID tenantId, UUID userId, UUID botId,
                                             String pair, String side, String status,
                                             java.time.Instant dayStart, java.time.Instant dayEnd);

    Mono<Position> findByBotIdAndPairAndSideAndStatus(UUID botId, String pair, String side, String status);

    Mono<Position> findByBotIdAndPairAndStatus(UUID botId, String pair, String status);
    Mono<Long> countByBotIdAndStatus(UUID botId, String status);
    Flux<Position> findByStatus(String status);
    Flux<Position> findByTenantIdAndStatus(UUID tenantId, String status);
}
