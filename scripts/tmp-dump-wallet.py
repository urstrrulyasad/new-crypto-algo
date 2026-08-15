#!/usr/bin/env python3
"""Dump CoinDCX futures wallets + positions via backend internal helpers isn't available;
call through docker logs after hitting a small Java path — instead use curl to API with token from env.
"""
import json, os, subprocess, urllib.request

token = subprocess.check_output(
    ["docker", "exec", "crypto-algo-backend-1", "printenv", "INTERNAL_TOKEN"],
    text=True,
).strip()
req = urllib.request.Request(
    "http://127.0.0.1:8080/api/v1/internal/exchange-positions",
    headers={"X-Internal-Token": token},
)
with urllib.request.urlopen(req, timeout=30) as r:
    print(r.read().decode())
