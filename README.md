# QuantDCX — Multi-Tenant AI Crypto Trading Platform

AI-generated trading strategies, backtested on real CoinDCX market data, executed paper-first
(then live) on CoinDCX — built as a reactive Java backend, a Python strategy engine, and a
React frontend with TradingView Lightweight Charts.

## Architecture

| Component | Stack | Role |
|---|---|---|
| `backend/` | Java 21, Spring Boot 3.5, WebFlux, R2DBC, Flyway | Multi-tenant API: auth (JWT), RBAC (Super Admin / Tenant Admin / Trader), encrypted CoinDCX keys (user level), encrypted AI provider config (admin level), market data ingestion, execution engine (paper + live), portfolio, audit |
| `strategy-engine/` | Python 3.11, FastAPI, pandas, ta | LLM strategy generation (Claude / Gemini / Grok / OpenAI-compatible), AST-sandbox validation, backtesting over real CoinDCX candles, live signal runner |
| `frontend/` | React 19, TypeScript, Tailwind v4, Motion, Lightweight Charts | Animated responsive UI: dashboard with live SSE tickers, strategy lab with chart + trade markers, bots, admin panel, key management |
| PostgreSQL | schema `quantdcx`, versioned by Flyway | All state; every business row carries `tenant_id` |

Data flow: signal runner evaluates approved strategies on each closed candle → POSTs idempotent
signals to the backend → execution engine fans out to running bots (risk checks: max open trades,
stake sizing, kill switch) → paper fill or signed CoinDCX market order → reconciliation loop
tracks live fills.

## Run (local dev)

Prereqs: Java 21+, Node 20+, Python 3.11, PostgreSQL with database `crypto_algo`.

```powershell
# 1. Backend (creates schema via Flyway, bootstraps super admin on first run)
cd backend
$env:DB_PASSWORD='<your pg password>'
.\mvnw.cmd spring-boot:run

# 2. Strategy engine
cd strategy-engine
python -m pip install -r requirements.txt
python -m uvicorn app.main:app --port 8000

# 3. Frontend (http://localhost:5173, proxies /api to :8080)
cd frontend
npm install
npm run dev
```

First login: `admin@platform.local` / `ChangeMe123!` (override with `SUPERADMIN_EMAIL` /
`SUPERADMIN_PASSWORD`; change immediately).

## Run (docker compose)

```bash
cp .env.example .env   # fill in JWT_SECRET, MASTER_KEY, INTERNAL_TOKEN, SUPERADMIN_PASSWORD
docker compose up --build
```

## Usage walkthrough

1. **Admin panel** → add an AI provider (type, base URL, model, API key — stored AES-256-GCM
   encrypted, write-only). Create tenants (super admin) and users.
2. **Strategy Lab** → "Generate with AI": describe the goal, pick provider/timeframe/risk.
   The engine prompts the LLM, validates the generated code (import allowlist, no I/O/exec,
   sandboxed load), and stores it as VALIDATED.
3. **Run a backtest** over any lookback window — real CoinDCX candles, fees included. Metrics:
   total profit %, win rate, profit factor, max drawdown, Sharpe, per-trade markers on the chart.
4. **Approve** the strategy, then create a **PAPER bot**. The signal runner evaluates it every
   closed candle; simulated fills build a track record on the dashboard.
5. Only then: add your CoinDCX API key (Settings) and create a **LIVE bot**.

## Security model

- CoinDCX API keys: **user level**, AES-256-GCM encrypted with `MASTER_KEY`, never returned by
  any API (only label + last 4 chars), decrypted in memory at order time only.
- LLM API keys: **admin level**, same encryption, write-only fields.
- Tenant isolation: every query filters by the JWT's `tenant_id`; roles enforced via Spring
  Security method security.
- AI-generated code: AST allowlist (pandas/numpy/ta/math only), forbidden call/attribute scan,
  restricted-builtins sandbox with guarded `__import__`.
- Internal backend ↔ engine traffic authenticated with `INTERNAL_TOKEN`; signals are idempotent.

## Honest caveats

- Backtest results never guarantee live profits. Always paper trade first (the platform defaults
  to it).
- Strategies are candle-based (min 1m) — this is a swing/intraday platform, not HFT.
- The strategy engine implements the freqtrade strategy *interface* (populate_indicators /
  populate_entry_trend / populate_exit_trend, stoploss, minimal_roi) with its own simulator,
  because freqtrade cannot trade or fetch data on CoinDCX (no CCXT support). Swapping the
  simulator for the full freqtrade engine inside Docker is a contained change in
  `strategy-engine/app/backtest.py`.
- Futures/margin endpoints exist in the CoinDCX client but v1 execution is spot-first; leverage
  is tracked per bot for PnL math.
- Operating other people's funds may require licensing in your jurisdiction — get legal advice
  before onboarding third-party users.
