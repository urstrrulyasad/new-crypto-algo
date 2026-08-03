-- Tenant-scoped LIVE risk reservation (single shared app instance; no Redis).
CREATE TABLE IF NOT EXISTS tenant_live_risk (
    tenant_id            UUID PRIMARY KEY REFERENCES tenants(id),
    reserved_margin_inr  NUMERIC(30, 10) NOT NULL DEFAULT 0,
    protection_degraded  BOOLEAN         NOT NULL DEFAULT FALSE,
    updated_at           TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_orders_tenant_client
    ON orders (tenant_id, client_order_id);

CREATE INDEX IF NOT EXISTS idx_orders_status_mode
    ON orders (status, mode, created_at ASC);
