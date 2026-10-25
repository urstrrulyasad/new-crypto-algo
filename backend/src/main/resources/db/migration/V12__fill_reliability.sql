-- Forward-only fill accounting and recovery state. Never reset trading records.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS requested_qty NUMERIC(30,10);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS remaining_qty NUMERIC(30,10);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS exchange_updated_at TIMESTAMPTZ;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS recovery_state VARCHAR(30);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS realized_pnl NUMERIC(30,10) NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS fill_event_key VARCHAR(160);

UPDATE orders
SET requested_qty = COALESCE(requested_qty, quantity),
    remaining_qty = COALESCE(remaining_qty, GREATEST(COALESCE(quantity, 0) - COALESCE(filled_qty, 0), 0))
WHERE requested_qty IS NULL OR remaining_qty IS NULL;

CREATE TABLE IF NOT EXISTS order_fills (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES orders(id),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    exchange_fill_id VARCHAR(160),
    event_key VARCHAR(160) NOT NULL,
    quantity NUMERIC(30,10) NOT NULL CHECK (quantity > 0),
    price NUMERIC(30,10) NOT NULL CHECK (price >= 0),
    fee NUMERIC(30,10) NOT NULL DEFAULT 0 CHECK (fee >= 0),
    exchange_updated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (order_id, event_key)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_order_fill_exchange_id
    ON order_fills(order_id, exchange_fill_id)
    WHERE exchange_fill_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_order_fills_order ON order_fills(order_id, created_at);
CREATE INDEX IF NOT EXISTS idx_orders_exchange_ids ON orders(exchange_order_id, client_order_id);
