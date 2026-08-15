import os, subprocess, sys, json

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
SELECT name, status,
  left(coalesce(config::text,''), 400) as config_snip,
  left(coalesce(prompt,''), 200) as prompt_snip
FROM strategies
WHERE instrument = 'B-BTC_USDT'
ORDER BY created_at DESC
LIMIT 3;
"""
r = subprocess.run(["psql", conn, "-t", "-A", "-F", "|", "-c", sql], capture_output=True, text=True)
print(r.stdout)
print(r.stderr, file=sys.stderr)
sys.exit(r.returncode)
