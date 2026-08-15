#!/bin/bash
set -euo pipefail
NEW_JAR=/tmp/backend-0.0.1-SNAPSHOT.jar
echo "New jar size: $(wc -c < "$NEW_JAR")"
docker cp "$NEW_JAR" crypto-algo-backend-1:/app/app.jar
docker restart crypto-algo-backend-1
sleep 8
docker ps --filter name=crypto-algo-backend-1 --format '{{.Names}} {{.Status}}'
python3 - <<'PY'
import zipfile
z=zipfile.ZipFile('/tmp/backend-0.0.1-SNAPSHOT.jar')
for name in [
 'BOOT-INF/classes/com/cryptoalgo/backend/trading/CoinDcxFuturesClient.class',
 'BOOT-INF/classes/com/cryptoalgo/backend/trading/ExecutionService.class',
]:
    b=z.read(name)
    print(name.split('/')[-1],
          'maxLev', b'maxLeverageFromDynamic' in b,
          'dyn', b'dynamic_position_leverage_details' in b,
          'compact', b'compactClientOrderId' in b,
          'caf', b'caf-' in b,
          'minQty', b'minQty=' in b)
PY
echo "waiting for healthy logs..."
sleep 12
docker logs crypto-algo-backend-1 --since 30s 2>&1 | tail -25
