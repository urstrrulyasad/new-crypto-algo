-- Partition append-heavy audit records by UTC calendar day.
-- Candles are intentionally not partitioned because V2 removed candle persistence.
-- The DEFAULT partition protects writes whose date falls outside the pre-created range.

ALTER TABLE audit_log RENAME TO audit_log_legacy;

CREATE TABLE audit_log (
    id           UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id    UUID NOT NULL,
    user_id      UUID,
    action       VARCHAR(80) NOT NULL,
    entity_type  VARCHAR(40),
    entity_id    UUID,
    details      JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
) PARTITION BY RANGE (created_at);

CREATE TABLE audit_log_default PARTITION OF audit_log DEFAULT;

DO $$
DECLARE
    day_start DATE := (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date - 7;
    day_end DATE := (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date + 32;
    partition_name TEXT;
BEGIN
    WHILE day_start < day_end LOOP
        partition_name := 'audit_log_' || to_char(day_start, 'YYYY_MM_DD');
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF audit_log FOR VALUES FROM (%L) TO (%L)',
            partition_name,
            day_start::timestamp AT TIME ZONE 'UTC',
            (day_start + 1)::timestamp AT TIME ZONE 'UTC'
        );
        day_start := day_start + 1;
    END LOOP;
END $$;

INSERT INTO audit_log (id, tenant_id, user_id, action, entity_type, entity_id, details, created_at)
SELECT id, tenant_id, user_id, action, entity_type, entity_id, details, created_at
FROM audit_log_legacy;

CREATE INDEX idx_audit_log_tenant_created
    ON audit_log (tenant_id, created_at DESC);
CREATE INDEX idx_audit_log_entity_created
    ON audit_log (tenant_id, entity_type, entity_id, created_at DESC);
CREATE INDEX idx_audit_log_created
    ON audit_log (created_at DESC);

-- Keep the legacy table for rollback/verification during deployment. It is not
-- used by the application after this migration and can be archived later.
ALTER TABLE audit_log_legacy RENAME TO audit_log_legacy_v9;
