#!/usr/bin/env python3
"""Post a non-catchup LIVE ENTRY signal for TAG to verify sizing/order path."""
import json
import time
import urllib.request
from datetime import datetime, timezone

TOKEN = "3e7874ffa34b293a762d2fc32eb1de37b7ca007e947d794a5336c52f891bbd2f"
# Prefer in-container call; this script runs on EC2 host against localhost published port or docker network.
BASE = "http://127.0.0.1:8080"
# Prefer ARX — TAG may still have an orphan exchange fill from the null-id bug.
STRATEGY_ID = "cc67164e-4d86-4f13-a698-0e0b69040137"
TENANT_ID = None  # filled from backend via strategy lookup if needed
PAIR = "B-ARX_USDT"

# mark price from CoinDCX public candles
to = int(time.time())
frm = to - 600
cand_url = (
    f"https://public.coindcx.com/market_data/candlesticks"
    f"?pair={PAIR}&from={frm}&to={to}&resolution=1&pcode=f"
)
req = urllib.request.Request(cand_url, headers={"User-Agent": "Mozilla/5.0"})
with urllib.request.urlopen(req, timeout=20) as r:
    data = json.load(r)
bars = data.get("data") or []
px = float(bars[-1]["close"])
print("mark", px)

body = {
    "tenantId": "00000000-0000-0000-0000-000000000000",  # controller resolves from strategy
    "strategyId": STRATEGY_ID,
    "idempotencyKey": f"live-verify-tag-{int(time.time())}",
    "pair": PAIR,
    "timeframe": "1m",
    "action": "ENTRY_LONG",
    "price": px,
    "candleTs": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    "payload": {"verify": True, "catchup": False},
}

# Use docker exec curl into backend network
import subprocess
cmd = [
    "docker",
    "exec",
    "crypto-algo-backend-1",
    "wget",
    "-qO-",
    "--header=Content-Type: application/json",
    f"--header=X-Internal-Token: {TOKEN}",
    f"--post-data={json.dumps(body)}",
    "http://127.0.0.1:8080/api/v1/internal/signals",
]
# wget may not exist; try curl
cmd = [
    "docker",
    "exec",
    "crypto-algo-backend-1",
    "sh",
    "-c",
    "command -v curl >/dev/null && curl -sS -X POST "
    "-H 'Content-Type: application/json' "
    f"-H 'X-Internal-Token: {TOKEN}' "
    f"-d '{json.dumps(body)}' "
    "http://127.0.0.1:8080/api/v1/internal/signals || "
    "wget -qO- "
    "--header='Content-Type: application/json' "
    f"--header='X-Internal-Token: {TOKEN}' "
    f"--post-data='{json.dumps(body)}' "
    "http://127.0.0.1:8080/api/v1/internal/signals",
]
print("posting…")
out = subprocess.check_output(cmd, text=True)
print(out)
