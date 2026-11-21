import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import express, { type NextFunction, type Request, type Response } from 'express';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const PORT = Number(process.env.PORT || 3000);
const HOST = '0.0.0.0';
const JWT_SECRET =
  process.env.JWT_SECRET || 'dev-only-secret-key-change-me-in-ai-studio';
const SUPERADMIN_EMAIL = (
  process.env.SUPERADMIN_EMAIL || 'admin@platform.local'
).toLowerCase();
const SUPERADMIN_PASSWORD =
  process.env.SUPERADMIN_PASSWORD || 'ChangeMe123!';

// ---------------------------------------------------------------------------
// Types & In-Memory Store
// ---------------------------------------------------------------------------

interface Tenant {
  id: string;
  name: string;
  slug: string;
  status: 'ACTIVE' | 'SUSPENDED';
  createdAt: string;
}

interface User {
  id: string;
  tenantId: string;
  email: string;
  passwordHash: string;
  displayName: string;
  role: 'SUPER_ADMIN' | 'TENANT_ADMIN' | 'TRADER';
  status: 'ACTIVE' | 'DISABLED';
  createdAt: string;
}

interface RefreshRecord {
  userId: string;
  expiresAt: number;
  revoked: boolean;
}

interface AiProvider {
  id: string;
  tenantId: string;
  providerType: string;
  displayName: string;
  models: string[];
  priority: number;
  enabled: boolean;
  apiKeyLast4: string;
  createdAt: string;
}

interface ExchangeKey {
  id: string;
  tenantId: string;
  userId: string;
  exchange: string;
  label: string;
  apiKeyEnc: string;
  apiSecretEnc: string;
  keyLast4: string;
  status: 'ACTIVE' | 'REVOKED';
  createdAt: string;
}

interface Strategy {
  id: string;
  tenantId: string;
  name: string;
  version: number;
  status:
    | 'GENERATED'
    | 'BACKTESTED'
    | 'PAPER_TRADING'
    | 'LIVE_APPROVED'
    | 'REJECTED'
    | 'ARCHIVED';
  origin: string;
  sourceCode: string;
  marketType: 'FUTURES' | 'SPOT';
  instrument: string;
  marginCurrency: string;
  config: {
    timeframe: string;
    pairs: string[];
    provider_used: string;
    model_used: string;
    generation_errors?: string[];
  };
  createdAt: string;
}

interface Backtest {
  id: string;
  tenantId: string;
  strategyId: string;
  timeframe: string;
  pairs: string[];
  rangeStart: string;
  rangeEnd: string;
  status: 'RUNNING' | 'DONE' | 'FAILED';
  metrics: Record<string, unknown> | null;
  trades: { entry_time: string; exit_time: string; profit_ratio: number }[] | null;
  error: string | null;
  createdAt: string;
}

interface Bot {
  id: string;
  tenantId: string;
  userId: string;
  strategyId: string;
  exchangeKeyId: string | null;
  name: string;
  mode: 'PAPER' | 'LIVE';
  marketType: 'FUTURES' | 'SPOT';
  pairs: string[];
  stakeCurrency: string;
  stakeAmount: number;
  maxOpenTrades: number;
  leverage: number;
  status: 'RUNNING' | 'STOPPED';
  killSwitch: boolean;
  marginCurrency: string;
  createdAt: string;
}

interface Position {
  id: string;
  tenantId: string;
  userId: string;
  botId: string;
  strategyId: string;
  mode: 'PAPER' | 'LIVE';
  pair: string;
  side: 'LONG' | 'SHORT';
  quantity: number;
  entryPrice: number;
  exitPrice: number | null;
  slPrice: number | null;
  targetPrice: number | null;
  status: 'OPEN' | 'CLOSED';
  leverage: number;
  realizedPnl: number | null;
  marginInr: number | null;
  marginCurrency: string;
  openedAt: string;
  closedAt: string | null;
}

interface TradeOrder {
  id: string;
  tenantId: string;
  userId: string;
  botId: string;
  strategyId: string;
  mode: 'PAPER' | 'LIVE';
  pair: string;
  side: 'BUY' | 'SELL';
  orderType: string;
  status: 'FILLED' | 'OPEN' | 'FAILED' | 'CANCELLED';
  price: number;
  avgPrice: number | null;
  quantity: number;
  filledQty: number | null;
  sizeInr: number | null;
  error: string | null;
  createdAt: string;
}

interface AuditLog {
  id: string;
  tenantId: string;
  entityType: string;
  entityId: string;
  action: string;
  details: Record<string, string | number>;
  createdAt: string;
}

const PROVIDER_CATALOG = [
  {
    type: 'GEMINI',
    displayName: 'Google Gemini',
    models: ['gemini-flash-latest', 'gemini-2.0-flash-lite', 'gemini-2.0-flash'],
  },
  {
    type: 'GROQ',
    displayName: 'Groq',
    models: ['llama-3.3-70b-versatile', 'openai/gpt-oss-120b', 'llama-3.1-8b-instant'],
  },
  {
    type: 'OPENAI',
    displayName: 'OpenAI',
    models: ['gpt-4.1-mini', 'gpt-4o-mini', 'gpt-4.1'],
  },
  {
    type: 'OPENROUTER',
    displayName: 'OpenRouter',
    models: [
      'openrouter/free',
      'google/gemma-3-27b-it:free',
      'mistralai/mistral-small-3.1-24b-instruct:free',
    ],
  },
  {
    type: 'MISTRAL',
    displayName: 'Mistral AI',
    models: ['mistral-large-latest', 'mistral-medium-latest', 'mistral-small-latest'],
  },
  {
    type: 'CEREBRAS',
    displayName: 'Cerebras',
    models: ['llama-3.3-70b', 'gpt-oss-120b', 'llama3.1-8b'],
  },
];

// ---------------------------------------------------------------------------
// Crypto / Auth Helpers
// ---------------------------------------------------------------------------

function hashPassword(password: string): string {
  return crypto.createHash('sha256').update(password).digest('hex');
}

