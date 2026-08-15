#!/usr/bin/env python3
"""Close LIVE ARX test position via EXIT_LONG signal."""
import json
import subprocess
import time
import urllib.request
from datetime import datetime, timezone

TOKEN = "3e7874ffa34b293a762d2fc32eb1de37b7ca007e947d794a5336c52f891bbd2f"
STRATEGY_ID = "cc67164e-4d86-4f13-a698-0e0b69040137"
PAIR = "B-ARX_USDT"

to = int(time.time())
frm = to - 600
cand_url = (
    f"https://public.coindcx.com/market_data/candlesticks"
    f"?pair={PAIR}&from={frm}&to={to}&resolution=1&pcode=f"
)
req = urllib.request.Request(cand_url, headers={"User-Agent": "Mozilla/5.0"})
with urllib.request.urlopen(req, timeout=20) as r:
    data = json.load(r)
px = float((data.get("data") or [])[-1]["close"])
print("mark", px)

body = {
    "tenantId": "00000000-0000-0000-0000-000000000000",
    "strategyId": STRATEGY_ID,
    "idempotencyKey": f"live-close-arx-{int(time.time())}",
    "pair": PAIR,
    "timeframe": "1m",
    "action": "EXIT_LONG",
    "price": px,
    "candleTs": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    "payload": {"verify": True, "catchup": False, "manualClose": True},
}
cmd = [
    "docker",
    "exec",
    "crypto-algo-backend-1",
    "curl",
    "-sS",
    "-X",
    "POST",
    "-H",
    "Content-Type: application/json",
    "-H",
    f"X-Internal-Token: {TOKEN}",
    "-d",
    json.dumps(body),
    "http://127.0.0.1:8080/api/v1/internal/signals",
]
print(subprocess.check_output(cmd, text=True))
