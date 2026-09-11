-- Order tracking: persist exact reserved margin INR and exchange identifiers for idempotent reconciliation.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS reserved_margin_inr NUMERIC(30, 10);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS exchange_order_id_confirmed BOOLEAN DEFAULT FALSE;

-- Position tracking: persist protection state and actual fill details.
ALTER TABLE positions ADD COLUMN IF NOT EXISTS protection_order_id VARCHAR(80);
ALTER TABLE positions ADD COLUMN IF NOT EXISTS protection_order_status VARCHAR(20);
ALTER TABLE positions ADD COLUMN IF NOT EXISTS actual_fill_qty NUMERIC(30, 10);
ALTER TABLE positions ADD COLUMN IF NOT EXISTS actual_avg_price NUMERIC(30, 10);

-- New indexes for portfolio APIs (pagination + server-side filtering).
CREATE INDEX IF NOT EXISTS idx_positions_tenant_user_status_opened
    ON positions (tenant_id, user_id, status, opened_at DESC);

CREATE INDEX IF NOT EXISTS idx_orders_tenant_user_status_created
    ON orders (tenant_id, user_id, status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_orders_tenant_mode_status_created
    ON orders (tenant_id, mode, status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_positions_tenant_status
    ON positions (tenant_id, status);

-- HTTP client pool observability: track active connections per pool.
CREATE TABLE IF NOT EXISTS http_pool_metrics (
    id UUID PRIMARY KEY,
    pool_name VARCHAR(64) NOT NULL,
    active_connections INT NOT NULL,
    pending_acquire INT NOT NULL,
    max_connections INT NOT NULL,
    measured_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_http_pool_metrics_pool_measured
    ON http_pool_metrics (pool_name, measured_at DESC);

-- Scheduler in-flight tracking: prevent overlapping scheduled tasks.
CREATE TABLE IF NOT EXISTS scheduler_inflight (
    job_name VARCHAR(128) PRIMARY KEY,
    started_at TIMESTAMPTZ NOT NULL,
    last_heartbeat TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Cache for public data: exchange snapshot, rates, metadata (short TTL).
CREATE TABLE IF NOT EXISTS exchange_snapshot_cache (
    cache_key VARCHAR(256) PRIMARY KEY,
    cached_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ttl_seconds INT NOT NULL DEFAULT 5,
    payload JSONB NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_exchange_snapshot_cache_key_ttl
    ON exchange_snapshot_cache (cache_key, cached_at);
