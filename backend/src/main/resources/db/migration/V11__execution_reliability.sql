-- Reliability follow-up: make fill application and reservation release retry-safe.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS reserved_margin_inr NUMERIC(30,10) NOT NULL DEFAULT 0;
UPDATE orders SET reserved_margin_inr = reserved_amount
WHERE reserved_margin_inr = 0 AND reserved_amount > 0;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS accounted_filled_qty NUMERIC(30,10) NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS recovery_state VARCHAR(30);

-- Existing rows are considered already accounted for; new rows advance this marker
-- transactionally during reconciliation.
UPDATE orders
SET accounted_filled_qty = COALESCE(filled_qty, 0)
WHERE accounted_filled_qty = 0 AND COALESCE(filled_qty, 0) > 0;

CREATE INDEX IF NOT EXISTS idx_orders_recovery
    ON orders (status, mode, updated_at)
    WHERE mode = 'LIVE' AND status IN ('SUBMITTING', 'PENDING_RECONCILE', 'UNKNOWN');

CREATE UNIQUE INDEX IF NOT EXISTS uq_live_client_order_id
    ON orders (tenant_id, client_order_id)
    WHERE mode = 'LIVE';

-- Status validation is intentionally application-owned here: older installations may
-- contain exchange-specific terminal labels that must remain readable during rollout.
