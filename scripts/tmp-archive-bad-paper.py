import os, subprocess, sys

env = {}
with open(os.path.expanduser("~/crypto-algo/.env.gcp"), encoding="utf-8", errors="ignore") as f:
    for line in f:
        line = line.replace("\r", "").strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        env[k] = v
os.environ["PGPASSWORD"] = env["DB_PASSWORD"]
conn = (
    f"host={env['DB_HOST']} port={env.get('DB_PORT','5432')} "
    f"dbname={env['DB_NAME']} user={env['DB_USER']} sslmode=require"
)
sql = r"""
SET search_path TO quantdcx;
-- Stop paper bots for PAPER_TRADING strategies with losing backtests
UPDATE bots b
SET status = 'STOPPED', updated_at = now()
FROM strategies s
JOIN LATERAL (
  SELECT (metrics::json->>'profit_total_pct')::float AS profit,
         (metrics::json->>'win_rate')::float AS wr,
         (metrics::json->>'profit_factor')::float AS pf
  FROM backtests bt
  WHERE bt.strategy_id = s.id AND bt.status = 'DONE' AND bt.metrics IS NOT NULL
  ORDER BY bt.created_at DESC LIMIT 1
) m ON true
WHERE b.strategy_id = s.id
  AND b.mode = 'PAPER' AND b.status = 'RUNNING'
  AND s.status = 'PAPER_TRADING'
  AND (m.profit < 0 OR m.wr < 0.50 OR m.pf < 0.95);

UPDATE strategies s
SET status = 'ARCHIVED'
FROM LATERAL (
  SELECT (metrics::json->>'profit_total_pct')::float AS profit,
         (metrics::json->>'win_rate')::float AS wr,
         (metrics::json->>'profit_factor')::float AS pf
  FROM backtests bt
  WHERE bt.strategy_id = s.id AND bt.status = 'DONE' AND bt.metrics IS NOT NULL
  ORDER BY bt.created_at DESC LIMIT 1
) m
WHERE s.status = 'PAPER_TRADING'
  AND (m.profit < 0 OR m.wr < 0.50 OR m.pf < 0.95);

SELECT status, count(*) FROM strategies GROUP BY 1 ORDER BY 2 DESC;
SELECT name, status FROM strategies WHERE status = 'PAPER_TRADING';
"""
r = subprocess.run(["psql", conn, "-v", "ON_ERROR_STOP=1", "-c", sql], capture_output=True, text=True)
sys.stdout.write(r.stdout)
sys.stderr.write(r.stderr)
sys.exit(r.returncode)
