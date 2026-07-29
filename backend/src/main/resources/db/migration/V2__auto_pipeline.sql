-- =====================================================================
-- V2: autonomous AI pipeline
--  * ai_providers become preset-based (provider type + API key only)
--  * candles are no longer persisted (fetched live, passed straight to AI)
--  * positions carry stop-loss / target
--  * strategies move through pipeline statuses:
--    GENERATED -> BACKTESTED -> PAPER_TRADING -> LIVE_APPROVED | REJECTED
-- =====================================================================

-- ---------------------------------------------------------------------
-- AI providers: one row per provider type; base URL / models come from
-- the built-in preset catalog, admin only supplies the API key.
-- ---------------------------------------------------------------------
UPDATE strategies SET ai_provider_id = NULL;
DELETE FROM ai_providers;

ALTER TABLE ai_providers
    DROP COLUMN name,
    DROP COLUMN base_url,
    DROP COLUMN model,
    DROP COLUMN request_template;

ALTER TABLE ai_providers
    ADD COLUMN priority INT NOT NULL DEFAULT 100,
    ADD CONSTRAINT uq_ai_providers_tenant_type UNIQUE (tenant_id, provider_type);

-- ---------------------------------------------------------------------
-- No candle storage: market data is fetched live from CoinDCX.
-- ---------------------------------------------------------------------
DROP TABLE candles;

-- ---------------------------------------------------------------------
-- Stop-loss / target on every position (paper and live).
-- sl_order_id: exchange id of the resting stop_limit SL order (live only).
-- ---------------------------------------------------------------------
ALTER TABLE positions
    ADD COLUMN sl_price     NUMERIC(30,10),
    ADD COLUMN target_price NUMERIC(30,10),
    ADD COLUMN sl_order_id  VARCHAR(80);

-- ---------------------------------------------------------------------
-- Pipeline statuses for strategies.
-- ---------------------------------------------------------------------
UPDATE strategies SET status = 'LIVE_APPROVED' WHERE status = 'APPROVED';
UPDATE strategies SET status = 'GENERATED'     WHERE status IN ('DRAFT', 'VALIDATED');