function base64UrlEncode(input: string | Buffer): string {
  return Buffer.from(input)
    .toString('base64')
    .replace(/=/g, '')
    .replace(/\+/g, '-')
    .replace(/\//g, '_');
}

function base64UrlDecode(input: string): string {
  const padded = input.replace(/-/g, '+').replace(/_/g, '/');
  return Buffer.from(padded, 'base64').toString('utf8');
}

interface AuthPrincipal {
  userId: string;
  tenantId: string;
  email: string;
  role: 'SUPER_ADMIN' | 'TENANT_ADMIN' | 'TRADER';
}

function signToken(principal: AuthPrincipal, ttlSeconds = 1800): string {
  const header = base64UrlEncode(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const payload = base64UrlEncode(
    JSON.stringify({
      sub: principal.userId,
      tid: principal.tenantId,
      email: principal.email,
      role: principal.role,
      exp: Math.floor(Date.now() / 1000) + ttlSeconds,
    }),
  );
  const sig = base64UrlEncode(
    crypto
      .createHmac('sha256', JWT_SECRET)
      .update(`${header}.${payload}`)
      .digest(),
  );
  return `${header}.${payload}.${sig}`;
}

function verifyToken(token: string): AuthPrincipal | null {
  try {
    const parts = token.split('.');
    if (parts.length !== 3) return null;
    const [header, payload, sig] = parts;
    const expected = base64UrlEncode(
      crypto
        .createHmac('sha256', JWT_SECRET)
        .update(`${header}.${payload}`)
        .digest(),
    );
    if (sig !== expected) return null;
    const parsed = JSON.parse(base64UrlDecode(payload)) as {
      sub: string;
      tid: string;
      email: string;
      role: AuthPrincipal['role'];
      exp: number;
    };
    if (parsed.exp < Math.floor(Date.now() / 1000)) return null;
    return {
      userId: parsed.sub,
      tenantId: parsed.tid,
      email: parsed.email,
      role: parsed.role,
    };
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------------
// Seed Initial Data
// ---------------------------------------------------------------------------

const DEFAULT_TENANT_ID = '11111111-1111-4111-8111-111111111111';
const DEFAULT_USER_ID = '22222222-2222-4222-8222-222222222222';
const STRAT_BTC_ID = '33333333-3333-4333-8333-333333333301';
const STRAT_ETH_ID = '33333333-3333-4333-8333-333333333302';
const STRAT_SOL_ID = '33333333-3333-4333-8333-333333333303';
const BOT_BTC_LIVE_ID = '44444444-4444-4444-8444-444444444401';
const BOT_ETH_PAPER_ID = '44444444-4444-4444-8444-444444444402';
const BOT_SOL_PAPER_ID = '44444444-4444-4444-8444-444444444403';

const nowMs = Date.now();
const hoursAgo = (h: number) => new Date(nowMs - h * 3_600_000).toISOString();
const daysAgo = (d: number) => new Date(nowMs - d * 86_400_000).toISOString();

const tenants: Tenant[] = [
  {
    id: DEFAULT_TENANT_ID,
    name: 'Platform',
    slug: 'platform',
    status: 'ACTIVE',
    createdAt: daysAgo(14),
  },
];

const users: User[] = [
  {
    id: DEFAULT_USER_ID,
    tenantId: DEFAULT_TENANT_ID,
    email: SUPERADMIN_EMAIL,
    passwordHash: hashPassword(SUPERADMIN_PASSWORD),
    displayName: 'Super Admin',
    role: 'SUPER_ADMIN',
    status: 'ACTIVE',
    createdAt: daysAgo(14),
  },
];

const refreshTokens = new Map<string, RefreshRecord>();

const aiProviders: AiProvider[] = [
  {
    id: '55555555-5555-4555-8555-555555555501',
    tenantId: DEFAULT_TENANT_ID,
    providerType: 'GEMINI',
    displayName: 'Google Gemini',
    models: ['gemini-flash-latest', 'gemini-2.0-flash-lite', 'gemini-2.0-flash'],
    priority: 100,
    enabled: true,
    apiKeyLast4: 'demo',
    createdAt: daysAgo(10),
  },
];

const exchangeKeys: ExchangeKey[] = [];

const SAMPLE_STRATEGY_CODE = (pair: string, name: string) => `import numpy as np
import pandas as pd
import ta

class ${name.replace(/[^A-Za-z0-9]/g, '')}:
    timeframe = "5m"
    stoploss = -0.013
    minimal_roi = {"0": 0.02}
    pairs = ["${pair}"]

    def populate_indicators(self, df: pd.DataFrame) -> pd.DataFrame:
        df["ema_fast"] = ta.trend.ema_indicator(df["close"], window=12)
        df["ema_slow"] = ta.trend.ema_indicator(df["close"], window=26)
        df["rsi"] = ta.momentum.rsi(df["close"], window=14)
        bb = ta.volatility.BollingerBands(df["close"], window=20, window_dev=2)
        df["bb_lower"] = bb.bollinger_lband()
        df["bb_upper"] = bb.bollinger_hband()
        return df

    def populate_entry_trend(self, df: pd.DataFrame) -> pd.DataFrame:
        df["enter_long"] = (
            (df["ema_fast"] > df["ema_slow"])
            & (df["rsi"] > 45)
            & (df["rsi"] < 68)
            & (df["close"] > df["bb_lower"])
        )
        df["enter_short"] = (
            (df["ema_fast"] < df["ema_slow"])
            & (df["rsi"] < 55)
            & (df["rsi"] > 32)
            & (df["close"] < df["bb_upper"])
        )
        return df
`;

const strategies: Strategy[] = [
  {
    id: STRAT_BTC_ID,
    tenantId: DEFAULT_TENANT_ID,
    name: 'BTC EMA-RSI Momentum v3',
    version: 3,
    status: 'LIVE_APPROVED',
    origin: 'AI_PIPELINE',
    sourceCode: SAMPLE_STRATEGY_CODE('B-BTC_USDT', 'BtcEmaRsiMomentum'),
    marketType: 'FUTURES',
    instrument: 'B-BTC_USDT',
    marginCurrency: 'INR',
    config: {
      timeframe: '5m',
      pairs: ['B-BTC_USDT'],
      provider_used: 'GEMINI',
      model_used: 'gemini-flash-latest',
    },
    createdAt: daysAgo(6),
  },
  {
    id: STRAT_ETH_ID,
    tenantId: DEFAULT_TENANT_ID,
    name: 'ETH Volatility Breakout v2',
    version: 2,
    status: 'PAPER_TRADING',
    origin: 'AI_PIPELINE',
    sourceCode: SAMPLE_STRATEGY_CODE('B-ETH_USDT', 'EthVolatilityBreakout'),
    marketType: 'FUTURES',
    instrument: 'B-ETH_USDT',
    marginCurrency: 'INR',
    config: {
      timeframe: '5m',
      pairs: ['B-ETH_USDT'],
      provider_used: 'GROQ',
      model_used: 'llama-3.3-70b-versatile',
    },
    createdAt: daysAgo(3),
  },
  {
    id: STRAT_SOL_ID,
    tenantId: DEFAULT_TENANT_ID,
    name: 'SOL Bollinger MeanRev v1',
    version: 1,
    status: 'PAPER_TRADING',
    origin: 'AI_PIPELINE',
    sourceCode: SAMPLE_STRATEGY_CODE('B-SOL_USDT', 'SolBollingerMeanRev'),
    marketType: 'FUTURES',
    instrument: 'B-SOL_USDT',
    marginCurrency: 'INR',
    config: {
      timeframe: '15m',
      pairs: ['B-SOL_USDT'],
      provider_used: 'GEMINI',
      model_used: 'gemini-2.0-flash',
    },
    createdAt: daysAgo(1),
  },
];

function makeBacktestTrades(daysWindow: number) {
  const out: { entry_time: string; exit_time: string; profit_ratio: number }[] = [];
  for (let i = 0; i < 8; i++) {
    const startH = (daysWindow - 1) * 24 - i * 18;
    const endH = startH - 3;
    const win = i % 4 !== 2;
    out.push({
      entry_time: hoursAgo(Math.max(4, startH)),
      exit_time: hoursAgo(Math.max(1, endH)),
      profit_ratio: win ? 0.018 : -0.011,
    });
  }
  return out;
}

const backtests: Backtest[] = [
  {
    id: '66666666-6666-4666-8666-666666666601',
    tenantId: DEFAULT_TENANT_ID,
    strategyId: STRAT_BTC_ID,
    timeframe: '5m',
    pairs: ['B-BTC_USDT'],
    rangeStart: daysAgo(5),
    rangeEnd: new Date(nowMs).toISOString(),
    status: 'DONE',
    metrics: {
      profit_total_pct: 14.8,
      trades: 42,
      wins: 29,
      win_rate: 0.69,
      max_drawdown_pct: 4.2,
      profit_factor: 1.85,
      sharpe: 2.14,
      timeframe: '5m',
    },
    trades: makeBacktestTrades(5),
    error: null,
    createdAt: daysAgo(5),
  },
  {
    id: '66666666-6666-4666-8666-666666666602',
    tenantId: DEFAULT_TENANT_ID,
    strategyId: STRAT_ETH_ID,
    timeframe: '5m',
    pairs: ['B-ETH_USDT'],
    rangeStart: daysAgo(5),
    rangeEnd: new Date(nowMs).toISOString(),
    status: 'DONE',
    metrics: {
      profit_total_pct: 9.4,
      trades: 36,
      wins: 22,
      win_rate: 0.611,
      max_drawdown_pct: 6.1,
      profit_factor: 1.52,
      sharpe: 1.68,
      timeframe: '5m',
    },
    trades: makeBacktestTrades(5),
    error: null,
    createdAt: daysAgo(3),
  },
  {
    id: '66666666-6666-4666-8666-666666666603',
    tenantId: DEFAULT_TENANT_ID,
    strategyId: STRAT_SOL_ID,
    timeframe: '5m',
    pairs: ['B-SOL_USDT'],
    rangeStart: daysAgo(5),
    rangeEnd: new Date(nowMs).toISOString(),
    status: 'DONE',
    metrics: {
      profit_total_pct: 6.7,
      trades: 31,
      wins: 18,
      win_rate: 0.581,
      max_drawdown_pct: 7.4,
      profit_factor: 1.38,
      sharpe: 1.42,
      timeframe: '5m',
    },
    trades: makeBacktestTrades(5),
    error: null,
    createdAt: daysAgo(1),
  },
];

const bots: Bot[] = [
  {
    id: BOT_BTC_LIVE_ID,
    tenantId: DEFAULT_TENANT_ID,
    userId: DEFAULT_USER_ID,
    strategyId: STRAT_BTC_ID,
    exchangeKeyId: null,
    name: 'BTC INR Live Momentum',
    mode: 'LIVE',
    marketType: 'FUTURES',
    pairs: ['B-BTC_USDT'],
    stakeCurrency: 'INR',
    stakeAmount: 2500,
    maxOpenTrades: 3,
    leverage: 3,
    status: 'RUNNING',
    killSwitch: false,
    marginCurrency: 'INR',
    createdAt: daysAgo(4),
  },
  {
    id: BOT_ETH_PAPER_ID,
    tenantId: DEFAULT_TENANT_ID,
    userId: DEFAULT_USER_ID,
    strategyId: STRAT_ETH_ID,
    exchangeKeyId: null,
    name: 'ETH INR Paper Breakout',
    mode: 'PAPER',
    marketType: 'FUTURES',
    pairs: ['B-ETH_USDT'],
    stakeCurrency: 'INR',
    stakeAmount: 1000,
    maxOpenTrades: 3,
    leverage: 3,
    status: 'RUNNING',
    killSwitch: false,
    marginCurrency: 'INR',
    createdAt: daysAgo(3),
  },
  {
    id: BOT_SOL_PAPER_ID,
    tenantId: DEFAULT_TENANT_ID,
    userId: DEFAULT_USER_ID,
    strategyId: STRAT_SOL_ID,
    exchangeKeyId: null,
    name: 'SOL INR Paper MeanRev',
    mode: 'PAPER',
    marketType: 'FUTURES',
    pairs: ['B-SOL_USDT'],
    stakeCurrency: 'INR',
    stakeAmount: 1000,
    maxOpenTrades: 3,
    leverage: 3,
    status: 'RUNNING',
    killSwitch: false,
    marginCurrency: 'INR',
    createdAt: daysAgo(1),
  },
];

const positions: Position[] = [
  {
    id: '77777777-7777-4777-8777-777777777701',
    tenantId: DEFAULT_TENANT_ID,
    userId: DEFAULT_USER_ID,
    botId: BOT_BTC_LIVE_ID,
    strategyId: STRAT_BTC_ID,
    mode: 'LIVE',
    pair: 'B-BTC_USDT',
    side: 'LONG',
    quantity: 0.002,
    entryPrice: 91450,
    exitPrice: null,
    slPrice: 90260,
    targetPrice: 93280,
    status: 'OPEN',
    leverage: 3,
    realizedPnl: null,
    marginInr: 6218.6,
    marginCurrency: 'INR',
    openedAt: hoursAgo(3),
    closedAt: null,
  },
  {
    id: '77777777-7777-4777-8777-777777777702',
    tenantId: DEFAULT_TENANT_ID,
    userId: DEFAULT_USER_ID,
    botId: BOT_BTC_LIVE_ID,
    strategyId: STRAT_BTC_ID,
    mode: 'LIVE',
    pair: 'B-BTC_USDT',
    side: 'LONG',
    quantity: 0.002,
    entryPrice: 89800,
    exitPrice: 91250,
    slPrice: 88630,
    targetPrice: 91600,
    status: 'CLOSED',
    leverage: 3,
    realizedPnl: 295.8,
    marginInr: 6106.4,
    marginCurrency: 'INR',
    openedAt: hoursAgo(28),
    closedAt: hoursAgo(22),
  },
  {
    id: '77777777-7777-4777-8777-777777777703',
    tenantId: DEFAULT_TENANT_ID,
    userId: DEFAULT_USER_ID,
    botId: BOT_ETH_PAPER_ID,
    strategyId: STRAT_ETH_ID,
    mode: 'PAPER',
    pair: 'B-ETH_USDT',
    side: 'LONG',
    quantity: 0.05,
    entryPrice: 3120,
    exitPrice: null,
    slPrice: 3079.4,
    targetPrice: 3182.4,
    status: 'OPEN',
    leverage: 3,
    realizedPnl: null,
    marginInr: 5304,
    marginCurrency: 'INR',
    openedAt: hoursAgo(2),
    closedAt: null,
  },
  {
    id: '77777777-7777-4777-8777-777777777704',
    tenantId: DEFAULT_TENANT_ID,
    userId: DEFAULT_USER_ID,
    botId: BOT_ETH_PAPER_ID,
    strategyId: STRAT_ETH_ID,
    mode: 'PAPER',
    pair: 'B-ETH_USDT',
    side: 'SHORT',
    quantity: 0.05,
    entryPrice: 3185,
    exitPrice: 3122,
    slPrice: 3226.4,
    targetPrice: 3121.3,
    status: 'CLOSED',
    leverage: 3,
    realizedPnl: 321.3,
    marginInr: 5414.5,
    marginCurrency: 'INR',
    openedAt: hoursAgo(14),
    closedAt: hoursAgo(9),
  },
  {
    id: '77777777-7777-4777-8777-777777777705',
    tenantId: DEFAULT_TENANT_ID,
    userId: DEFAULT_USER_ID,
    botId: BOT_SOL_PAPER_ID,
    strategyId: STRAT_SOL_ID,
    mode: 'PAPER',
    pair: 'B-SOL_USDT',
    side: 'LONG',
    quantity: 0.8,
    entryPrice: 184.5,
    exitPrice: 188.2,
    slPrice: 182.1,
    targetPrice: 188.19,
    status: 'CLOSED',
    leverage: 3,
    realizedPnl: 301.92,
    marginInr: 5018.4,
    marginCurrency: 'INR',
    openedAt: hoursAgo(10),
    closedAt: hoursAgo(6),
  },
];

const orders: TradeOrder[] = [
  {
    id: '88888888-8888-4888-8888-888888888801',
    tenantId: DEFAULT_TENANT_ID,
    userId: DEFAULT_USER_ID,
    botId: BOT_BTC_LIVE_ID,
    strategyId: STRAT_BTC_ID,
    mode: 'LIVE',
    pair: 'B-BTC_USDT',
    side: 'BUY',
    orderType: 'MARKET',
    status: 'FILLED',
    price: 91450,
    avgPrice: 91450,
    quantity: 0.002,
    filledQty: 0.002,
    sizeInr: 18655.8,
    error: null,
    createdAt: hoursAgo(3),
  },
  {
    id: '88888888-8888-4888-8888-888888888802',
    tenantId: DEFAULT_TENANT_ID,
    userId: DEFAULT_USER_ID,
    botId: BOT_BTC_LIVE_ID,
    strategyId: STRAT_BTC_ID,
    mode: 'LIVE',
    pair: 'B-BTC_USDT',
    side: 'SELL',
    orderType: 'MARKET',
    status: 'FILLED',
    price: 91250,
    avgPrice: 91250,
    quantity: 0.002,
    filledQty: 0.002,
    sizeInr: 18615.0,
    error: null,
    createdAt: hoursAgo(22),
  },
  {
    id: '88888888-8888-4888-8888-888888888803',
    tenantId: DEFAULT_TENANT_ID,
    userId: DEFAULT_USER_ID,
    botId: BOT_ETH_PAPER_ID,
    strategyId: STRAT_ETH_ID,
    mode: 'PAPER',
    pair: 'B-ETH_USDT',
    side: 'BUY',
    orderType: 'MARKET',
    status: 'FILLED',
    price: 3120,
    avgPrice: 3120,
    quantity: 0.05,
    filledQty: 0.05,
    sizeInr: 15912.0,
    error: null,
    createdAt: hoursAgo(2),
  },
];

const auditLogs: AuditLog[] = [
  {
    id: '99999999-9999-4999-8999-999999999901',
    tenantId: DEFAULT_TENANT_ID,
    entityType: 'STRATEGY',
    entityId: STRAT_ETH_ID,
    action: 'AUTO_LIVE_SKIPPED_PAPER_GATE',
    details: {
      reason: 'Paper win rate progress: 34/100 closed trades (64.7% WR)',
    },
    createdAt: hoursAgo(1),
  },
  {
    id: '99999999-9999-4999-8999-999999999902',
    tenantId: DEFAULT_TENANT_ID,
    entityType: 'STRATEGY',
    entityId: STRAT_SOL_ID,
    action: 'AUTO_LIVE_SKIPPED_PAPER_GATE',
    details: {
      reason: 'Paper win rate progress: 18/100 closed trades (61.1% WR)',
    },
    createdAt: hoursAgo(2),
  },
];

// Synthetic paper stats baseline so progress bars reflect multi-trade history
const strategyPaperBaseline = new Map<
  string,
  { closedTrades: number; wins: number; totalPnl: number }
>([
  [STRAT_BTC_ID, { closedTrades: 104, wins: 71, totalPnl: 4820.5 }],
  [STRAT_ETH_ID, { closedTrades: 33, wins: 21, totalPnl: 1290.2 }],
  [STRAT_SOL_ID, { closedTrades: 17, wins: 10, totalPnl: 412.8 }],
]);

// ---------------------------------------------------------------------------
// CoinDCX Public Market Data Client (Real HTTP + Safe Cache & Fallback)
// ---------------------------------------------------------------------------

const FALLBACK_INSTRUMENTS = [
  'B-BTC_USDT',
  'B-ETH_USDT',
  'B-SOL_USDT',
  'B-XRP_USDT',
  'B-BNB_USDT',
  'B-DOGE_USDT',
  'B-ADA_USDT',
  'B-AVAX_USDT',
  'B-LINK_USDT',
  'B-SUI_USDT',
  'B-DOT_USDT',
  'B-NEAR_USDT',
];

let instrumentsCache: { list: string[]; fetchedAt: number } | null = null;
const priceCache = new Map<string, { price: number; fetchedAt: number }>();

function toFuturesResolution(timeframe: string): string {
  switch (timeframe) {
    case '1m':
    case '1':
      return '1';
    case '5m':
    case '5':
    case '15m':
    case '30m':
      return '5';
    case '1h':
    case '60':
    case '2h':
    case '4h':
      return '60';
    case '1d':
    case '1D':
      return '1D';
    default:
      return '60';
  }
}

async function fetchActiveInstruments(marginCurrency = 'INR'): Promise<string[]> {
  if (instrumentsCache && Date.now() - instrumentsCache.fetchedAt < 60_000) {
    return instrumentsCache.list;
  }
  try {
    const url = `https://api.coindcx.com/exchange/v1/derivatives/futures/data/active_instruments?margin_currency_short_name[]=${encodeURIComponent(marginCurrency)}`;
    const res = await fetch(url, { signal: AbortSignal.timeout(8000) });
    if (res.ok) {
      const data = (await res.json()) as unknown;
      if (Array.isArray(data) && data.length > 0) {
        const list = data.map((x) => String(x));
        instrumentsCache = { list, fetchedAt: Date.now() };
        return list;
      }
    }
  } catch {
    // Fallback below
  }
  return FALLBACK_INSTRUMENTS;
}

interface CandleDto {
  ts: string;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
}

function generateFallbackCandles(
  pair: string,
  resolution: string,
  fromMs: number,
  toMs: number,
  limit: number,
): CandleDto[] {
  const stepMs =
    resolution === '1'
      ? 60_000
      : resolution === '5'
        ? 300_000
        : resolution === '1D'
          ? 86_400_000
          : 3_600_000;
  const count = Math.min(Math.max(40, Math.floor((toMs - fromMs) / stepMs)), Math.min(limit, 300));
  const baseSeed = pair.includes('BTC')
    ? 91500
    : pair.includes('ETH')
      ? 3135
      : pair.includes('SOL')
        ? 186
        : 12;
  let price = baseSeed;
  const out: CandleDto[] = [];
  const start = toMs - count * stepMs;
  for (let i = 0; i < count; i++) {
    const t = Math.floor((start + i * stepMs) / stepMs) * stepMs;
    const wave = Math.sin((i + pair.length) * 0.35) * 0.004 + Math.cos(i * 0.13) * 0.002;
    const open = price;
    const close = Number((open * (1 + wave)).toFixed(6));
    const spread = Math.abs(close - open) + open * 0.0022;
    const high = Number((Math.max(open, close) + spread * 0.6).toFixed(6));
    const low = Number((Math.min(open, close) - spread * 0.6).toFixed(6));
    price = close;
    out.push({
      ts: new Date(t).toISOString(),
      open,
      high,
      low,
      close,
      volume: Number((100 + Math.abs(Math.sin(i)) * 450).toFixed(2)),
    });
  }
  return out;
}

async function fetchCandles(
  pair: string,
  timeframe: string,
  fromIso: string,
  toIso: string,
  limit: number,
): Promise<CandleDto[]> {
  const resolution = toFuturesResolution(timeframe);
  const fromMs = new Date(fromIso).getTime() || Date.now() - 5 * 86_400_000;
  const toMs = new Date(toIso).getTime() || Date.now();
  const fromSec = Math.floor(fromMs / 1000);
  const toSec = Math.floor(toMs / 1000);

  try {
    const url = `https://public.coindcx.com/market_data/candlesticks?pair=${encodeURIComponent(pair)}&from=${fromSec}&to=${toSec}&resolution=${resolution}&pcode=f`;
    const res = await fetch(url, { signal: AbortSignal.timeout(10000) });
    if (res.ok) {
      const body = (await res.json()) as {
        data?: Array<{
          time: number;
          open: number | string;
          high: number | string;
          low: number | string;
          close: number | string;
          volume: number | string;
        }>;
      };
      if (Array.isArray(body.data) && body.data.length > 0) {
        const dedup = new Map<number, CandleDto>();
        for (const c of body.data) {
          const tMs = Number(c.time);
          if (!Number.isFinite(tMs)) continue;
          dedup.set(tMs, {
            ts: new Date(tMs).toISOString(),
            open: Number(c.open),
            high: Number(c.high),
            low: Number(c.low),
            close: Number(c.close),
            volume: Number(c.volume || 0),
          });
        }
        const sorted = [...dedup.entries()]
          .sort((a, b) => a[0] - b[0])
          .map((e) => e[1]);
        const sliced = sorted.slice(-Math.min(limit, 2000));
        if (sliced.length > 0) {
          const last = sliced[sliced.length - 1];
          priceCache.set(pair, { price: last.close, fetchedAt: Date.now() });
          return sliced;
        }
      }
    }
  } catch {
    // Fallback below
  }

  const fb = generateFallbackCandles(pair, resolution, fromMs, toMs, limit);
  if (fb.length > 0) {
    priceCache.set(pair, { price: fb[fb.length - 1].close, fetchedAt: Date.now() });
  }
  return fb;
}

async function getLastPrice(pair: string, fallback: number): Promise<number> {
  const cached = priceCache.get(pair);
  if (cached && Date.now() - cached.fetchedAt < 10_000) return cached.price;
  const to = new Date();
  const from = new Date(to.getTime() - 2 * 3_600_000);
  const candles = await fetchCandles(pair, '5m', from.toISOString(), to.toISOString(), 10);
  if (candles.length > 0) return candles[candles.length - 1].close;
  return fallback;
}

const USDT_INR_SETTLE = 102;

function computePaperProgress(strategyId: string) {
  const base = strategyPaperBaseline.get(strategyId) ?? {
    closedTrades: 0,
    wins: 0,
    totalPnl: 0,
  };
  const liveRows = positions.filter(
    (p) => p.strategyId === strategyId && p.mode === 'PAPER',
  );
  const extraClosed = liveRows.filter((p) => p.status === 'CLOSED');
  const openPositions = liveRows.filter((p) => p.status === 'OPEN').length;
  const closedTrades = base.closedTrades + extraClosed.length;
  const wins =
    base.wins +
    extraClosed.filter((p) => (p.realizedPnl ?? 0) > 0).length;
  const totalPnl =
    base.totalPnl +
    extraClosed.reduce((sum, p) => sum + (p.realizedPnl ?? 0), 0);
  return {
    closedTrades,
    wins,
    winRate: closedTrades === 0 ? 0 : wins / closedTrades,
    totalPnl: Number(totalPnl.toFixed(2)),
    requiredTrades: 100,
    requiredWinRate: 0.6,
    openPositions,
  };
}

function toStrategyView(s: Strategy, includeCode = false) {
  return {
    id: s.id,
    tenantId: s.tenantId,
    name: s.name,
    version: s.version,
    status: s.status,
    origin: s.origin,
    sourceCode: includeCode ? s.sourceCode : '',
    config: s.config,
    prompt: null,
    marketType: s.marketType,
    instrument: s.instrument,
    marginCurrency: s.marginCurrency,
    createdAt: s.createdAt,
    paper: computePaperProgress(s.id),
  };
}

// ---------------------------------------------------------------------------
// Express App & Auth Middleware
// ---------------------------------------------------------------------------

const app = express();
app.use(express.json({ limit: '1mb' }));

interface AuthedRequest extends Request {
  principal?: AuthPrincipal;
}

function requireAuth(req: AuthedRequest, res: Response, next: NextFunction) {
  const header = req.headers.authorization;
  if (!header || !header.startsWith('Bearer ')) {
    res.status(401).json({ message: 'Unauthorized' });
    return;
  }
  const principal = verifyToken(header.slice(7));
  if (!principal) {
    res.status(401).json({ message: 'Invalid or expired token' });
    return;
  }
  req.principal = principal;
  next();
}

function requireAdmin(req: AuthedRequest, res: Response, next: NextFunction) {
  if (
    req.principal?.role !== 'SUPER_ADMIN' &&
    req.principal?.role !== 'TENANT_ADMIN'
  ) {
    res.status(403).json({ message: 'Forbidden' });
    return;
  }
  next();
}

// Health check
app.get('/api/health', (_req, res) => {
  res.json({ ok: true, mode: 'nodejs-in-memory' });
});

// Auth routes
app.post('/api/v1/auth/login', (req, res) => {
  const email = String(req.body?.email || '').toLowerCase().trim();
  const password = String(req.body?.password || '');
  const user = users.find(
    (u) =>
      u.email === email &&
      u.status === 'ACTIVE' &&
      u.passwordHash === hashPassword(password),
  );
  if (!user) {
    res.status(401).json({ message: 'Invalid credentials' });
    return;
  }
  const principal: AuthPrincipal = {
    userId: user.id,
    tenantId: user.tenantId,
    email: user.email,
    role: user.role,
  };
  const accessToken = signToken(principal, 1800);
  const refreshToken = crypto.randomBytes(32).toString('base64url');
  refreshTokens.set(refreshToken, {
    userId: user.id,
    expiresAt: Date.now() + 14 * 86_400_000,
    revoked: false,
  });
  res.json({
    accessToken,
    refreshToken,
    user: {
      id: user.id,
      tenantId: user.tenantId,
      email: user.email,
      displayName: user.displayName,
      role: user.role,
    },
  });
});

app.post('/api/v1/auth/refresh', (req, res) => {
  const token = String(req.body?.refreshToken || '');
  const rec = refreshTokens.get(token);
  if (!rec || rec.revoked || rec.expiresAt < Date.now()) {
    res.status(401).json({ message: 'Invalid refresh token' });
    return;
  }
  rec.revoked = true;
  const user = users.find((u) => u.id === rec.userId && u.status === 'ACTIVE');
  if (!user) {
    res.status(401).json({ message: 'User disabled' });
    return;
  }
  const principal: AuthPrincipal = {
    userId: user.id,
    tenantId: user.tenantId,
    email: user.email,
    role: user.role,
  };
  const accessToken = signToken(principal, 1800);
  const refreshToken = crypto.randomBytes(32).toString('base64url');
  refreshTokens.set(refreshToken, {
    userId: user.id,
    expiresAt: Date.now() + 14 * 86_400_000,
    revoked: false,
  });
  res.json({
    accessToken,
    refreshToken,
    user: {
      id: user.id,
      tenantId: user.tenantId,
      email: user.email,
      displayName: user.displayName,
      role: user.role,
    },
  });
});

app.get('/api/v1/auth/me', requireAuth, (req: AuthedRequest, res) => {
  res.json(req.principal);
});

// Market routes (real CoinDCX public API + fallback)
app.get('/api/v1/market/futures/instruments', async (req, res) => {
  const marginCurrency = String(req.query.marginCurrency || 'INR');
  const instruments = await fetchActiveInstruments(marginCurrency);
  res.json({
    marginCurrency,
    count: instruments.length,
    instruments,
  });
});

app.get('/api/v1/market/candles', async (req, res) => {
  const pair = String(req.query.pair || 'B-BTC_USDT');
  const timeframe = String(req.query.timeframe || '1h');
  const from = String(req.query.from || daysAgo(5));
  const to = String(req.query.to || new Date().toISOString());
  const limit = Number(req.query.limit || 2000);
  const candles = await fetchCandles(pair, timeframe, from, to, limit);
  res.json(candles);
});

// Portfolio routes
async function enrichPosition(pos: Position) {
  if (pos.status !== 'OPEN') {
    return {
      ...pos,
      pnl: pos.realizedPnl,
      unrealizedPnl: null,
      markPrice: null,
      sizeInr: pos.exitPrice
        ? Number((pos.quantity * pos.exitPrice * USDT_INR_SETTLE).toFixed(2))
        : null,
      roePct: null,
    };
  }
  const mark = await getLastPrice(pos.pair, pos.entryPrice);
  const sign = pos.side === 'SHORT' ? -1 : 1;
  const unrealized = Number(
    ((mark - pos.entryPrice) * pos.quantity * sign * USDT_INR_SETTLE).toFixed(2),
  );
  const sizeInr = Number((pos.quantity * mark * USDT_INR_SETTLE).toFixed(2));
  const marginInr = pos.marginInr ?? Number((sizeInr / Math.max(1, pos.leverage)).toFixed(2));
  const roePct =
    marginInr > 0 ? Number(((unrealized * 100) / marginInr).toFixed(2)) : 0;
  return {
    ...pos,
    markPrice: mark,
    unrealizedPnl: unrealized,
    pnl: unrealized,
    marginInr,
    sizeInr,
    roePct,
  };
}

app.get('/api/v1/portfolio/positions', requireAuth, async (req: AuthedRequest, res) => {
  const mode = String(req.query.mode || 'LIVE').toUpperCase();
  const list = positions
    .filter((p) => p.tenantId === req.principal!.tenantId && p.mode === mode)
    .sort((a, b) => new Date(b.openedAt).getTime() - new Date(a.openedAt).getTime());
  const enriched = await Promise.all(list.map(enrichPosition));
  res.json(enriched);
});

app.get('/api/v1/portfolio/orders', requireAuth, async (req: AuthedRequest, res) => {
  const mode = String(req.query.mode || 'LIVE').toUpperCase();
  const list = orders
    .filter((o) => o.tenantId === req.principal!.tenantId && o.mode === mode)
    .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
  res.json(list);
});

app.get('/api/v1/portfolio/summary', requireAuth, async (req: AuthedRequest, res) => {
  const mode = String(req.query.mode || 'LIVE').toUpperCase();
  const list = positions.filter(
    (p) => p.tenantId === req.principal!.tenantId && p.mode === mode,
  );
  const enriched = await Promise.all(list.map(enrichPosition));
  const openPositions = enriched.filter((p) => p.status === 'OPEN').length;
  const closed = enriched.filter((p) => p.status === 'CLOSED');
  const wins = closed.filter((p) => (p.realizedPnl ?? 0) > 0).length;
  const realizedPnl = closed.reduce((s, p) => s + (p.realizedPnl ?? 0), 0);
  const unrealizedPnl = enriched
    .filter((p) => p.status === 'OPEN')
    .reduce((s, p) => s + (p.unrealizedPnl ?? 0), 0);
  res.json({
    mode,
    openPositions,
    closedPositions: closed.length,
    realizedPnl: Number(realizedPnl.toFixed(2)),
    unrealizedPnl: Number(unrealizedPnl.toFixed(2)),
    winRate: closed.length === 0 ? 0 : wins / closed.length,
  });
});

app.get('/api/v1/portfolio/wallet', requireAuth, async (req: AuthedRequest, res) => {
  const openLive = await Promise.all(
    positions
      .filter(
        (p) =>
          p.tenantId === req.principal!.tenantId &&
          p.mode === 'LIVE' &&
          p.status === 'OPEN',
      )
      .map(enrichPosition),
  );
  const activePnl = Number(
    openLive.reduce((s, p) => s + (p.unrealizedPnl ?? 0), 0).toFixed(2),
  );
  const locked = Number(
    openLive.reduce((s, p) => s + (p.marginInr ?? 0), 0).toFixed(2),
  );
  const available = 42500.0;
  const walletBalance = Number((available + locked).toFixed(2));
  const currentValue = Number((walletBalance + activePnl).toFixed(2));
  res.json({
    currency: 'INR',
    available,
    locked,
    walletBalance,
    walletEquity: walletBalance,
    activePnl,
    currentValue,
    usdtAvailable: 0,
    usdtValueInr: 0,
    estTotalFutures: currentValue,
    futuresWalletBalance: walletBalance,
    source: 'Simulated INR futures wallet (in-memory)',
  });
});

// Strategies & Backtests
app.get('/api/v1/strategies', requireAuth, (req: AuthedRequest, res) => {
  const mt = String(req.query.marketType || 'FUTURES').toUpperCase();
  const includeCode = req.query.includeCode === 'true';
  const list = strategies
    .filter(
      (s) =>
        s.tenantId === req.principal!.tenantId &&
        s.marketType === mt &&
        s.status !== 'REJECTED' &&
        s.status !== 'ARCHIVED',
    )
    .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
    .map((s) => toStrategyView(s, includeCode));
  res.json(list);
});

app.get('/api/v1/strategies/:id', requireAuth, (req: AuthedRequest, res) => {
  const s = strategies.find(
    (x) => x.id === req.params.id && x.tenantId === req.principal!.tenantId,
  );
  if (!s) {
    res.status(404).json({ message: 'Strategy not found' });
    return;
  }
  res.json(toStrategyView(s, true));
});

app.get('/api/v1/strategies/:id/trades', requireAuth, (req: AuthedRequest, res) => {
  const s = strategies.find(
    (x) => x.id === req.params.id && x.tenantId === req.principal!.tenantId,
  );
  if (!s) {
    res.status(404).json({ message: 'Strategy not found' });
    return;
  }
  const mode = req.query.mode ? String(req.query.mode).toUpperCase() : null;
  const list = positions
    .filter((p) => p.strategyId === s.id && (!mode || p.mode === mode))
    .sort((a, b) => new Date(b.openedAt).getTime() - new Date(a.openedAt).getTime());
  res.json(list);
});

app.get('/api/v1/strategies/:id/orders', requireAuth, (req: AuthedRequest, res) => {
  const s = strategies.find(
    (x) => x.id === req.params.id && x.tenantId === req.principal!.tenantId,
  );
  if (!s) {
    res.status(404).json({ message: 'Strategy not found' });
    return;
  }
  const mode = req.query.mode ? String(req.query.mode).toUpperCase() : null;
  const list = orders
    .filter((o) => o.strategyId === s.id && (!mode || o.mode === mode))
    .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
  res.json(list);
});

app.get('/api/v1/strategies/:id/live-skips', requireAuth, (req: AuthedRequest, res) => {
  const list = auditLogs
    .filter(
      (a) =>
        a.tenantId === req.principal!.tenantId &&
        a.entityId === req.params.id &&
        a.action.startsWith('AUTO_LIVE_SKIPPED'),
    )
    .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
  res.json(list);
});

app.post('/api/v1/strategies/:id/approve-live', requireAuth, (req: AuthedRequest, res) => {
  const s = strategies.find(
    (x) => x.id === req.params.id && x.tenantId === req.principal!.tenantId,
  );
  if (!s) {
    res.status(404).json({ message: 'Strategy not found' });
    return;
  }
  const paper = computePaperProgress(s.id);
  if (paper.closedTrades < paper.requiredTrades || paper.winRate < paper.requiredWinRate) {
    res.status(400).json({
      message: `Paper gate not met (${paper.closedTrades}/${paper.requiredTrades} trades, ${(paper.winRate * 100).toFixed(1)}% WR)`,
    });
    return;
  }
  s.status = 'LIVE_APPROVED';
  res.json({ ok: true, status: s.status, reason: null, paper });
});

app.post('/api/v1/strategies/generate', requireAuth, (req: AuthedRequest, res) => {
  const pairs: string[] = Array.isArray(req.body?.pairs) && req.body.pairs.length
    ? req.body.pairs
    : ['B-BTC_USDT'];
  const pair = pairs[0];
  const timeframe = String(req.body?.timeframe || '5m');
  const name = String(req.body?.name || `${pair.replace('B-', '').replace('_USDT', '')} AI Momentum`);
  const id = crypto.randomUUID();
  const strat: Strategy = {
    id,
    tenantId: req.principal!.tenantId,
    name,
    version: 1,
    status: 'PAPER_TRADING',
    origin: 'AI_PIPELINE',
    sourceCode: SAMPLE_STRATEGY_CODE(pair, name),
    marketType: 'FUTURES',
    instrument: pair,
    marginCurrency: 'INR',
    config: {
      timeframe,
      pairs,
      provider_used: 'GEMINI',
      model_used: 'gemini-flash-latest',
    },
    createdAt: new Date().toISOString(),
  };
  strategies.unshift(strat);
  backtests.unshift({
    id: crypto.randomUUID(),
    tenantId: req.principal!.tenantId,
    strategyId: id,
    timeframe,
    pairs,
    rangeStart: daysAgo(5),
    rangeEnd: new Date().toISOString(),
    status: 'DONE',
    metrics: {
      profit_total_pct: 8.2,
      trades: 34,
      wins: 21,
      win_rate: 0.618,
      max_drawdown_pct: 5.3,
      profit_factor: 1.48,
      sharpe: 1.72,
      timeframe,
    },
    trades: makeBacktestTrades(5),
    error: null,
    createdAt: new Date().toISOString(),
  });
  res.json(toStrategyView(strat, true));
});

app.get('/api/v1/backtests', requireAuth, (req: AuthedRequest, res) => {
  const strategyId = String(req.query.strategyId || '');
  const list = backtests
    .filter(
      (b) =>
        b.tenantId === req.principal!.tenantId &&
        (!strategyId || b.strategyId === strategyId),
    )
    .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
  res.json(list);
});

app.get('/api/v1/backtests/:id', requireAuth, (req: AuthedRequest, res) => {
  const bt = backtests.find(
    (b) => b.id === req.params.id && b.tenantId === req.principal!.tenantId,
  );
  if (!bt) {
    res.status(404).json({ message: 'Backtest not found' });
    return;
  }
  res.json(bt);
});

// Bots
app.get('/api/v1/bots', requireAuth, (req: AuthedRequest, res) => {
  const mt = req.query.marketType ? String(req.query.marketType).toUpperCase() : 'FUTURES';
  const mode = req.query.mode ? String(req.query.mode).toUpperCase() : null;
  const list = bots.filter(
    (b) =>
      b.tenantId === req.principal!.tenantId &&
      b.marketType === mt &&
      (!mode || b.mode === mode),
  );
  res.json(list);
});

app.post('/api/v1/bots', requireAuth, (_req, res) => {
  res.status(400).json({
    message: 'Manual bot creation is disabled. Futures bots are created by the auto strategy pipeline.',
  });
});

app.post('/api/v1/bots/:id/start', requireAuth, (req: AuthedRequest, res) => {
  const bot = bots.find(
    (b) => b.id === req.params.id && b.tenantId === req.principal!.tenantId,
  );
  if (!bot) {
    res.status(404).json({ message: 'Bot not found' });
    return;
  }
  bot.status = 'RUNNING';
  res.json(bot);
});

app.post('/api/v1/bots/:id/stop', requireAuth, (req: AuthedRequest, res) => {
  const bot = bots.find(
    (b) => b.id === req.params.id && b.tenantId === req.principal!.tenantId,
  );
  if (!bot) {
    res.status(404).json({ message: 'Bot not found' });
    return;
  }
  bot.status = 'STOPPED';
  res.json(bot);
});

app.post('/api/v1/bots/:id/kill-switch', requireAuth, (req: AuthedRequest, res) => {
  const bot = bots.find(
    (b) => b.id === req.params.id && b.tenantId === req.principal!.tenantId,
  );
  if (!bot) {
    res.status(404).json({ message: 'Bot not found' });
    return;
  }
  bot.killSwitch = req.query.enabled === 'true';
  if (bot.killSwitch) bot.status = 'STOPPED';
  res.json(bot);
});

app.post('/api/v1/bots/stop-all-futures', requireAuth, (req: AuthedRequest, res) => {
  let stopped = 0;
  for (const b of bots) {
    if (
      b.tenantId === req.principal!.tenantId &&
      b.marketType === 'FUTURES' &&
      b.status === 'RUNNING'
    ) {
      b.status = 'STOPPED';
      b.killSwitch = true;
      stopped++;
    }
  }
  res.json({ stopped });
});

// Exchange API keys
app.get('/api/v1/keys', requireAuth, (req: AuthedRequest, res) => {
  const list = exchangeKeys
    .filter(
      (k) => k.tenantId === req.principal!.tenantId && k.userId === req.principal!.userId,
    )
    .map((k) => ({
      id: k.id,
      exchange: k.exchange,
      label: k.label,
      keyLast4: k.keyLast4,
      status: k.status,
      createdAt: k.createdAt,
    }));
  res.json(list);
});

app.post('/api/v1/keys', requireAuth, (req: AuthedRequest, res) => {
  const label = String(req.body?.label || '').trim();
  const apiKey = String(req.body?.apiKey || '').trim();
  const apiSecret = String(req.body?.apiSecret || '').trim();
  if (!label || !apiKey || !apiSecret) {
    res.status(400).json({ message: 'label, apiKey, and apiSecret are required' });
    return;
  }
  const key: ExchangeKey = {
    id: crypto.randomUUID(),
    tenantId: req.principal!.tenantId,
    userId: req.principal!.userId,
    exchange: 'COINDCX',
    label,
    apiKeyEnc: hashPassword(apiKey),
    apiSecretEnc: hashPassword(apiSecret),
    keyLast4: apiKey.slice(-4),
    status: 'ACTIVE',
    createdAt: new Date().toISOString(),
  };
  exchangeKeys.unshift(key);
  res.json({
    id: key.id,
    exchange: key.exchange,
    label: key.label,
    keyLast4: key.keyLast4,
    status: key.status,
    createdAt: key.createdAt,
  });
});

app.delete('/api/v1/keys/:id', requireAuth, (req: AuthedRequest, res) => {
  const idx = exchangeKeys.findIndex(
    (k) => k.id === req.params.id && k.tenantId === req.principal!.tenantId,
  );
  if (idx === -1) {
    res.status(404).json({ message: 'Key not found' });
    return;
  }
  exchangeKeys.splice(idx, 1);
  res.status(204).end();
});

// AI Providers & Health
app.get('/api/v1/ai/providers/catalog', requireAuth, (_req, res) => {
  res.json(PROVIDER_CATALOG);
});

app.get('/api/v1/ai/providers', requireAuth, (req: AuthedRequest, res) => {
  res.json(aiProviders.filter((p) => p.tenantId === req.principal!.tenantId));
});

app.post('/api/v1/ai/providers', requireAuth, requireAdmin, (req: AuthedRequest, res) => {
  const providerType = String(req.body?.providerType || '');
  const apiKey = String(req.body?.apiKey || '').trim();
  const preset = PROVIDER_CATALOG.find((p) => p.type === providerType);
  if (!preset) {
    res.status(400).json({ message: 'Unknown provider type' });
    return;
  }
  if (!apiKey) {
    res.status(400).json({ message: 'apiKey is required' });
    return;
  }
  const existing = aiProviders.find(
    (p) => p.tenantId === req.principal!.tenantId && p.providerType === providerType,
  );
  if (existing) {
    existing.apiKeyLast4 = apiKey.slice(-4);
    existing.enabled = req.body?.enabled ?? true;
    res.json(existing);
    return;
  }
  const created: AiProvider = {
    id: crypto.randomUUID(),
    tenantId: req.principal!.tenantId,
    providerType,
    displayName: preset.displayName,
    models: preset.models,
    priority: Number(req.body?.priority ?? 100),
    enabled: req.body?.enabled ?? true,
    apiKeyLast4: apiKey.slice(-4),
    createdAt: new Date().toISOString(),
  };
  aiProviders.push(created);
  res.json(created);
});

app.put('/api/v1/ai/providers/:id', requireAuth, requireAdmin, (req: AuthedRequest, res) => {
  const p = aiProviders.find(
    (x) => x.id === req.params.id && x.tenantId === req.principal!.tenantId,
  );
  if (!p) {
    res.status(404).json({ message: 'Provider not found' });
    return;
  }
  if (typeof req.body?.enabled === 'boolean') p.enabled = req.body.enabled;
  if (typeof req.body?.priority === 'number') p.priority = req.body.priority;
  if (typeof req.body?.apiKey === 'string' && req.body.apiKey.trim()) {
    p.apiKeyLast4 = req.body.apiKey.trim().slice(-4);
  }
  res.json(p);
});

app.delete('/api/v1/ai/providers/:id', requireAuth, requireAdmin, (req: AuthedRequest, res) => {
  const idx = aiProviders.findIndex(
    (x) => x.id === req.params.id && x.tenantId === req.principal!.tenantId,
  );
  if (idx === -1) {
    res.status(404).json({ message: 'Provider not found' });
    return;
  }
  aiProviders.splice(idx, 1);
  res.status(204).end();
});

app.get('/api/v1/ai/health', requireAuth, (_req, res) => {
  res.json({
    rateLimited: false,
    message: 'AI providers healthy',
    lastAt: null,
    recentRateLimitEvents: 0,
  });
});

app.get('/api/v1/alerts', requireAuth, (req: AuthedRequest, res) => {
  res.json(
    auditLogs
      .filter((a) => a.tenantId === req.principal!.tenantId)
      .slice(0, 40)
      .map((a) => ({
        id: a.id,
        action: a.action,
        title: a.action.replace(/_/g, ' '),
        body: String(a.details.reason || ''),
        details: a.details,
        createdAt: a.createdAt,
      })),
  );
});

// Admin Users & Tenants
app.get('/api/v1/admin/users', requireAuth, requireAdmin, (req: AuthedRequest, res) => {
  res.json(
    users
      .filter((u) => u.tenantId === req.principal!.tenantId)
      .map((u) => ({
        id: u.id,
        email: u.email,
        displayName: u.displayName,
        role: u.role,
        status: u.status,
        createdAt: u.createdAt,
      })),
  );
});

app.post('/api/v1/admin/users', requireAuth, requireAdmin, (req: AuthedRequest, res) => {
  const email = String(req.body?.email || '').toLowerCase().trim();
  const password = String(req.body?.password || '');
  const displayName = String(req.body?.displayName || email);
  const role = String(req.body?.role || 'TRADER') as User['role'];
  if (!email || password.length < 8) {
    res.status(400).json({ message: 'Valid email and password (min 8 chars) required' });
    return;
  }
  if (role !== 'TRADER' && role !== 'TENANT_ADMIN') {
    res.status(400).json({ message: 'role must be TENANT_ADMIN or TRADER' });
    return;
  }
  if (users.some((u) => u.tenantId === req.principal!.tenantId && u.email === email)) {
    res.status(409).json({ message: 'Email already exists in tenant' });
    return;
  }
  const u: User = {
    id: crypto.randomUUID(),
    tenantId: req.principal!.tenantId,
    email,
    passwordHash: hashPassword(password),
    displayName,
    role,
    status: 'ACTIVE',
    createdAt: new Date().toISOString(),
  };
  users.push(u);
  res.json({
    id: u.id,
    email: u.email,
    displayName: u.displayName,
    role: u.role,
    status: u.status,
    createdAt: u.createdAt,
  });
});

app.get('/api/v1/admin/tenants', requireAuth, (req: AuthedRequest, res) => {
  if (req.principal?.role !== 'SUPER_ADMIN') {
    res.status(403).json({ message: 'Forbidden' });
    return;
  }
  res.json(tenants);
});

app.post('/api/v1/admin/tenants', requireAuth, (req: AuthedRequest, res) => {
  if (req.principal?.role !== 'SUPER_ADMIN') {
    res.status(403).json({ message: 'Forbidden' });
    return;
  }
  const name = String(req.body?.name || '').trim();
  const slug = String(req.body?.slug || '').trim().toLowerCase();
  const adminEmail = String(req.body?.adminEmail || '').toLowerCase().trim();
  const adminPassword = String(req.body?.adminPassword || '');
  const adminName = String(req.body?.adminName || `${name} Admin`);
  if (!name || !slug || !adminEmail || adminPassword.length < 8) {
    res.status(400).json({ message: 'All tenant fields and 8+ char password are required' });
    return;
  }
  if (tenants.some((t) => t.slug === slug)) {
    res.status(409).json({ message: 'Slug already in use' });
    return;
  }
  const tenant: Tenant = {
    id: crypto.randomUUID(),
    name,
    slug,
    status: 'ACTIVE',
    createdAt: new Date().toISOString(),
  };
  tenants.push(tenant);
  users.push({
    id: crypto.randomUUID(),
    tenantId: tenant.id,
    email: adminEmail,
    passwordHash: hashPassword(adminPassword),
    displayName: adminName,
    role: 'TENANT_ADMIN',
    status: 'ACTIVE',
    createdAt: new Date().toISOString(),
  });
  res.json(tenant);
});

// Fallback for unimplemented /api/* routes
app.all('/api/*', (_req, res) => {
  res.status(501).json({ error: 'Not yet migrated' });
});

// ---------------------------------------------------------------------------
// Frontend Serving (Vite Dev Middleware or Built Static Dist)
// ---------------------------------------------------------------------------

async function start() {
  const isProd = process.env.NODE_ENV === 'production';
  const distDir = path.resolve(__dirname, 'frontend/dist');

  if (!isProd || !fs.existsSync(distDir)) {
    const { createServer: createViteServer } = await import('vite');
    const vite = await createViteServer({
      configFile: path.resolve(__dirname, 'frontend/vite.config.ts'),
      root: path.resolve(__dirname, 'frontend'),
      server: {
        middlewareMode: true,
        hmr: false,
        allowedHosts: true,
      },
      appType: 'spa',
    });
    app.use(vite.middlewares);
  } else {
    app.use(express.static(distDir));
    app.get('*', (_req, res) => {
      res.sendFile(path.join(distDir, 'index.html'));
    });
  }

  app.listen(PORT, HOST, () => {
    console.log(`QuantDCX server listening on http://${HOST}:${PORT}`);
  });
}

start().catch((err) => {
  console.error('Failed to start server:', err);
  process.exit(1);
});
