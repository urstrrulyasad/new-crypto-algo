package com.cryptoalgo.backend.repo;

import com.cryptoalgo.backend.domain.Backtest;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;


public interface BacktestRepository extends ReactiveCrudRepository<Backtest, UUID> {
    Flux<Backtest> findByTenantIdAndStrategyIdOrderByCreatedAtDesc(UUID tenantId, UUID strategyId);
    Mono<Backtest> findByIdAndTenantId(UUID id, UUID tenantId);

    /**
     * Latest DONE backtest for gate checks, WITHOUT the heavy {@code trades} JSONB
     * (returned as null). The scheduled paper-evaluation loop only reads {@code metrics};
     * pulling the full per-trade array for every strategy every 60s was the dominant
     * Supabase egress source. Projecting {@code trades} out and pushing the
     * DONE-filter + LIMIT 1 into SQL keeps this to one small row per call.
     */
    @Query("""
            SELECT id, tenant_id, strategy_id, timeframe, pairs, range_start, range_end,
                   status, metrics, NULL::jsonb AS trades, error, created_at, finished_at
            FROM backtests
            WHERE tenant_id = :tenantId AND strategy_id = :strategyId
              AND status = 'DONE' AND metrics IS NOT NULL
            ORDER BY created_at DESC
            LIMIT 1
            """)
    Mono<Backtest> findLatestDoneWithMetrics(@Param("tenantId") UUID tenantId,
                                             @Param("strategyId") UUID strategyId);

}


