#!/usr/bin/env bash
set -euo pipefail
export PGPASSWORD="${DB_PASSWORD:?}"
psql "host=aws-0-ap-southeast-1.pooler.supabase.com port=5432 dbname=postgres user=postgres.cdrsogeidbkjaapnjpbp sslmode=require" <<'SQL'
SELECT id, label, key_last4, status, created_at
FROM quantdcx.exchange_keys
ORDER BY created_at;

DELETE FROM quantdcx.exchange_keys;

SELECT count(*) AS remaining_keys FROM quantdcx.exchange_keys;
SQL
