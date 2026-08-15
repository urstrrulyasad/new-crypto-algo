import os
import subprocess
import sys

env = {}
with open(os.path.expanduser("~/crypto-algo/.env.gcp"), "r", encoding="utf-8", errors="ignore") as f:
    for line in f:
        line = line.replace("\r", "").strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        env[k] = v

os.environ["PGPASSWORD"] = env["DB_PASSWORD"]
conn = (
    f"host={env['DB_HOST']} port={env.get('DB_PORT', '5432')} "
    f"dbname={env['DB_NAME']} user={env['DB_USER']} sslmode=require"
)
sql = """
SET search_path TO quantdcx;
UPDATE positions p
SET status = 'CLOSED',
    exit_price = COALESCE(p.target_price, p.entry_price),
    realized_pnl = CASE
      WHEN p.side = 'SHORT' THEN (p.entry_price - COALESCE(p.target_price, p.entry_price))
           * p.quantity * COALESCE(p.leverage, 1)
      ELSE (COALESCE(p.target_price, p.entry_price) - p.entry_price)
           * p.quantity * COALESCE(p.leverage, 1)
    END,
    closed_at = now()
FROM bots b
WHERE p.bot_id = b.id
  AND p.status = 'OPEN'
  AND b.mode = 'PAPER'
  AND p.opened_at < now() - interval '6 hours';
SELECT status, count(*) FROM positions GROUP BY status ORDER BY 1;
"""
print("connecting", env["DB_HOST"], env["DB_NAME"], env["DB_USER"])
r = subprocess.run(["psql", conn, "-v", "ON_ERROR_STOP=1", "-c", sql], capture_output=True, text=True)
sys.stdout.write(r.stdout)
sys.stderr.write(r.stderr)
sys.exit(r.returncode)
