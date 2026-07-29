# QuantDCX — Autonomous Multi-Tenant AI Crypto Trading Platform

Fully autonomous strategy pipeline on real CoinDCX market data:

**AI generates a strategy → validates + smoke-tests it on live candles → auto-backtests →
auto-starts a paper bot → after ≥ 10 closed paper trades at ≥ 75% win rate it is
auto-approved and a live bot starts trading real funds with stop-loss and target on every order.**

There is no manual strategy creation and no manual approval — the pipeline does everything.

## Architecture

| Component | Stack | Role |
|---|---|---|
| `backend/` | Java 21, Spring Boot 3.5, WebFlux, R2DBC, Flyway | Multi-tenant API: JWT auth, RBAC (Super Admin / Tenant Admin / Trader), AES-256-GCM-encrypted CoinDCX keys (user level) and AI provider keys (admin level), strategy pipeline orchestration, execution engine (paper + live), position guard (SL/target), paper-trade gate, portfolio, audit |
| `strategy-engine/` | Python 3.11, FastAPI, pandas, ta | LLM strategy generation with **model → provider rate-limit failover**, AST-sandbox validation + runtime smoke test on live candles (with self-correcting retry), backtesting on real CoinDCX candles, live signal runner |
| `frontend/` | React 19, TypeScript, Tailwind v4, Motion, Lightweight Charts | Animated responsive UI: dashboard with live SSE tickers, AI pipeline dashboard with progress gates, bots, admin panel, key management |
| PostgreSQL | schema `quantdcx`, versioned by Flyway | All state; every business row carries `tenant_id`. **No candle data is stored** — market data is fetched live and passed straight to the AI |

### AI providers (free tier, key-only setup)

Preset catalog — the admin only pastes an API key, everything else is built in:

| Provider | Model fallback chain |
|---|---|
| Google Gemini | gemini-2.5-flash → gemini-2.5-flash-lite → gemini-2.0-flash |
| Groq | llama-3.3-70b-versatile → openai/gpt-oss-120b → llama-3.1-8b-instant |
| OpenRouter | deepseek-chat-v3.1:free → llama-3.3-70b-instruct:free → qwen-2.5-72b:free |
| Mistral AI | mistral-large-latest → mistral-medium-latest → mistral-small-latest |
| Cerebras | llama-3.3-70b → gpt-oss-120b → llama3.1-8b |

On a 429 the engine tries the next model of the same provider; when a provider is exhausted it
falls through to the next configured provider. Generation only fails when the whole chain fails.

### Pipeline flow

```
Generate (UI, all fields optional)
  └─ LLM failover chain, live candles in the prompt
      └─ AST validation + runtime smoke test (self-correcting retry, max 3 attempts)
          └─ Strategy saved: GENERATED
              └─ Auto backtest (30 days, real candles) → BACKTESTED
                  └─ Auto paper bot starts → PAPER_TRADING
                      └─ Gate: ≥ 10 closed paper trades AND ≥ 75% win rate
                          └─ LIVE_APPROVED: paper bot stops, live bot starts
                              (sized by available funds, SL resting on the exchange,
                               target watched by the position guard)
```

Every entry (paper and live) stores `sl_price` / `target_price` derived from the strategy's
`stoploss` / `minimal_roi`. Live entries place an exchange-side `stop_limit` stop-loss leg
(CoinDCX spot has no OCO); the position guard watches the target and paper SL/target.

> CoinDCX's public candle API only serves the `1m`, `15m`, `1h`, `1d` intervals; every other
> timeframe is normalized to the nearest supported one.

---

## Step-by-step: run the application

### 0. Prerequisites

- Java 21+ (`java -version`)
- Node 20+ (`node -v`)
- Python 3.11 (`python --version`)
- PostgreSQL 14+ running locally

### 1. Create the database

```bash
psql -U postgres -f db/create_database.sql
```

This creates the `crypto` role (default password `algotrade123` — change it) and the
`crypto_algo` database. Flyway creates the `quantdcx` schema and all tables automatically on
the first backend start.

