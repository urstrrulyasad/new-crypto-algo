#!/bin/bash
TO=$(date -u +%s)
FROM=$((TO-900))
echo "FROM=$FROM TO=$TO"
curl -sS "https://public.coindcx.com/market_data/candlesticks?pair=B-ARX_USDT&from=${FROM}&to=${TO}&resolution=1&pcode=f" | head -c 600
echo
docker exec crypto-algo-backend-1 wget -qO- "https://public.coindcx.com/market_data/candlesticks?pair=B-ARX_USDT&from=${FROM}&to=${TO}&resolution=1&pcode=f" 2>&1 | head -c 600
echo
docker logs crypto-algo-backend-1 --since 15m 2>&1 | grep -iE 'candle|lastPrice|Futures candle' | tail -20
