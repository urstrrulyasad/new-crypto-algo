CREATE TABLE scalper_settings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    user_id UUID NOT NULL REFERENCES users(id),
    strategy_id UUID REFERENCES strategies(id),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    mode VARCHAR(10) NOT NULL DEFAULT 'PAPER',
    instruments JSONB NOT NULL DEFAULT '[]'::jsonb,
    timeframe VARCHAR(10) NOT NULL DEFAULT '5m',
    stake_amount NUMERIC(30,10) NOT NULL DEFAULT 0,
    max_open_trades INT NOT NULL DEFAULT 3,
    cooldown_seconds INT NOT NULL DEFAULT 300,
    daily_loss_limit NUMERIC(30,10) NOT NULL DEFAULT 0,
    kill_switch BOOLEAN NOT NULL DEFAULT FALSE,
    last_decision VARCHAR(120),
    last_error TEXT,
    last_heartbeat TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id)
);
CREATE INDEX idx_scalper_heartbeat ON scalper_settings(tenant_id, last_heartbeat);

ALTER TABLE orders ADD COLUMN IF NOT EXISTS reserved_amount NUMERIC(30,10) NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX IF NOT EXISTS uq_open_position_side
  ON positions (bot_id, pair, side) WHERE status = 'OPEN';
CREATE UNIQUE INDEX IF NOT EXISTS uq_signal_idempotency_key ON signals(idempotency_key);
