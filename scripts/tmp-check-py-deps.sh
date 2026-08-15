#!/bin/sh
docker exec crypto-algo-strategy-engine-1 python -c "import requests; print('requests-ok')"
docker exec crypto-algo-strategy-engine-1 python -c "from cryptography.hazmat.primitives.ciphers.aead import AESGCM; print('crypto-ok')"
docker exec crypto-algo-strategy-engine-1 python -c "import psycopg2; print('psycopg-ok')"