### 2. Start the backend (port 8080)

```powershell
cd backend
$env:DB_PASSWORD='algotrade123'      # the password from step 1
.\mvnw.cmd spring-boot:run
```

Wait for `Started BackendApplication`. On the first run Flyway migrates the schema and a super
admin is bootstrapped.

### 3. Start the strategy engine (port 8000)

```powershell
cd strategy-engine
python -m pip install -r requirements.txt
$env:BACKEND_URL='http://localhost:8080'
python -m uvicorn app.main:app --port 8000
```

### 4. Start the frontend (port 5173)

```powershell
cd frontend
npm install
npm run dev
```

Open http://localhost:5173 and log in with `admin@platform.local` / `ChangeMe123!`
(override via `SUPERADMIN_EMAIL` / `SUPERADMIN_PASSWORD`; change it immediately).

### 5. Configure the platform (once)

1. **Admin → AI Providers**: pick a provider (e.g. Google Gemini), paste the API key, save.
   Add more providers for deeper rate-limit failover.
2. **API Keys**: add your CoinDCX API key + secret (needed only when a strategy is promoted
   to live trading; stored encrypted).

### 6. Run the pipeline

**Strategy Lab → Generate strategy** (every field optional) → watch the pipeline card:
GENERATED → BACKTESTED → PAPER TRADING with a live `x/10 trades · y% wins` progress bar.
When the gate passes, the strategy flips to LIVE APPROVED and a live bot starts automatically.

### Docker alternative

```bash
cp .env.example .env    # set DB password, JWT_SECRET, MASTER_KEY, INTERNAL_TOKEN, SUPERADMIN_PASSWORD
docker compose up --build
```

---

## Configuration knobs (env vars, all optional)

| Variable | Default | Meaning |
|---|---|---|
| `PIPELINE_MIN_PAPER_TRADES` | `10` | Closed paper trades required before the live gate |
| `PIPELINE_WIN_RATE_THRESHOLD` | `0.75` | Win rate required for auto-promotion to live |
| `PIPELINE_BACKTEST_DAYS` | `30` | Auto-backtest lookback |
| `PIPELINE_PAPER_STAKE` | `1000` | Paper bot stake (USDT) |
| `PIPELINE_MAX_OPEN_TRADES` | `3` | Auto-created bot max concurrent positions |
| `PIPELINE_DEFAULT_STOPLOSS` | `-0.05` | Fallback stop-loss when the AI config omits it |
| `PIPELINE_DEFAULT_TARGET_ROI` | `0.04` | Fallback target when the AI config omits it |
| `DB_HOST/PORT/NAME/USER/PASSWORD/SCHEMA` | localhost/5432/crypto_algo/crypto/crypto/quantdcx | Database |
| `JWT_SECRET`, `MASTER_KEY`, `INTERNAL_TOKEN` | dev values | **Must** be overridden in production |

## Security model

- Secrets (CoinDCX keys, AI provider keys) stored AES-256-GCM encrypted, write-only via API.
- Tenant isolation on every query (`tenant_id` on every business row).
- Strategy code is AST-validated (import allowlist: pandas/numpy/ta, no I/O, no exec/eval),
  loaded in a restricted namespace, and smoke-tested before it can trade.
- Engine ↔ backend calls authenticated with a shared internal token.
- Live risk: fund-based sizing, market minimum-notional checks, per-bot kill switch,
  exchange-side stop-loss leg + position guard.

## Caveats

- Live promotion is **fully automatic** by design (product decision): once a strategy passes
  the paper gate it trades real funds with the owner's CoinDCX key. Keep the kill switch handy.
- Spot only for live orders (long-only); shorts are ignored on spot bots.
- A 75% win-rate gate is deliberately hard — most generated strategies will keep paper trading
  indefinitely. Tune `PIPELINE_WIN_RATE_THRESHOLD` / `PIPELINE_MIN_PAPER_TRADES` to taste.
