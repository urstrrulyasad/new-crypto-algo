package com.cryptoalgo.backend.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Hard-deletes REJECTED / ARCHIVED strategies and dependents so they never clutter the system.
 */
@Service
public class StrategyPurgeService {

    private static final Logger log = LoggerFactory.getLogger(StrategyPurgeService.class);

    private final DatabaseClient db;

    public StrategyPurgeService(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Void> purgeStrategy(UUID strategyId) {
        if (strategyId == null) return Mono.empty();
        return db.sql("""
                        DELETE FROM orders
                        WHERE bot_id IN (SELECT id FROM bots WHERE strategy_id = :sid)
                        """)
                .bind("sid", strategyId).fetch().rowsUpdated()
                .then(db.sql("""
                                DELETE FROM positions
                                WHERE bot_id IN (SELECT id FROM bots WHERE strategy_id = :sid)
                                """).bind("sid", strategyId).fetch().rowsUpdated())
                .then(db.sql("DELETE FROM bots WHERE strategy_id = :sid")
                        .bind("sid", strategyId).fetch().rowsUpdated())
                .then(db.sql("DELETE FROM signals WHERE strategy_id = :sid")
                        .bind("sid", strategyId).fetch().rowsUpdated())
                .then(db.sql("DELETE FROM backtests WHERE strategy_id = :sid")
                        .bind("sid", strategyId).fetch().rowsUpdated())
                .then(db.sql("DELETE FROM strategies WHERE id = :sid")
                        .bind("sid", strategyId).fetch().rowsUpdated())
                .doOnSuccess(n -> log.info("Purged strategy {} (deleted={})", strategyId, n))
                .then();
    }

    /** Sweep any leftover REJECTED/ARCHIVED rows (safety net). */
    @Scheduled(fixedDelayString = "${app.pipeline.evaluate-ms:60000}")
    public void sweepTerminal() {
        db.sql("""
                SELECT id FROM strategies
                WHERE status IN ('REJECTED', 'ARCHIVED')
                LIMIT 50
                """)
                .map((row, meta) -> row.get("id", UUID.class))
                .all()
                .concatMap(this::purgeStrategy)
                .then()
                .subscribe(v -> {}, e -> log.warn("Terminal strategy sweep failed: {}", e.getMessage()));
    }
}
