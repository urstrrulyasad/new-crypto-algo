-- =====================================================================
-- Crypto Algo Platform - initial schema
-- Multi-tenant: every business table carries tenant_id.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------------------------------------------------------------------
-- Tenancy & identity
-- ---------------------------------------------------------------------
CREATE TABLE tenants (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(120)         NOT NULL,
    slug        VARCHAR(60)          NOT NULL UNIQUE,
    status      VARCHAR(20)          NOT NULL DEFAULT 'ACTIVE', -- ACTIVE | SUSPENDED
    created_at  TIMESTAMPTZ          NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID          NOT NULL REFERENCES tenants(id),
    email          VARCHAR(255)  NOT NULL,
    password_hash  VARCHAR(100)  NOT NULL,
    display_name   VARCHAR(120)  NOT NULL,
    role           VARCHAR(20)   NOT NULL, -- SUPER_ADMIN | TENANT_ADMIN | TRADER
    status         VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE', -- ACTIVE | DISABLED
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, email)
);
CREATE INDEX idx_users_tenant ON users(tenant_id);

CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  VARCHAR(100) NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ  NOT NULL,
    revoked     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id);

-- ---------------------------------------------------------------------
-- Secrets: user-level exchange keys, admin-level AI provider config
-- (secrets stored AES-256-GCM encrypted, never in plaintext)
-- ---------------------------------------------------------------------
CREATE TABLE exchange_keys (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL REFERENCES tenants(id),
    user_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    exchange        VARCHAR(30)  NOT NULL DEFAULT 'COINDCX',
    label           VARCHAR(120) NOT NULL,
    api_key_enc     TEXT         NOT NULL,
    api_secret_enc  TEXT         NOT NULL,
    key_last4       VARCHAR(4)   NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE', -- ACTIVE | DISABLED
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_exchange_keys_user ON exchange_keys(tenant_id, user_id);

CREATE TABLE ai_providers (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID          NOT NULL REFERENCES tenants(id),
    created_by        UUID          NOT NULL REFERENCES users(id),
    provider_type     VARCHAR(30)   NOT NULL, -- ANTHROPIC | GEMINI | GROK | OPENAI_COMPATIBLE
    name              VARCHAR(120)  NOT NULL,
    base_url          TEXT          NOT NULL,
    model             VARCHAR(120)  NOT NULL,
    api_key_enc       TEXT          NOT NULL,
    request_template  JSONB         NOT NULL DEFAULT '{}'::jsonb,
    enabled           BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name)
);
CREATE INDEX idx_ai_providers_tenant ON ai_providers(tenant_id);

