#!/bin/bash
set -euo pipefail
cp /tmp/backend-0.0.1-SNAPSHOT.jar ~/crypto-algo/backend/target/backend-0.0.1-SNAPSHOT.jar

python3 <<'PY'
import os, subprocess, sys
env={}
for line in open(os.path.expanduser('~/crypto-algo/.env.gcp'),encoding='utf-8',errors='ignore'):
    line=line.replace('\r','').strip()
    if not line or line.startswith('#') or '=' not in line:
        continue
    k,v=line.split('=',1)
    if k.startswith('JAVA'):
        continue
    env[k]=v
os.environ['PGPASSWORD']=env['DB_PASSWORD']
conn=(f"host={env['DB_HOST']} port={env.get('DB_PORT','5432')} "
      f"dbname={env['DB_NAME']} user={env['DB_USER']} sslmode=require")
r=subprocess.run(['psql',conn,'-v','ON_ERROR_STOP=1','-f','/tmp/apply-perf-indexes.sql'],
                 capture_output=True, text=True)
sys.stdout.write(r.stdout)
sys.stderr.write(r.stderr)
sys.exit(r.returncode)
PY

cd ~/crypto-algo
docker compose -f docker-compose.gcp.yml --env-file .env.gcp up -d --build --no-deps backend
sleep 28
docker logs crypto-algo-backend-1 2>&1 | grep -iE 'Started Backend|Flyway|ERROR' | tail -20

python3 <<'PY'
import json, time, urllib.request
email='admin@platform.local'
password='Asad@1996'
for base in ['http://127.0.0.1:8080','https://hbpt7cur19.execute-api.ap-south-1.amazonaws.com']:
    t0=time.time()
    try:
        req=urllib.request.Request(
            base+'/api/v1/auth/login',
            data=json.dumps({'email':email,'password':password}).encode(),
            headers={'Content-Type':'application/json'}, method='POST')
        with urllib.request.urlopen(req, timeout=30) as r:
            login=json.loads(r.read().decode())
        token=login.get('accessToken') or login.get('access_token')
        t1=time.time()
        req2=urllib.request.Request(
            base+'/api/v1/strategies?marketType=FUTURES',
            headers={'Authorization':'Bearer '+token})
        with urllib.request.urlopen(req2, timeout=60) as r2:
            body=r2.read(); status=r2.status
        data=json.loads(body)
        n=len(data) if isinstance(data,list) else -1
        print(f"{base} strategies={status} time={time.time()-t1:.3f}s size={len(body)} count={n}")
    except Exception as e:
        print(base, 'ERR', e, f't={time.time()-t0:.2f}s')
PY
