# QuantDCX Database Architecture & Schema Reference

QuantDCX uses **PostgreSQL (15+)** with **Flyway migrations (`V1`–`V5`)** under schema **`quantdcx`** for the Spring Boot R2DBC production backend. In the AI Studio live preview runtime (`server.ts`), a stateful in-memory store (`nodejs-in-memory`) mirrors this schema cleanly with real CoinDCX public candle feeds.

---

## 1. Connection & Environment Variables

### Production PostgreSQL / Supabase (`.env.gcp` / `docker-compose.gcp.yml`)

| Variable | Default | Notes |
| --- | --- | --- |
| `DB_HOST` | `localhost` | Postgres host (local, RDS, or Supabase pooler) |
| `DB_PORT` | `5432` | Postgres port (`5432` direct or `6543` Supabase transaction pooler) |
| `DB_NAME` | `postgres` (GCP) / `crypto_algo` (local) | Target database name |
| `DB_USER` | `crypto` (local) / `postgres` (Supabase) | Role with DDL/DML ownership of schema `quantdcx` |
| `DB_PASSWORD` | — | Database role password |
| `DB_SCHEMA` | `quantdcx` | Target schema (created automatically by Flyway if missing) |
| `DB_SSL_MODE` | `require` (GCP) / `disable` (local) | Set `require` for Supabase / RDS |
| `MASTER_KEY` | — | 32-byte base64 key used for AES-256-GCM encryption at rest |

### Local Bootstrap (`db/create_database.sql`)
Run once as superuser before starting `backend`:
```bash
psql -U postgres -f db/create_database.sql
```
This provisions:
- **Role**: `crypto` (login role)
- **Database**: `crypto_algo` (owned by `crypto`)
- **Schema**: `quantdcx` (authorized to `crypto`)
- **Extension**: `pgcrypto` (for `gen_random_uuid()`)

---

## 2. Schema `quantdcx` — Active Tables (13 Tables)

Flyway migrations live in `backend/src/main/resources/db/migration/`:
- `V1__init.sql` — Multi-tenant core tables, identity, secrets, strategies, backtests, bots, signals, orders, positions, audit log.
- `V2__auto_pipeline.sql` — Preset-based AI providers with failover priority, stops/targets + `sl_order_id` on positions, drops historical `candles` table (candles are streamed live from CoinDCX).
- `V3__market_verticals.sql` — Adds `market_type`, `instrument`, `margin_currency` for INR-margined CoinDCX Futures; archives legacy SPOT items.
- `V4__perf_indexes.sql` — Composite covering indexes for strategy list, paper stats (`WHERE status = 'CLOSED'`), orders, backtests, and audit history.
- `V5__tenant_live_risk.sql` — Adds `tenant_live_risk` margin reservation table and order status lookup indexes.

### Table Summary

| Table | Purpose | Key Columns & Relationships |
| --- | --- | --- |
| `tenants` | Organization / desk isolation | `id (UUID PK)`, `name`, `slug (UNIQUE)`, `status (ACTIVE\|SUSPENDED)` |
| `users` | Per-tenant operators | `id (UUID PK)`, `tenant_id -> tenants`, `email`, `password_hash`, `role (SUPER_ADMIN\|TENANT_ADMIN\|TRADER)`, `status`, `UNIQUE(tenant_id, email)` |
| `refresh_tokens` | Rotating JWT refresh tokens | `id (UUID PK)`, `user_id -> users (ON DELETE CASCADE)`, `token_hash (UNIQUE)`, `expires_at`, `revoked` |
| `exchange_keys` | Encrypted CoinDCX API keys | `id (UUID PK)`, `tenant_id`, `user_id -> users`, `exchange ('COINDCX')`, `label`, `api_key_enc` (AES-256-GCM), `api_secret_enc` (AES-256-GCM), `key_last4`, `status` |
| `ai_providers` | Failover LLM chain per tenant | `id (UUID PK)`, `tenant_id`, `created_by -> users`, `provider_type (ANTHROPIC\|GEMINI\|GROK\|OPENAI_COMPATIBLE)`, `api_key_enc`, `priority`, `enabled`, `UNIQUE(tenant_id, provider_type)` |
| `strategies` | AI-generated & audited Python strategies | `id (UUID PK)`, `tenant_id`, `user_id`, `name`, `version`, `parent_id`, `source_code`, `config (JSONB)`, `status (GENERATED\|BACKTESTED\|PAPER_TRADING\|LIVE_APPROVED\|REJECTED\|ARCHIVED)`, `origin`, `market_type`, `instrument`, `margin_currency` |
| `backtests` | Vectorized multi-bar simulations | `id (UUID PK)`, `tenant_id`, `strategy_id -> strategies (CASCADE)`, `timeframe`, `pairs (JSONB)`, `range_start`, `range_end`, `status`, `metrics (JSONB)`, `trades (JSONB)`, `error` |
| `bots` | Active PAPER / LIVE execution engines | `id (UUID PK)`, `tenant_id`, `user_id`, `strategy_id`, `exchange_key_id`, `mode (PAPER\|LIVE)`, `market_type`, `pairs (JSONB)`, `stake_currency`, `stake_amount`, `max_open_trades`, `leverage`, `status`, `kill_switch`, `margin_currency` |
| `signals` | Deduplicated strategy triggers | `id (UUID PK)`, `tenant_id`, `strategy_id`, `idempotency_key (UNIQUE)`, `pair`, `timeframe`, `action (ENTRY_LONG\|ENTRY_SHORT\|EXIT_LONG\|EXIT_SHORT)`, `price`, `candle_ts`, `payload (JSONB)` |
| `orders` | Exchange & paper order ledger | `id (UUID PK)`, `tenant_id`, `user_id`, `bot_id`, `signal_id`, `exchange_order_id`, `client_order_id (UNIQUE)`, `pair`, `side (BUY\|SELL)`, `order_type`, `market_type`, `mode`, `status`, `price`, `quantity`, `filled_qty`, `avg_price`, `fee`, `error` |
| `positions` | Open & closed positions with SL/TP | `id (UUID PK)`, `tenant_id`, `user_id`, `bot_id`, `pair`, `side (LONG\|SHORT)`, `quantity`, `entry_price`, `exit_price`, `sl_price`, `target_price`, `sl_order_id`, `leverage`, `status (OPEN\|CLOSED)`, `realized_pnl`, `margin_currency`, `opened_at`, `closed_at` |
| `audit_log` | Immutable execution & admin trail | `id (UUID PK)`, `tenant_id`, `user_id`, `action`, `entity_type`, `entity_id`, `details (JSONB)`, `created_at` |
| `tenant_live_risk` | Single-instance INR margin lock | `tenant_id (UUID PK -> tenants)`, `reserved_margin_inr`, `protection_degraded`, `updated_at` |

*(Wait: note that `candles` was intentionally dropped in `V2__auto_pipeline.sql`; `market/candles` queries CoinDCX directly and caches in-memory.)*

---

## 3. Security & Encryption Guarantees

- **Tenant Isolation**: Every business table carries `tenant_id` indexed at the leftmost prefix (`idx_*_tenant*`).
- **AES-256-GCM at Rest**: Exchange API credentials (`api_key_enc`, `api_secret_enc`) and AI provider keys are encrypted using `MASTER_KEY` before hitting PostgreSQL; UI responses return only `key_last4`.
- **Single-Flight Risk Gate (`tenant_live_risk`)**: Prevents concurrent LIVE order sizing races from over-allocating INR wallet balance.