-- ---------------------------------------------------------------------
-- Strategies & backtests
-- ---------------------------------------------------------------------
CREATE TABLE strategies (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID          NOT NULL REFERENCES tenants(id),
    user_id         UUID          NOT NULL REFERENCES users(id),
    name            VARCHAR(120)  NOT NULL,
    version         INT           NOT NULL DEFAULT 1,
    parent_id       UUID          REFERENCES strategies(id),
    source_code     TEXT          NOT NULL,
    config          JSONB         NOT NULL DEFAULT '{}'::jsonb, -- timeframe, pairs, roi, stoploss...
    status          VARCHAR(20)   NOT NULL DEFAULT 'DRAFT', -- DRAFT | VALIDATED | APPROVED | ARCHIVED
    origin          VARCHAR(20)   NOT NULL DEFAULT 'MANUAL', -- MANUAL | AI_GENERATED
    ai_provider_id  UUID          REFERENCES ai_providers(id),
    prompt          TEXT,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_strategies_tenant_user ON strategies(tenant_id, user_id);

CREATE TABLE backtests (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID          NOT NULL REFERENCES tenants(id),
    strategy_id  UUID          NOT NULL REFERENCES strategies(id) ON DELETE CASCADE,
    timeframe    VARCHAR(10)   NOT NULL,
    pairs        JSONB         NOT NULL,
    range_start  TIMESTAMPTZ   NOT NULL,
    range_end    TIMESTAMPTZ   NOT NULL,
    status       VARCHAR(20)   NOT NULL DEFAULT 'PENDING', -- PENDING | RUNNING | DONE | FAILED
    metrics      JSONB,   -- profit_total_pct, win_rate, sharpe, max_drawdown, profit_factor...
    trades       JSONB,   -- list of simulated trades for chart markers
    error        TEXT,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    finished_at  TIMESTAMPTZ
);
CREATE INDEX idx_backtests_tenant_strategy ON backtests(tenant_id, strategy_id);

-- ---------------------------------------------------------------------
-- Bots, signals, orders, positions
-- ---------------------------------------------------------------------
CREATE TABLE bots (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID          NOT NULL REFERENCES tenants(id),
    user_id          UUID          NOT NULL REFERENCES users(id),
    strategy_id      UUID          NOT NULL REFERENCES strategies(id),
    exchange_key_id  UUID          REFERENCES exchange_keys(id), -- null for paper bots
    name             VARCHAR(120)  NOT NULL,
    mode             VARCHAR(10)   NOT NULL DEFAULT 'PAPER', -- PAPER | LIVE
    market_type      VARCHAR(10)   NOT NULL DEFAULT 'SPOT',  -- SPOT | FUTURES
    pairs            JSONB         NOT NULL,
    stake_currency   VARCHAR(10)   NOT NULL DEFAULT 'USDT',
    stake_amount     NUMERIC(30,10) NOT NULL,
    max_open_trades  INT           NOT NULL DEFAULT 3,
    leverage         NUMERIC(6,2)  NOT NULL DEFAULT 1,
    status           VARCHAR(20)   NOT NULL DEFAULT 'STOPPED', -- RUNNING | STOPPED
    kill_switch      BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_bots_tenant_user ON bots(tenant_id, user_id);

CREATE TABLE signals (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID          NOT NULL REFERENCES tenants(id),
    strategy_id      UUID          NOT NULL REFERENCES strategies(id),
    idempotency_key  VARCHAR(120)  NOT NULL UNIQUE,
    pair             VARCHAR(30)   NOT NULL,
    timeframe        VARCHAR(10)   NOT NULL,
    action           VARCHAR(20)   NOT NULL, -- ENTRY_LONG | ENTRY_SHORT | EXIT_LONG | EXIT_SHORT
    price            NUMERIC(30,10) NOT NULL,
    candle_ts        TIMESTAMPTZ   NOT NULL,
    payload          JSONB         NOT NULL DEFAULT '{}'::jsonb,
    received_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_signals_strategy ON signals(tenant_id, strategy_id, received_at DESC);

CREATE TABLE orders (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID          NOT NULL REFERENCES tenants(id),
    user_id            UUID          NOT NULL REFERENCES users(id),
    bot_id             UUID          NOT NULL REFERENCES bots(id),
    signal_id          UUID          REFERENCES signals(id),
    exchange_order_id  VARCHAR(80),
    client_order_id    VARCHAR(80)   NOT NULL UNIQUE,
    pair               VARCHAR(30)   NOT NULL,
    side               VARCHAR(5)    NOT NULL, -- BUY | SELL
    order_type         VARCHAR(20)   NOT NULL DEFAULT 'MARKET_ORDER',
    market_type        VARCHAR(10)   NOT NULL DEFAULT 'SPOT',
    mode               VARCHAR(10)   NOT NULL, -- PAPER | LIVE
    status             VARCHAR(30)   NOT NULL DEFAULT 'NEW', -- NEW | OPEN | FILLED | CANCELLED | REJECTED
    price              NUMERIC(30,10),
    quantity           NUMERIC(30,10) NOT NULL,
    filled_qty         NUMERIC(30,10) NOT NULL DEFAULT 0,
    avg_price          NUMERIC(30,10),
    fee                NUMERIC(30,10) NOT NULL DEFAULT 0,
    error              TEXT,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_orders_bot ON orders(tenant_id, bot_id, created_at DESC);

CREATE TABLE positions (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID          NOT NULL REFERENCES tenants(id),
    user_id       UUID          NOT NULL REFERENCES users(id),
    bot_id        UUID          NOT NULL REFERENCES bots(id),
    pair          VARCHAR(30)   NOT NULL,
    side          VARCHAR(5)    NOT NULL, -- LONG | SHORT
    quantity      NUMERIC(30,10) NOT NULL,
    entry_price   NUMERIC(30,10) NOT NULL,
    exit_price    NUMERIC(30,10),
    leverage      NUMERIC(6,2)  NOT NULL DEFAULT 1,
    status        VARCHAR(10)   NOT NULL DEFAULT 'OPEN', -- OPEN | CLOSED
    realized_pnl  NUMERIC(30,10),
    opened_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    closed_at     TIMESTAMPTZ
);
CREATE INDEX idx_positions_bot ON positions(tenant_id, bot_id, status);

-- ---------------------------------------------------------------------
-- Market data (shared across tenants - public exchange data)
-- ---------------------------------------------------------------------
CREATE TABLE candles (
    pair       VARCHAR(30)    NOT NULL,
    timeframe  VARCHAR(10)    NOT NULL,
    ts         TIMESTAMPTZ    NOT NULL,
    open       NUMERIC(30,10) NOT NULL,
    high       NUMERIC(30,10) NOT NULL,
    low        NUMERIC(30,10) NOT NULL,
    close      NUMERIC(30,10) NOT NULL,
    volume     NUMERIC(38,10) NOT NULL,
    PRIMARY KEY (pair, timeframe, ts)
);

-- ---------------------------------------------------------------------
-- Audit
-- ---------------------------------------------------------------------
CREATE TABLE audit_log (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID         NOT NULL,
    user_id      UUID,
    action       VARCHAR(80)  NOT NULL,
    entity_type  VARCHAR(40),
    entity_id    UUID,
    details      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_tenant ON audit_log(tenant_id, created_at DESC);
