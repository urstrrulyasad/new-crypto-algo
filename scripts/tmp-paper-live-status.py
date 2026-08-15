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
SELECT status, count(*) FROM strategies GROUP BY 1 ORDER BY 2 DESC;

SELECT s.name, s.status,
       (s.config::json->>'model_used') AS model,
       (bt.metrics::json->>'trades') AS bt_trades,
       (bt.metrics::json->>'win_rate') AS bt_wr,
       (bt.metrics::json->>'profit_total_pct') AS bt_profit,
       (bt.metrics::json->>'profit_factor') AS bt_pf,
       (bt.metrics::json->>'max_drawdown_pct') AS bt_dd,
       COALESCE(ps.closed,0) AS paper_closed,
       COALESCE(ps.wins,0) AS paper_wins
FROM strategies s
LEFT JOIN LATERAL (
  SELECT metrics FROM backtests b
  WHERE b.strategy_id = s.id AND b.status='DONE'
  ORDER BY b.created_at DESC LIMIT 1
) bt ON true
LEFT JOIN LATERAL (
  SELECT count(*) FILTER (WHERE p.status='CLOSED') AS closed,
         count(*) FILTER (WHERE p.status='CLOSED' AND p.realized_pnl > 0) AS wins
  FROM positions p
  JOIN bots b ON b.id = p.bot_id
  WHERE b.strategy_id = s.id AND b.mode='PAPER'
) ps ON true
WHERE s.status IN ('PAPER_TRADING','LIVE_APPROVED','BACKTESTED')
ORDER BY s.status, s.name
LIMIT 40;

SELECT b.name, b.mode, b.status, b.stake_amount, s.status AS strat_status
FROM bots b JOIN strategies s ON s.id = b.strategy_id
WHERE b.mode='LIVE' OR s.status='LIVE_APPROVED'
ORDER BY b.created_at DESC LIMIT 20;
"""
r = subprocess.run(["psql", conn, "-c", sql], capture_output=True, text=True)
sys.stdout.write(r.stdout)
sys.stderr.write(r.stderr)
sys.exit(r.returncode)
