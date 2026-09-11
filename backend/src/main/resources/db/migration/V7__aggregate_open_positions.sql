-- One open projection per bot, futures pair, and direction.
-- Orders remain separate for audit and reconciliation.
CREATE UNIQUE INDEX IF NOT EXISTS uq_positions_open_bot_pair_side
    ON positions (tenant_id, user_id, bot_id, pair, side)
    WHERE status = 'OPEN';

CREATE INDEX IF NOT EXISTS idx_positions_open_bot_pair_side
    ON positions (bot_id, pair, side)
    WHERE status = 'OPEN';
