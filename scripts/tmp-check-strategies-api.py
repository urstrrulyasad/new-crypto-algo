#!/usr/bin/env python3
import json, os, sys, time, urllib.request

def load_env(path):
    env = {}
    with open(path, encoding="utf-8", errors="ignore") as f:
        for line in f:
            line = line.replace("\r", "").strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            env[k] = v
    return env

base = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080"
env = load_env(os.path.expanduser("~/crypto-algo/.env.gcp"))
email = env.get("SUPERADMIN_EMAIL", "admin@platform.local")
password = env["SUPERADMIN_PASSWORD"]

def post(url, data, headers=None):
    req = urllib.request.Request(url, data=json.dumps(data).encode(), headers={
        "Content-Type": "application/json", **(headers or {})
    }, method="POST")
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read().decode())

def get(url, token):
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"})
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            body = r.read()
            return r.status, time.time() - t0, body
    except Exception as e:
        return getattr(e, "code", 0), time.time() - t0, str(e).encode()

login = post(f"{base}/api/v1/auth/login", {"email": email, "password": password})
token = login.get("accessToken") or login.get("access_token")
print("login_ok", bool(token), "token_len", len(token or ""))
status, elapsed, body = get(f"{base}/api/v1/strategies?marketType=FUTURES", token)
print(f"strategies={status} time={elapsed:.3f}s size={len(body)}")
try:
    data = json.loads(body)
    if isinstance(data, list):
        print("count", len(data))
        print("sourceCode_chars", sum(len(x.get("sourceCode") or "") for x in data))
        print("prompt_chars", sum(len(x.get("prompt") or "") for x in data))
        st = {}
        for x in data:
            st[x.get("status")] = st.get(x.get("status"), 0) + 1
        print("statuses", st)
    else:
        print("body", str(data)[:500])
except Exception as e:
    print("parse", e, body[:300])
