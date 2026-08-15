#!/bin/bash
set -euo pipefail
cd ~/crypto-algo
set -a
# shellcheck disable=SC1091
source .env.gcp
set +a
export PGPASSWORD="$DB_PASSWORD"
# Close stuck OPEN paper positions at their target (SHORT profit) or entry.
psql "host=${DB_HOST} port=${DB_PORT:-5432} dbname=${DB_NAME} user=${DB_USER} sslmode=${DB_SSL_MODE:-require}" <<'SQL'
SET search_path TO quantdcx;
UPDATE positions p
SET status = 'CLOSED',
    exit_price = COALESCE(p.target_price, p.entry_price),
    realized_pnl = CASE
      WHEN p.side = 'SHORT' THEN (p.entry_price - COALESCE(p.target_price, p.entry_price)) * p.quantity * COALESCE(p.leverage, 1)
      ELSE (COALESCE(p.target_price, p.entry_price) - p.entry_price) * p.quantity * COALESCE(p.leverage, 1)
    END,
    closed_at = now()
FROM bots b
WHERE p.bot_id = b.id
  AND p.status = 'OPEN'
  AND b.mode = 'PAPER'
  AND p.opened_at < now() - interval '6 hours';
SELECT status, count(*) FROM positions GROUP BY status;
SQL
