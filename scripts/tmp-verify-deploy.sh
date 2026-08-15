#!/bin/sh
set -e
cd /tmp
rm -rf jarprobe
mkdir jarprobe
cd jarprobe
docker cp crypto-algo-backend-1:/app/app.jar ./app.jar
jar xf app.jar BOOT-INF/classes/com/cryptoalgo/backend/trading/CoinDcxFuturesClient.class \
  BOOT-INF/classes/com/cryptoalgo/backend/trading/ExecutionService.class
python3 - <<'PY'
for p in [
  "BOOT-INF/classes/com/cryptoalgo/backend/trading/CoinDcxFuturesClient.class",
  "BOOT-INF/classes/com/cryptoalgo/backend/trading/ExecutionService.class",
]:
    b=open(p,"rb").read()
    print(p.split("/")[-1],
          "maxLev", b"maxLeverageFromDynamic" in b,
          "dyn", b"dynamic_position_leverage_details" in b,
          "compact", b"compactClientOrderId" in b,
          "caf", b"caf-" in b,
          "minQty", b"minQty=" in b)
PY
echo "--- recent LIVE logs ---"
docker logs crypto-algo-backend-1 --since 40m 2>&1 | grep -E "skipped LIVE|LIVE futures|placeOrder|minQty=|compact|value too long|BadSql|FILLED|SUBMITTING|caf-" | tail -40
