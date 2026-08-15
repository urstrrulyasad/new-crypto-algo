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

-- Duplicate REJECTED by exact name
SELECT name, instrument, count(*) AS n,
       min(created_at) AS first_seen, max(created_at) AS last_seen
FROM strategies
WHERE status = 'REJECTED'
GROUP BY name, instrument
HAVING count(*) > 1
ORDER BY n DESC, name
LIMIT 40;

SELECT
  (SELECT count(*) FROM strategies WHERE status = 'REJECTED') AS rejected_total,
  (SELECT count(*) FROM (
     SELECT name, instrument FROM strategies
     WHERE status = 'REJECTED'
     GROUP BY name, instrument HAVING count(*) > 1
  ) d) AS dup_groups,
  (SELECT coalesce(sum(c - 1), 0) FROM (
     SELECT count(*) AS c FROM strategies
     WHERE status = 'REJECTED'
     GROUP BY name, instrument HAVING count(*) > 1
  ) x) AS rows_to_delete;
"""
r = subprocess.run(["psql", conn, "-c", sql], capture_output=True, text=True)
sys.stdout.write(r.stdout)
sys.stderr.write(r.stderr)
sys.exit(r.returncode)
