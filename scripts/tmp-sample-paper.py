#!/usr/bin/env python3
import json
import urllib.request

BASE = "https://hbpt7cur19.execute-api.ap-south-1.amazonaws.com"


def req(method, path, token=None, body=None):
    data = None if body is None else json.dumps(body).encode()
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    r = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    with urllib.request.urlopen(r, timeout=60) as resp:
        return json.load(resp)


login = req("POST", "/api/v1/auth/login", body={"email": "admin@platform.local", "password": "Asad@1996"})
token = login["accessToken"]
pos = req("GET", "/api/v1/portfolio/positions?mode=PAPER", token=token)
print("positions", len(pos))
if pos:
    print(json.dumps(pos[0], indent=2, default=str))
strats = req("GET", "/api/v1/strategies?marketType=FUTURES", token=token)
paper = [s for s in strats if s.get("status") in ("PAPER_TRADING", "LIVE_APPROVED")]
print("paper strats", len(paper))
if paper:
    s = paper[0]
    print("strat", s["id"], s["name"])
    trades = req("GET", f"/api/v1/strategies/{s['id']}/trades?mode=PAPER", token=token)
    print("trades", len(trades))
    if trades:
        print(json.dumps(trades[0], indent=2, default=str))
