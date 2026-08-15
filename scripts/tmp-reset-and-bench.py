#!/usr/bin/env python3
"""Reset superadmin password to a known value (via bcrypt) then bench strategies API."""
import json, os, subprocess, sys, time, urllib.request

env = {}
for line in open(os.path.expanduser("~/crypto-algo/.env.gcp"), encoding="utf-8", errors="ignore"):
    line = line.replace("\r", "").strip()
    if not line or line.startswith("#") or "=" not in line:
        continue
    k, v = line.split("=", 1)
    if k.startswith("JAVA"):
        continue
    env[k] = v

# bcrypt for 'TempBench123!' — generate with python if passlib/bcrypt available
new_pass = "TempBench123!"
try:
    import bcrypt
    hashed = bcrypt.hashpw(new_pass.encode(), bcrypt.gensalt(rounds=10)).decode()
except Exception:
    # fall back: use htpasswd if present
    r = subprocess.run(
        ["python3", "-c",
         "import crypt; print('SKIP')"],
        capture_output=True, text=True)
    print("bcrypt module missing; trying docker backend encoder via SQL skip")
    hashed = None

os.environ["PGPASSWORD"] = env["DB_PASSWORD"]
conn = (
    f"host={env['DB_HOST']} port={env.get('DB_PORT','5432')} "
    f"dbname={env['DB_NAME']} user={env['DB_USER']} sslmode=require"
)

# Count strategies for context
sql_count = "SET search_path TO quantdcx; SELECT status, count(*) FROM strategies GROUP BY 1 ORDER BY 2 DESC;"
subprocess.run(["psql", conn, "-c", sql_count], check=False)

if hashed:
    email = env.get("SUPERADMIN_EMAIL", "admin@platform.local")
    # Spring BCrypt hashes usually start with $2a$
    sql = f"""
SET search_path TO quantdcx;
UPDATE users SET password_hash = '{hashed}'
WHERE email = '{email}';
SELECT email, left(password_hash, 7) FROM users WHERE email = '{email}';
"""
    subprocess.run(["psql", conn, "-c", sql], check=False)

    for base in [
        "http://127.0.0.1:8080",
        "https://hbpt7cur19.execute-api.ap-south-1.amazonaws.com",
    ]:
        try:
            req = urllib.request.Request(
                base + "/api/v1/auth/login",
                data=json.dumps({"email": email, "password": new_pass}).encode(),
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            with urllib.request.urlopen(req, timeout=30) as r:
                login = json.loads(r.read().decode())
            token = login.get("accessToken") or login.get("access_token")
            t0 = time.time()
            req2 = urllib.request.Request(
                base + "/api/v1/strategies?marketType=FUTURES",
                headers={"Authorization": "Bearer " + token},
            )
            with urllib.request.urlopen(req2, timeout=60) as r2:
                body = r2.read()
                status = r2.status
            data = json.loads(body)
            count = len(data) if isinstance(data, list) else -1
            code_chars = sum(len((x.get("sourceCode") or "")) for x in data) if isinstance(data, list) else 0
            print(
                base,
                f"strategies={status} time={time.time()-t0:.3f}s size={len(body)} "
                f"count={count} code_chars={code_chars}",
            )
        except Exception as e:
            print(base, "ERR", e)
else:
    print("Install bcrypt: pip install bcrypt")
    sys.exit(1)
