#!/usr/bin/env bash
set -uo pipefail
export DEBIAN_FRONTEND=noninteractive
if ! command -v psql >/dev/null; then
  sudo apt-get update -qq
  sudo apt-get install -y -qq postgresql-client
fi

export PGPASSWORD="${DB_PASSWORD:?set DB_PASSWORD}"
USER='postgres.cdrsogeidbkjaapnjpbp'
hosts=(
  aws-0-ap-south-1.pooler.supabase.com
  aws-1-ap-south-1.pooler.supabase.com
  aws-0-ap-southeast-1.pooler.supabase.com
  aws-0-ap-northeast-1.pooler.supabase.com
  aws-0-ap-northeast-2.pooler.supabase.com
  aws-0-us-east-1.pooler.supabase.com
  aws-0-us-west-1.pooler.supabase.com
  aws-0-us-west-2.pooler.supabase.com
  aws-0-eu-west-1.pooler.supabase.com
  aws-0-eu-west-2.pooler.supabase.com
  aws-0-eu-central-1.pooler.supabase.com
  aws-0-sa-east-1.pooler.supabase.com
  aws-0-ca-central-1.pooler.supabase.com
)

for h in "${hosts[@]}"; do
  for p in 5432 6543; do
    out="$(psql "host=${h} port=${p} dbname=postgres user=${USER} sslmode=require connect_timeout=5" -tAc 'select 1' 2>&1 | tr '\n' ' ')"
    echo "${h}:${p} => ${out}"
    if [[ "${out}" =~ ^[[:space:]]*1[[:space:]]*$ ]]; then
      echo "FOUND host=${h} port=${p}"
      exit 0
    fi
  done
done
echo NONE_FOUND
exit 1
