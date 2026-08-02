-- Speed up strategies list + paper-stats aggregations (API Gateway was timing out).

CREATE INDEX IF NOT EXISTS idx_strategies_tenant_market_created
    ON strategies (tenant_id, market_type, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_strategies_tenant_status
    ON strategies (tenant_id, status);

CREATE INDEX IF NOT EXISTS idx_bots_strategy_mode
    ON bots (strategy_id, mode);

CREATE INDEX IF NOT EXISTS idx_bots_tenant_market_mode_status
    ON bots (tenant_id, market_type, mode, status);

CREATE INDEX IF NOT EXISTS idx_positions_bot_status_closed
    ON positions (bot_id, status)
    WHERE status = 'CLOSED';

CREATE INDEX IF NOT EXISTS idx_positions_bot_opened
    ON positions (bot_id, opened_at DESC);

CREATE INDEX IF NOT EXISTS idx_orders_bot_created
    ON orders (bot_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_backtests_strategy_created
    ON backtests (strategy_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_entity_created
    ON audit_log (tenant_id, entity_type, entity_id, created_at DESC);
