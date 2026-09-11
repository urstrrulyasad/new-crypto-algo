-- Allow one aggregated open position per bot/pair/side per UTC trading day.
-- A new position on a later day remains a separate position.
DROP INDEX IF EXISTS uq_positions_open_bot_pair_side;

CREATE UNIQUE INDEX IF NOT EXISTS uq_positions_open_bot_pair_side_day
    ON positions (tenant_id, user_id, bot_id, pair, side, ((opened_at AT TIME ZONE 'UTC')::date))
    WHERE status = 'OPEN';

CREATE INDEX IF NOT EXISTS idx_positions_open_bot_pair_side_day
    ON positions (bot_id, pair, side, opened_at DESC)
    WHERE status = 'OPEN';

COMMENT ON INDEX uq_positions_open_bot_pair_side_day IS
    'One open aggregated position per bot, pair, side, and UTC trading day';

COMMENT ON INDEX idx_positions_open_bot_pair_side_day IS
    'Supports same-day open-position aggregation lookups';

-- Existing V7 index is intentionally replaced because it incorrectly merged
-- positions across calendar days and prevented a new daily position.
-- Application date boundaries use UTC to match this index.
