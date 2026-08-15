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
SELECT p.id, p.pair, p.side, p.status, p.opened_at, p.sl_price, p.target_price,
       b.mode, b.status AS bot_status, length(b.mode) AS mode_len,
       now() AS db_now, now() - p.opened_at AS age
FROM positions p
JOIN bots b ON b.id = p.bot_id
WHERE p.status = 'OPEN';
"""
r = subprocess.run(["psql", conn, "-c", sql], capture_output=True, text=True)
sys.stdout.write(r.stdout)
sys.stderr.write(r.stderr)
sys.exit(r.returncode)
