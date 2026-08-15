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
    f"host={env['DB_HOST']} port={env.get('DB_PORT', '5432')} "
    f"dbname={env['DB_NAME']} user={env['DB_USER']} sslmode=require"
)
sql = """
SET search_path TO quantdcx;
SELECT status, count(*) FROM strategies GROUP BY 1 ORDER BY 2 DESC;
SELECT name, status, instrument, created_at
FROM strategies
WHERE instrument IN ('B-BTC_USDT','B-ETH_USDT','B-SOL_USDT')
ORDER BY created_at DESC
LIMIT 25;
"""
r = subprocess.run(["psql", conn, "-c", sql], capture_output=True, text=True)
sys.stdout.write(r.stdout)
sys.stderr.write(r.stderr)
sys.exit(r.returncode)
