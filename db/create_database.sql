-- =====================================================================
-- QuantDCX — one-time database bootstrap
--
-- Run this once as a PostgreSQL superuser (e.g. postgres):
--   psql -U postgres -f db/create_database.sql
--
-- Everything else (schema `quantdcx`, all tables, migrations) is created
-- automatically by Flyway on the first backend start.
-- =====================================================================

-- Application role. CHANGE THE PASSWORD and pass it to the backend as DB_PASSWORD.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'crypto') THEN
        CREATE ROLE crypto LOGIN PASSWORD 'algotrade123';
    END IF;
END $$;

-- Application database (psql \gexec pattern: creates only if missing).
SELECT 'CREATE DATABASE crypto_algo OWNER crypto'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'crypto_algo')
\gexec

GRANT ALL PRIVILEGES ON DATABASE crypto_algo TO crypto;

-- Optional: create the schema up front (Flyway also does this automatically
-- because spring.flyway.create-schemas=true).
\connect crypto_algo
CREATE SCHEMA IF NOT EXISTS quantdcx AUTHORIZATION crypto;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
