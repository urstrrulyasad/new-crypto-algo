package com.cryptoalgo.backend.strategy;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Aggregated paper-trading results per strategy (from closed paper positions). */
@Service
public class PaperStatsService {

    public record PaperStats(long closedTrades, long wins, BigDecimal totalPnl) {
        public double winRate() {
            return closedTrades == 0 ? 0 : (double) wins / closedTrades;
        }
    }

    private final DatabaseClient db;

    public PaperStatsService(DatabaseClient db) {
        this.db = db;
    }

    public Mono<PaperStats> forStrategy(UUID strategyId) {
        return db.sql("""
                        SELECT count(*) AS closed,
                               count(*) FILTER (WHERE p.realized_pnl > 0) AS wins,
                               COALESCE(sum(p.realized_pnl), 0) AS pnl
                        FROM positions p
                        JOIN bots b ON b.id = p.bot_id
                        WHERE b.strategy_id = :sid AND b.mode = 'PAPER' AND p.status = 'CLOSED'
                        """)
                .bind("sid", strategyId)
                .map(row -> new PaperStats(
                        row.get("closed", Long.class),
                        row.get("wins", Long.class),
                        row.get("pnl", BigDecimal.class)))
                .one()
                .defaultIfEmpty(new PaperStats(0, 0, BigDecimal.ZERO));
    }

    /** Single grouped query for many strategies (list endpoint). */
    public Mono<Map<UUID, PaperStats>> forStrategies(Collection<UUID> strategyIds) {
        if (strategyIds == null || strategyIds.isEmpty()) {
            return Mono.just(Map.of());
        }
        // UUID.toString() is safe for IN-list construction (typed UUID values only).
        String inList = strategyIds.stream()
                .map(UUID::toString)
                .map(id -> "'" + id + "'::uuid")
                .collect(Collectors.joining(","));
        String sql = """
                SELECT b.strategy_id AS sid,
                       count(*)::bigint AS closed,
                       count(*) FILTER (WHERE p.realized_pnl > 0)::bigint AS wins,
                       COALESCE(sum(p.realized_pnl), 0) AS pnl
                FROM positions p
                JOIN bots b ON b.id = p.bot_id
                WHERE b.strategy_id IN (%s)
                  AND b.mode = 'PAPER'
                  AND p.status = 'CLOSED'
                GROUP BY b.strategy_id
                """.formatted(inList);
        return db.sql(sql)
                .map(row -> Map.entry(
                        row.get("sid", UUID.class),
                        new PaperStats(
                                row.get("closed", Long.class),
                                row.get("wins", Long.class),
                                row.get("pnl", BigDecimal.class))))
                .all()
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .defaultIfEmpty(Map.of());
    }
}
