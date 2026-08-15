#!/usr/bin/env python3
import json, os, time, urllib.request

env = {}
for line in open(os.path.expanduser("~/crypto-algo/.env.gcp"), encoding="utf-8", errors="ignore"):
    line = line.replace("\r", "").strip()
    if not line or line.startswith("#") or "=" not in line:
        continue
    k, v = line.split("=", 1)
    if k.startswith("JAVA"):
        continue
    env[k] = v

email = env.get("SUPERADMIN_EMAIL", "admin@platform.local")
password = env.get("SUPERADMIN_PASSWORD", "")
print("email", email, "pass_len", len(password))

for base in [
    "http://127.0.0.1:8080",
    "https://hbpt7cur19.execute-api.ap-south-1.amazonaws.com",
]:
    try:
        req = urllib.request.Request(
            base + "/api/v1/auth/login",
            data=json.dumps({"email": email, "password": password}).encode(),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=30) as r:
            login = json.loads(r.read().decode())
        token = login.get("accessToken") or login.get("access_token")
        print(base, "login", "ok" if token else "no-token")
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
        code_chars = (
            sum(len(x.get("sourceCode") or "") for x in data) if isinstance(data, list) else 0
        )
        print(
            base,
            f"strategies={status} time={time.time() - t0:.3f}s size={len(body)} "
            f"count={count} code_chars={code_chars}",
        )
    except Exception as e:
        print(base, "ERR", type(e).__name__, e)
