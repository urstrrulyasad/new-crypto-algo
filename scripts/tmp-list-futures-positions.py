#!/usr/bin/env python3
"""List CoinDCX INR futures positions using platform encrypted keys (EC2 only)."""
import base64
import hashlib
import hmac
import json
import os
import time
import urllib.request

import psycopg2
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ENV_PATH = "/home/ubuntu/crypto-algo/.env"


def load_env(path):
    out = {}
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip().strip('"').strip("'")
    return out


def decrypt(master_b64: str, encoded: str) -> str:
    key = base64.b64decode(master_b64)
    raw = base64.b64decode(encoded)
    iv, ct = raw[:12], raw[12:]
    return AESGCM(key).decrypt(iv, ct, None).decode()


def signed_post(path, body, api_key, api_secret):
    payload = json.dumps(body, separators=(",", ":"))
    sig = hmac.new(api_secret.encode(), payload.encode(), hashlib.sha256).hexdigest()
    req = urllib.request.Request(
        "https://api.coindcx.com" + path,
        data=payload.encode(),
        headers={
            "Content-Type": "application/json",
            "X-AUTH-APIKEY": api_key,
            "X-AUTH-SIGNATURE": sig,
            "User-Agent": "Mozilla/5.0",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def main():
    env = load_env(ENV_PATH)
    conn = psycopg2.connect(
        host=env["DB_HOST"],
        port=env.get("DB_PORT", "5432"),
        dbname=env["DB_NAME"],
        user=env["DB_USER"],
        password=env["DB_PASSWORD"],
        sslmode=env.get("DB_SSL_MODE", "require"),
    )
    cur = conn.cursor()
    cur.execute(
        "SELECT api_key_enc, api_secret_enc FROM quantdcx.exchange_keys WHERE status='ACTIVE' LIMIT 1"
    )
    row = cur.fetchone()
    conn.close()
    if not row:
        raise SystemExit("no active exchange key")
    api_key = decrypt(env["MASTER_KEY"], row[0])
    api_secret = decrypt(env["MASTER_KEY"], row[1])
    ts = int(time.time() * 1000)
    positions = signed_post(
        "/exchange/v1/derivatives/futures/positions",
        {
            "timestamp": ts,
            "page": "1",
            "size": "50",
            "margin_currency_short_name": ["INR"],
        },
        api_key,
        api_secret,
    )
    wallets = signed_post(
        "/exchange/v1/derivatives/futures/wallets",
        {"timestamp": int(time.time() * 1000)},
        api_key,
        api_secret,
    ) if False else None
    # wallets is GET in our client — use GET-style signed via POST if needed; skip wallet for now
    active = []
    for p in positions if isinstance(positions, list) else []:
        ap = float(p.get("active_pos") or 0)
        if ap != 0:
            active.append(
                {
                    "pair": p.get("pair"),
                    "active_pos": ap,
                    "avg_price": p.get("avg_price"),
                    "locked_margin": p.get("locked_margin") or p.get("margin"),
                    "leverage": p.get("leverage"),
                }
            )
    print(json.dumps({"active_count": len(active), "active": active, "raw_count": len(positions) if isinstance(positions, list) else 0}, indent=2))


if __name__ == "__main__":
    main()
