#!/usr/bin/env python3
"""Remove duplicate REJECTED strategies, keeping the newest per (name, instrument)."""
import os
import subprocess
import sys

ENV_PATH = os.path.expanduser("~/crypto-algo/.env.gcp")


def load_env(path: str) -> dict:
    env = {}
    with open(path, encoding="utf-8", errors="ignore") as f:
        for line in f:
            line = line.replace("\r", "").strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            env[k] = v
    return env


def main() -> int:
    env = load_env(ENV_PATH)
    os.environ["PGPASSWORD"] = env["DB_PASSWORD"]
    conn = (
        f"host={env['DB_HOST']} port={env.get('DB_PORT', '5432')} "
        f"dbname={env['DB_NAME']} user={env['DB_USER']} sslmode=require"
    )
    sql = r"""
SET search_path TO quantdcx;
BEGIN;

CREATE TEMP TABLE doomed AS
WITH ranked AS (
  SELECT id,
         ROW_NUMBER() OVER (
           PARTITION BY name, instrument
           ORDER BY created_at DESC, id DESC
         ) AS rn
  FROM strategies
  WHERE status = 'REJECTED'
)
SELECT id FROM ranked WHERE rn > 1;

-- Detach self-references before delete
UPDATE strategies SET parent_id = NULL
WHERE parent_id IN (SELECT id FROM doomed);

-- Dependent rows (bots/signals have no ON DELETE CASCADE)
DELETE FROM orders
WHERE bot_id IN (SELECT id FROM bots WHERE strategy_id IN (SELECT id FROM doomed));

DELETE FROM positions
WHERE bot_id IN (SELECT id FROM bots WHERE strategy_id IN (SELECT id FROM doomed));

DELETE FROM signals
WHERE strategy_id IN (SELECT id FROM doomed);

DELETE FROM bots
WHERE strategy_id IN (SELECT id FROM doomed);

-- backtests cascade, but delete explicitly for clarity
DELETE FROM backtests
WHERE strategy_id IN (SELECT id FROM doomed);

DELETE FROM strategies
WHERE id IN (SELECT id FROM doomed);

SELECT count(*) AS deleted FROM doomed;

SELECT status, count(*) FROM strategies GROUP BY 1 ORDER BY 2 DESC;

SELECT name, instrument, count(*) AS n
FROM strategies
WHERE status = 'REJECTED'
GROUP BY name, instrument
HAVING count(*) > 1;

COMMIT;
"""
    r = subprocess.run(["psql", conn, "-v", "ON_ERROR_STOP=1", "-c", sql], capture_output=True, text=True)
    sys.stdout.write(r.stdout)
    sys.stderr.write(r.stderr)
    return r.returncode


if __name__ == "__main__":
    raise SystemExit(main())
