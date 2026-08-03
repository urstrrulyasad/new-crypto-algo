package com.cryptoalgo.backend.trading;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Tenant-scoped LIVE margin reservation via Postgres row lock (no Redis).
 * Serializes concurrent LIVE bots on the same tenant wallet budget.
 */
@Service
public class TenantLiveRiskGate {

    private final DatabaseClient db;
    private final TransactionalOperator tx;

    public TenantLiveRiskGate(DatabaseClient db, TransactionalOperator tx) {
        this.db = db;
        this.tx = tx;
    }

    public Mono<Boolean> isProtectionDegraded(UUID tenantId) {
        return ensureRow(tenantId)
                .then(db.sql("SELECT protection_degraded FROM tenant_live_risk WHERE tenant_id = :tid")
                        .bind("tid", tenantId)
                        .map((row, meta) -> Boolean.TRUE.equals(row.get("protection_degraded", Boolean.class)))
                        .one()
                        .defaultIfEmpty(false));
    }

    public Mono<Void> setProtectionDegraded(UUID tenantId, boolean degraded) {
        return ensureRow(tenantId)
                .then(db.sql("""
                        UPDATE tenant_live_risk
                        SET protection_degraded = :d, updated_at = :now
                        WHERE tenant_id = :tid
                        """)
                        .bind("d", degraded)
                        .bind("now", Instant.now())
                        .bind("tid", tenantId)
                        .fetch().rowsUpdated())
                .then();
    }

    public Mono<BigDecimal> reservedMargin(UUID tenantId) {
        return ensureRow(tenantId)
                .then(db.sql("SELECT reserved_margin_inr FROM tenant_live_risk WHERE tenant_id = :tid")
                        .bind("tid", tenantId)
                        .map((row, meta) -> {
                            BigDecimal v = row.get("reserved_margin_inr", BigDecimal.class);
                            return v == null ? BigDecimal.ZERO : v;
                        })
                        .one()
                        .defaultIfEmpty(BigDecimal.ZERO));
    }

    /**
     * Lock tenant row and reserve margin if remaining capacity allows.
     * {@code maxReservable} = live wallet × maxWalletPct − already open exposure (caller-computed).
     */
    public Mono<Boolean> tryReserve(UUID tenantId, BigDecimal amount, BigDecimal maxReservable) {
        if (amount == null || amount.signum() <= 0) {
            return Mono.just(false);
        }
        return tx.transactional(ensureRow(tenantId).then(
                db.sql("""
                        SELECT reserved_margin_inr AS reserved
                        FROM tenant_live_risk
                        WHERE tenant_id = :tid
                        FOR UPDATE
                        """)
                        .bind("tid", tenantId)
                        .map((row, meta) -> {
                            BigDecimal reserved = row.get("reserved", BigDecimal.class);
                            return reserved == null ? BigDecimal.ZERO : reserved;
                        })
                        .one()
                        .flatMap(reserved -> {
                            BigDecimal cap = maxReservable == null ? BigDecimal.ZERO : maxReservable;
                            if (reserved.add(amount).compareTo(cap) > 0) {
                                return Mono.just(false);
                            }
                            return db.sql("""
                                            UPDATE tenant_live_risk
                                            SET reserved_margin_inr = reserved_margin_inr + :amt,
                                                updated_at = :now
                                            WHERE tenant_id = :tid
                                            """)
                                    .bind("amt", amount)
                                    .bind("now", Instant.now())
                                    .bind("tid", tenantId)
                                    .fetch().rowsUpdated()
                                    .map(n -> n > 0);
                        })));
    }

    public Mono<Void> release(UUID tenantId, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            return Mono.empty();
        }
        return tx.transactional(ensureRow(tenantId).then(
                db.sql("""
                        UPDATE tenant_live_risk
                        SET reserved_margin_inr = GREATEST(0, reserved_margin_inr - :amt),
                            updated_at = :now
                        WHERE tenant_id = :tid
                        """)
                        .bind("amt", amount)
                        .bind("now", Instant.now())
                        .bind("tid", tenantId)
                        .fetch().rowsUpdated()
                        .then()));
    }

    private Mono<Void> ensureRow(UUID tenantId) {
        return db.sql("""
                        INSERT INTO tenant_live_risk (tenant_id, reserved_margin_inr, protection_degraded, updated_at)
                        VALUES (:tid, 0, FALSE, :now)
                        ON CONFLICT (tenant_id) DO NOTHING
                        """)
                .bind("tid", tenantId)
                .bind("now", Instant.now())
                .fetch().rowsUpdated()
                .then();
    }
}
