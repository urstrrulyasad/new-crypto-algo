#!/usr/bin/env python3
import base64, json, os, subprocess, sys, time, urllib.request

try:
    import jwt
except ImportError:
    subprocess.check_call([sys.executable, "-m", "pip", "install", "--user", "-q", "PyJWT"])
    import jwt

env = {}
for line in open(os.path.expanduser("~/crypto-algo/.env.gcp"), encoding="utf-8", errors="ignore"):
    line = line.replace("\r", "").strip()
    if not line or line.startswith("#") or "=" not in line:
        continue
    k, v = line.split("=", 1)
    if k.startswith("JAVA"):
        continue
    env[k] = v

os.environ["PGPASSWORD"] = env["DB_PASSWORD"]
conn = (
    f"host={env['DB_HOST']} port={env.get('DB_PORT','5432')} "
    f"dbname={env['DB_NAME']} user={env['DB_USER']} sslmode=require"
)
sql = """
SET search_path TO quantdcx;
SELECT status, count(*) FROM strategies GROUP BY 1 ORDER BY 2 DESC;
SELECT count(*) AS total, coalesce(sum(octet_length(source_code)),0) AS code_bytes FROM strategies;
SELECT id::text, tenant_id::text, email, role FROM users ORDER BY created_at LIMIT 3;
"""
r = subprocess.run(["psql", conn, "-t", "-A", "-F", "|", "-c", sql], capture_output=True, text=True)
print(r.stdout)
print(r.stderr, file=sys.stderr)

# last query rows: id|tenant|email|role
user = None
for line in r.stdout.strip().splitlines():
    parts = line.split("|")
    if len(parts) == 4 and "@" in parts[2]:
        user = parts
        break
if not user:
    print("No user found")
    sys.exit(1)

uid, tid, email, role = user
secret = base64.b64decode(env["JWT_SECRET"])
now = int(time.time())
token = jwt.encode(
    {
        "sub": uid,
        "tenantId": tid,
        "email": email,
        "role": role,
        "iat": now,
        "exp": now + 3600,
    },
    secret,
    algorithm="HS256",
)
print("minted for", email, "role", role)

for base in [
    "http://127.0.0.1:8080",
    "https://hbpt7cur19.execute-api.ap-south-1.amazonaws.com",
]:
    t0 = time.time()
    req = urllib.request.Request(
        base + "/api/v1/strategies?marketType=FUTURES",
        headers={"Authorization": "Bearer " + token},
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            body = resp.read()
            status = resp.status
        data = json.loads(body)
        count = len(data) if isinstance(data, list) else -1
        code_chars = sum(len(x.get("sourceCode") or "") for x in data) if isinstance(data, list) else 0
        print(
            f"{base} strategies={status} time={time.time()-t0:.3f}s "
            f"size={len(body)} count={count} code_chars={code_chars}"
        )
    except Exception as e:
        print(base, "ERR", type(e).__name__, e, f"time={time.time()-t0:.3f}s")
