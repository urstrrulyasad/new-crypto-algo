-- =====================================================================
-- V3: Futures / Options market verticals (INR futures primary)
-- =====================================================================

ALTER TABLE strategies
    ADD COLUMN IF NOT EXISTS market_type      VARCHAR(10)  NOT NULL DEFAULT 'SPOT',
    ADD COLUMN IF NOT EXISTS instrument       VARCHAR(40),
    ADD COLUMN IF NOT EXISTS margin_currency  VARCHAR(10);

ALTER TABLE bots
    ADD COLUMN IF NOT EXISTS margin_currency  VARCHAR(10) NOT NULL DEFAULT 'USDT';

ALTER TABLE positions
    ADD COLUMN IF NOT EXISTS margin_currency  VARCHAR(10);

-- Archive legacy spot strategies and stop their bots so only FUTURES is evaluated.
UPDATE bots SET status = 'STOPPED', updated_at = now()
WHERE market_type = 'SPOT' AND status = 'RUNNING';

UPDATE strategies SET status = 'ARCHIVED'
WHERE status NOT IN ('ARCHIVED', 'REJECTED')
  AND (market_type = 'SPOT' OR market_type IS NULL OR market_type = '');

-- Default new auto strategies will set FUTURES explicitly in application code.
CREATE INDEX IF NOT EXISTS idx_strategies_tenant_market_instrument
    ON strategies(tenant_id, market_type, instrument);

CREATE INDEX IF NOT EXISTS idx_bots_tenant_market_mode
    ON bots(tenant_id, market_type, mode, status);
