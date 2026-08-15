#!/bin/bash
set -e
echo "=== PositionGuard in jar ==="
docker exec crypto-algo-backend-1 sh -c 'unzip -l app.jar | grep -i PositionGuard || true'
echo "=== XRP candle via engine ==="
docker exec crypto-algo-strategy-engine-1 python - <<'PY'
import urllib.request
url = "https://public.coindcx.com/market_data/candlesticks?pair=B-XRP_USDT&from=1785629400&to=1785629600&resolution=1&pcode=f"
print(urllib.request.urlopen(url, timeout=15).read()[:400])
PY
echo "=== recent backend errors ==="
docker logs crypto-algo-backend-1 2>&1 | grep -iE 'Guard|PAPER_EXIT|closePaper|WebClient|candle' | tail -30 || true
