#!/bin/bash
set -euo pipefail
cd ~/crypto-algo
# shellcheck disable=SC1091
set -a; source .env.gcp; set +a
EMAIL="${SUPERADMIN_EMAIL:-admin@platform.local}"
PASS="${SUPERADMIN_PASSWORD}"
BASE="${1:-http://127.0.0.1:8080}"

login=$(curl -sS -X POST "$BASE/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASS\"}")
token=$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("accessToken") or json.load(open("/dev/stdin")).get("accessToken",""))' <<<"$login" 2>/dev/null || true)
if [ -z "$token" ]; then
  token=$(python3 - <<PY
import json
print(json.loads('''$login''').get("accessToken",""))
PY
)
fi
echo "token_len=${#token}"
curl -sS -o /tmp/strats.json -w "strategies=%{http_code} time=%{time_total}s size=%{size_download}\n" \
  -H "Authorization: Bearer $token" \
  "$BASE/api/v1/strategies?marketType=FUTURES" --max-time 60
python3 - <<'PY'
import json
try:
    data=json.load(open('/tmp/strats.json'))
    if isinstance(data, list):
        print('count', len(data))
        code_lens=sum(len((x.get('sourceCode') or '')) for x in data)
        print('sourceCode_chars', code_lens)
        statuses={}
        for x in data:
            statuses[x.get('status')] = statuses.get(x.get('status'),0)+1
        print('statuses', statuses)
    else:
        print('body', str(data)[:400])
except Exception as e:
    print('parse_err', e, open('/tmp/strats.json').read()[:400])
PY
