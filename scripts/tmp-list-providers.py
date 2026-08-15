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
SELECT id, provider_type, enabled, priority,
       CASE WHEN api_key_enc IS NULL OR length(api_key_enc)=0 THEN 'empty' ELSE 'set' END as key_status,
       created_at
FROM ai_providers
ORDER BY priority ASC, created_at ASC;
"""
r = subprocess.run(["psql", conn, "-c", sql], capture_output=True, text=True)
sys.stdout.write(r.stdout)
sys.stderr.write(r.stderr)
sys.exit(r.returncode)
