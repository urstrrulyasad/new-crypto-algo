#!/usr/bin/env python3
import json, urllib.request

BASE = "http://127.0.0.1:8080"
login = json.dumps({"email": "admin@platform.local", "password": "Asad@1996"}).encode()
req = urllib.request.Request(
    BASE + "/api/v1/auth/login",
    data=login,
    headers={"Content-Type": "application/json"},
    method="POST",
)
with urllib.request.urlopen(req, timeout=30) as r:
    tok = json.load(r)
access = tok.get("accessToken") or tok.get("access_token") or tok.get("token")
print("token keys", list(tok.keys()))
headers = {"Authorization": "Bearer " + access}
for path in [
    "/api/v1/portfolio/positions?mode=LIVE",
    "/api/v1/portfolio/summary?mode=LIVE",
    "/api/v1/portfolio/orders?mode=LIVE",
]:
    req = urllib.request.Request(BASE + path, headers=headers)
    with urllib.request.urlopen(req, timeout=60) as r:
        body = r.read().decode()
    print("====", path)
    print(body[:2500])
