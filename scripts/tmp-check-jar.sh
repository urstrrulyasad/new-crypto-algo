#!/bin/sh
set -e
echo "jar mtime:"
ls -la /app/app.jar
echo "markers:"
for s in maxLeverageFromDynamic "minQty=" compactClientOrderId "caf-" "Could not persist FAILED"; do
  n=$(grep -a -F "$s" /app/app.jar | wc -c)
  echo "$s -> $n"
done
