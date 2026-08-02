#!/usr/bin/env bash
set -euo pipefail
hosts=(
  aws-0-ap-south-1.pooler.supabase.com
  aws-1-ap-south-1.pooler.supabase.com
  aws-0-ap-southeast-1.pooler.supabase.com
  aws-0-us-east-1.pooler.supabase.com
  aws-0-eu-central-1.pooler.supabase.com
)
for h in "${hosts[@]}"; do
  echo "== $h =="
  getent ahostsv4 "$h" | head -5 || true
  python3 - <<PY
import socket
h="$h"
try:
  print("v4", socket.getaddrinfo(h, 5432, socket.AF_INET)[0][4][0])
  s=socket.create_connection((socket.getaddrinfo(h,5432,socket.AF_INET)[0][4][0],5432),5)
  print("connect 5432 OK"); s.close()
except Exception as e:
  print("5432", e)
try:
  s=socket.create_connection((socket.getaddrinfo(h,6543,socket.AF_INET)[0][4][0],6543),5)
  print("connect 6543 OK"); s.close()
except Exception as e:
  print("6543", e)
PY
done
