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
const SUPERADMIN_EMAIL = 'admin@platform.local';
const SUPERADMIN_PASSWORD = 'ChangeMe123!';
const ENV_ADMIN_EMAIL = (process.env.SUPERADMIN_EMAIL || '').toLowerCase().trim();
const ENV_ADMIN_PASSWORD = process.env.SUPERADMIN_PASSWORD || '';

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
    style?: string;
    stoploss?: number;
    minimal_roi?: Record<string, number>;
    win_rate_target?: number;
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
  anchoredToLive?: boolean;
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

const KEY_ENC_SECRET = crypto
  .createHash('sha256')
  .update(process.env.MASTER_KEY || JWT_SECRET)
  .digest();

function encryptSecret(plaintext: string): string {
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', KEY_ENC_SECRET, iv);
  const enc = Buffer.concat([cipher.update(plaintext, 'utf8'), cipher.final()]);
  const tag = cipher.getAuthTag();
  return `${iv.toString('hex')}:${tag.toString('hex')}:${enc.toString('hex')}`;
}

function decryptSecret(ciphertext: string): string | null {
  try {
    const parts = ciphertext.split(':');
    if (parts.length !== 3) return null;
    const iv = Buffer.from(parts[0], 'hex');
    const tag = Buffer.from(parts[1], 'hex');
    const enc = Buffer.from(parts[2], 'hex');
    const decipher = crypto.createDecipheriv('aes-256-gcm', KEY_ENC_SECRET, iv);
    decipher.setAuthTag(tag);
    return Buffer.concat([decipher.update(enc), decipher.final()]).toString('utf8');
  } catch {
    return null;
  }
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
  ...(ENV_ADMIN_EMAIL &&
  ENV_ADMIN_PASSWORD &&
  ENV_ADMIN_EMAIL !== SUPERADMIN_EMAIL
    ? [
        {
          id: '22222222-2222-4222-8222-222222222202',
          tenantId: DEFAULT_TENANT_ID,
          email: ENV_ADMIN_EMAIL,
          passwordHash: hashPassword(ENV_ADMIN_PASSWORD),
          displayName: 'Env Super Admin',
          role: 'SUPER_ADMIN' as const,
          status: 'ACTIVE' as const,
          createdAt: daysAgo(14),
        },
      ]
    : []),
];

const refreshTokens = new Map<string, RefreshRecord>();

const aiProviders: AiProvider[] = [];
const exchangeKeys: ExchangeKey[] = [];

type StrategyStyle = 'trend-following' | 'breakout-squeeze' | 'mean-reversion' | 'donchian-adx';

function buildStrategySource(
  pair: string,
  name: string,
  style: StrategyStyle = 'trend-following',
  timeframe = '5m',
  stoploss = -0.015,
  roiTarget = 0.028,
): string {
  const cls = name.replace(/[^A-Za-z0-9]/g, '') || 'QuantStrategy';
  if (style === 'breakout-squeeze') {
    return `import numpy as np
import pandas as pd
import ta

class ${cls}:
    timeframe = "${timeframe}"
    stoploss = ${stoploss}
    minimal_roi = {"0": ${roiTarget}, "45": ${Number((roiTarget * 0.65).toFixed(4))}}
    pairs = ["${pair}"]

    def populate_indicators(self, df: pd.DataFrame) -> pd.DataFrame:
        bb = ta.volatility.BollingerBands(df["close"], window=20, window_dev=2.0)
        kc = ta.volatility.KeltnerChannel(df["high"], df["low"], df["close"], window=20, window_atr=10)
        df["bb_upper"] = bb.bollinger_hband()
        df["bb_lower"] = bb.bollinger_lband()
        df["kc_upper"] = kc.keltner_channel_hband()
        df["kc_lower"] = kc.keltner_channel_lband()
        df["squeeze"] = (df["bb_upper"] < df["kc_upper"]) & (df["bb_lower"] > df["kc_lower"])
        df["adx"] = ta.trend.adx(df["high"], df["low"], df["close"], window=14)
        df["atr_pct"] = ta.volatility.average_true_range(df["high"], df["low"], df["close"], window=14) / df["close"]
        df["vol_ma"] = df["volume"].rolling(20).mean()
        return df

    def populate_entry_trend(self, df: pd.DataFrame) -> pd.DataFrame:
        released = df["squeeze"].shift(1).fillna(False) & (~df["squeeze"])
        vol_ok = df["volume"] > df["vol_ma"] * 1.35
        regime_ok = (df["adx"] >= 20) & (df["atr_pct"] > 0.0018) & (df["atr_pct"] < 0.045)
        df["enter_long"] = released & regime_ok & vol_ok & (df["close"] > df["bb_upper"])
        df["enter_short"] = released & regime_ok & vol_ok & (df["close"] < df["bb_lower"])
        return df

    def populate_exit_trend(self, df: pd.DataFrame) -> pd.DataFrame:
        df["exit_long"] = df["close"] < df["kc_lower"]
        df["exit_short"] = df["close"] > df["kc_upper"]
        return df
`;
  }

  if (style === 'mean-reversion') {
    return `import numpy as np
import pandas as pd
import ta

class ${cls}:
    timeframe = "${timeframe}"
    stoploss = ${stoploss}
    minimal_roi = {"0": ${roiTarget}, "30": ${Number((roiTarget * 0.6).toFixed(4))}}
    pairs = ["${pair}"]

    def populate_indicators(self, df: pd.DataFrame) -> pd.DataFrame:
        tp = (df["high"] + df["low"] + df["close"]) / 3.0
        pv = tp * df["volume"]
        df["vwap"] = pv.rolling(48).sum() / df["volume"].rolling(48).sum().replace(0, np.nan)
        df["vwap_z"] = (df["close"] - df["vwap"]) / df["close"].rolling(48).std().replace(0, np.nan)
        stoch = ta.momentum.StochRSIIndicator(df["close"], window=14, smooth1=3, smooth2=3)
        df["stoch_k"] = stoch.stochrsi_k() * 100.0
        df["stoch_d"] = stoch.stochrsi_d() * 100.0
        df["adx"] = ta.trend.adx(df["high"], df["low"], df["close"], window=14)
        df["atr_pct"] = ta.volatility.average_true_range(df["high"], df["low"], df["close"], window=14) / df["close"]
        return df

    def populate_entry_trend(self, df: pd.DataFrame) -> pd.DataFrame:
        range_regime = (df["adx"] < 26) & (df["atr_pct"] > 0.0015) & (df["atr_pct"] < 0.038)
        df["enter_long"] = range_regime & (df["vwap_z"] < -1.45) & (df["stoch_k"] < 24) & (df["stoch_k"] > df["stoch_d"])
        df["enter_short"] = range_regime & (df["vwap_z"] > 1.45) & (df["stoch_k"] > 76) & (df["stoch_k"] < df["stoch_d"])
        return df

    def populate_exit_trend(self, df: pd.DataFrame) -> pd.DataFrame:
        df["exit_long"] = df["vwap_z"] >= 0.1
        df["exit_short"] = df["vwap_z"] <= -0.1
        return df
`;
  }

  if (style === 'donchian-adx') {
    return `import numpy as np
import pandas as pd
import ta

class ${cls}:
    timeframe = "${timeframe}"
    stoploss = ${stoploss}
    minimal_roi = {"0": ${roiTarget}, "60": ${Number((roiTarget * 0.65).toFixed(4))}}
    pairs = ["${pair}"]

    def populate_indicators(self, df: pd.DataFrame) -> pd.DataFrame:
        df["dc_high"] = df["high"].rolling(20).max().shift(1)
        df["dc_low"] = df["low"].rolling(20).min().shift(1)
        df["ema_trend"] = ta.trend.ema_indicator(df["close"], window=50)
        df["adx"] = ta.trend.adx(df["high"], df["low"], df["close"], window=14)
        df["vol_ma"] = df["volume"].rolling(20).mean()
        return df

    def populate_entry_trend(self, df: pd.DataFrame) -> pd.DataFrame:
        regime = (df["adx"] >= 24) & (df["volume"] > df["vol_ma"] * 1.2)
        df["enter_long"] = regime & (df["close"] > df["dc_high"]) & (df["close"] > df["ema_trend"])
        df["enter_short"] = regime & (df["close"] < df["dc_low"]) & (df["close"] < df["ema_trend"])
        return df

    def populate_exit_trend(self, df: pd.DataFrame) -> pd.DataFrame:
        df["exit_long"] = df["close"] < df["ema_trend"]
        df["exit_short"] = df["close"] > df["ema_trend"]
        return df
`;
  }

  return `import numpy as np
import pandas as pd
import ta

class ${cls}:
    timeframe = "${timeframe}"
    stoploss = ${stoploss}
    minimal_roi = {"0": ${roiTarget}, "45": ${Number((roiTarget * 0.65).toFixed(4))}}
    pairs = ["${pair}"]

    def populate_indicators(self, df: pd.DataFrame) -> pd.DataFrame:
        df["ema_fast"] = ta.trend.ema_indicator(df["close"], window=12)
        df["ema_slow"] = ta.trend.ema_indicator(df["close"], window=26)
        df["ema_macro"] = ta.trend.ema_indicator(df["close"], window=100)
        df["adx"] = ta.trend.adx(df["high"], df["low"], df["close"], window=14)
        df["rsi"] = ta.momentum.rsi(df["close"], window=14)
        df["atr_pct"] = ta.volatility.average_true_range(df["high"], df["low"], df["close"], window=14) / df["close"]
        df["vol_ma"] = df["volume"].rolling(20).mean()
        return df

    def populate_entry_trend(self, df: pd.DataFrame) -> pd.DataFrame:
        regime = (df["adx"] >= 22) & (df["atr_pct"] > 0.0018) & (df["atr_pct"] < 0.042)
        vol_ok = df["volume"] > df["vol_ma"] * 1.15
        df["enter_long"] = regime & vol_ok & (df["ema_fast"] > df["ema_slow"]) & (df["close"] > df["ema_macro"]) & (df["rsi"] > 48) & (df["rsi"] < 68)
        df["enter_short"] = regime & vol_ok & (df["ema_fast"] < df["ema_slow"]) & (df["close"] < df["ema_macro"]) & (df["rsi"] < 52) & (df["rsi"] > 32)
        return df

    def populate_exit_trend(self, df: pd.DataFrame) -> pd.DataFrame:
        df["exit_long"] = (df["ema_fast"] < df["ema_slow"]) | (df["rsi"] > 78)
        df["exit_short"] = (df["ema_fast"] > df["ema_slow"]) | (df["rsi"] < 22)
        return df
`;
}

// Strict real-data stores: start empty with zero fabricated/dummy records.
const strategies: Strategy[] = [];
const backtests: Backtest[] = [];
const bots: Bot[] = [];
const positions: Position[] = [];
const orders: TradeOrder[] = [];
const auditLogs: AuditLog[] = [];

// ---------------------------------------------------------------------------
// CoinDCX Public & Private Signed Client
// ---------------------------------------------------------------------------

let instrumentsCache: { list: string[]; fetchedAt: number } | null = null;
const priceCache = new Map<string, { price: number; fetchedAt: number }>();
let usdtInrCache: { rate: number; fetchedAt: number } | null = null;

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
    // Upstream failure returns empty instead of fabricated instruments
  }
  return instrumentsCache?.list ?? [];
}

interface CandleDto {
  ts: string;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
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
    // Return empty rather than synthetic candles
  }
  return [];
}

async function getLastPrice(pair: string, fallback = 0): Promise<number> {
  const cached = priceCache.get(pair);
  if (cached && Date.now() - cached.fetchedAt < 5_000) return cached.price;
  const to = new Date();
  const from = new Date(to.getTime() - 2 * 3_600_000);
  const candles = await fetchCandles(pair, '1m', from.toISOString(), to.toISOString(), 15);
  if (candles.length > 0) return candles[candles.length - 1].close;
  const fallbackCandles = await fetchCandles(pair, '5m', from.toISOString(), to.toISOString(), 15);
  if (fallbackCandles.length > 0) return fallbackCandles[fallbackCandles.length - 1].close;
  return fallback;
}

async function getUsdtInrRate(): Promise<number> {
  if (usdtInrCache && Date.now() - usdtInrCache.fetchedAt < 10_000) {
    return usdtInrCache.rate;
  }
  try {
    const res = await fetch('https://api.coindcx.com/exchange/ticker', {
      signal: AbortSignal.timeout(8000),
    });
    if (res.ok) {
      const list = (await res.json()) as Array<{ market?: string; last_price?: string | number }>;
      if (Array.isArray(list)) {
        const hit = list.find((t) => String(t.market || '').toUpperCase() === 'USDTINR');
        const rate = Number(hit?.last_price || 0);
        if (Number.isFinite(rate) && rate > 0) {
          usdtInrCache = { rate, fetchedAt: Date.now() };
          return rate;
        }
      }
    }
  } catch {
    // Keep last known if recent
  }
  return usdtInrCache?.rate ?? 0;
}

function getActiveCoinDcxCredentials(tenantId: string, userId: string) {
  const key = exchangeKeys.find(
    (k) =>
      k.tenantId === tenantId &&
      k.userId === userId &&
      k.exchange === 'COINDCX' &&
      k.status === 'ACTIVE',
  );
  if (!key) return null;
  const apiKey = decryptSecret(key.apiKeyEnc);
  const apiSecret = decryptSecret(key.apiSecretEnc);
  if (!apiKey || !apiSecret) return null;
  return { key, apiKey, apiSecret };
}

async function coindcxSignedRequest<T>(
  method: 'GET' | 'POST',
  apiPath: string,
  payloadObj: Record<string, unknown>,
  apiKey: string,
  apiSecret: string,
): Promise<T> {
  const payloadJson = JSON.stringify(payloadObj);
  const signature = crypto
    .createHmac('sha256', apiSecret)
    .update(payloadJson)
    .digest('hex');
  const res = await fetch(`https://api.coindcx.com${apiPath}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      'X-AUTH-APIKEY': apiKey,
      'X-AUTH-SIGNATURE': signature,
    },
    body: payloadJson,
    signal: AbortSignal.timeout(15000),
  });
  const text = await res.text();
  let parsed: unknown = null;
  try {
    parsed = text ? JSON.parse(text) : null;
  } catch {
    parsed = { message: text };
  }
  if (!res.ok) {
    const errMsg =
      (parsed && typeof parsed === 'object' && 'message' in parsed
        ? String((parsed as { message?: unknown }).message || '')
        : '') || `CoinDCX API ${res.status} on ${apiPath}`;
    throw new Error(errMsg);
  }
  return parsed as T;
}

interface CoinDcxWalletRow {
  currency_short_name?: string;
  balance?: string | number;
  locked_balance?: string | number;
}

interface CoinDcxPositionRow {
  id?: string;
  pair?: string;
  active_pos?: string | number;
  avg_price?: string | number;
  mark_price?: string | number;
  locked_margin?: string | number;
  leverage?: string | number;
  stop_loss_price?: string | number | null;
  take_profit_price?: string | number | null;
  settlement_currency_avg_price?: string | number;
  margin_currency_short_name?: string;
  realized_pnl?: string | number | null;
  pnl?: string | number | null;
  updated_at?: string | number;
  created_at?: string | number;
}

interface CoinDcxOrderRow {
  id?: string;
  pair?: string;
  side?: string;
  order_type?: string;
  status?: string;
  price?: string | number;
  avg_price?: string | number;
  total_quantity?: string | number;
  remaining_quantity?: string | number;
  filled_quantity?: string | number;
  settlement_currency_avg_price?: string | number;
  fee_amount?: string | number;
  created_at?: string | number;
  updated_at?: string | number;
}

function toIsoTs(val: unknown): string {
  if (typeof val === 'number' && Number.isFinite(val)) {
    return new Date(val < 1e12 ? val * 1000 : val).toISOString();
  }
  if (typeof val === 'string' && val.trim()) {
    const num = Number(val);
    if (Number.isFinite(num) && num > 0) {
      return new Date(num < 1e12 ? num * 1000 : num).toISOString();
    }
    const d = new Date(val);
    if (!Number.isNaN(d.getTime())) return d.toISOString();
  }
  return new Date().toISOString();
}

async function fetchLiveCoinDcxPositions(apiKey: string, apiSecret: string) {
  const [raw, usdtInr] = await Promise.all([
    coindcxSignedRequest<CoinDcxPositionRow[]>(
      'POST',
      '/exchange/v1/derivatives/futures/positions',
      {
        timestamp: Date.now(),
        page: '1',
        size: '50',
        margin_currency_short_name: ['INR', 'USDT'],
      },
      apiKey,
      apiSecret,
    ),
    getUsdtInrRate(),
  ]);
  const list = Array.isArray(raw) ? raw : [];
  const mapped = await Promise.all(
    list
      .filter((n) => n && n.pair)
      .map(async (n) => {
        const pair = String(n.pair);
        const activePos = Number(n.active_pos || 0);
        const isOpen = Math.abs(activePos) > 0;
        const side = activePos < 0 ? 'SHORT' : 'LONG';
        const qty = Math.abs(activePos);
        const avgPrice = Number(n.avg_price || 0);
        const settle =
          Number(n.settlement_currency_avg_price || 0) ||
          (String(n.margin_currency_short_name || 'INR').toUpperCase() === 'USDT'
            ? usdtInr || 1
            : usdtInr);
        const exchangeMark = Number(n.mark_price || 0);
        const candleMark = isOpen ? await getLastPrice(pair, exchangeMark || avgPrice) : 0;
        const markPrice = candleMark > 0 ? candleMark : exchangeMark;
        const lockedUsdt = Number(n.locked_margin || 0);
        const marginInr = settle > 0 ? Number((lockedUsdt * settle).toFixed(2)) : null;
        const sizeInr =
          isOpen && markPrice > 0 && settle > 0
            ? Number((qty * markPrice * settle).toFixed(2))
            : null;
        const sign = activePos < 0 ? -1 : 1;
        const unrealizedPnl =
          isOpen && avgPrice > 0 && markPrice > 0 && settle > 0
            ? Number(((markPrice - avgPrice) * qty * sign * settle).toFixed(2))
            : null;
        const realizedRaw = n.realized_pnl ?? n.pnl;
        const realizedPnl =
          !isOpen && realizedRaw != null && Number.isFinite(Number(realizedRaw))
            ? Number((Number(realizedRaw) * (String(n.margin_currency_short_name || 'INR').toUpperCase() === 'USDT' ? usdtInr || 1 : 1)).toFixed(2))
            : null;
        const roePct =
          unrealizedPnl != null && marginInr != null && marginInr > 0
            ? Number(((unrealizedPnl * 100) / marginInr).toFixed(2))
            : null;
        return {
          id: String(n.id || `${pair}-${side}`),
          botId: null,
          strategyId: null,
          mode: 'LIVE',
          pair,
          side,
          quantity: qty,
          entryPrice: avgPrice,
          exitPrice: isOpen ? null : exchangeMark || null,
          slPrice: n.stop_loss_price != null ? Number(n.stop_loss_price) : null,
          targetPrice: n.take_profit_price != null ? Number(n.take_profit_price) : null,
          status: isOpen ? 'OPEN' : 'CLOSED',
          leverage: n.leverage != null ? Number(n.leverage) : null,
          realizedPnl,
          unrealizedPnl,
          pnl: isOpen ? unrealizedPnl : realizedPnl,
          markPrice: isOpen ? markPrice : null,
          marginInr,
          sizeInr,
          roePct,
          marginCurrency: String(n.margin_currency_short_name || 'INR').toUpperCase(),
          openedAt: toIsoTs(n.created_at ?? n.updated_at),
          closedAt: isOpen ? null : toIsoTs(n.updated_at ?? n.created_at),
        };
      }),
  );
  return mapped.filter((p) => p.status === 'OPEN' || p.realizedPnl != null);
}

async function fetchLiveCoinDcxOrders(apiKey: string, apiSecret: string) {
  const [resp, usdtInr] = await Promise.all([
    coindcxSignedRequest<
      CoinDcxOrderRow[] | { orders?: CoinDcxOrderRow[]; data?: CoinDcxOrderRow[] }
    >(
      'POST',
      '/exchange/v1/derivatives/futures/orders',
      {
        timestamp: Date.now(),
        page: '1',
        size: '50',
        margin_currency_short_name: ['INR', 'USDT'],
      },
      apiKey,
      apiSecret,
    ),
    getUsdtInrRate(),
  ]);
  const list = Array.isArray(resp)
    ? resp
    : Array.isArray(resp?.orders)
      ? resp.orders
      : Array.isArray(resp?.data)
        ? resp.data
        : [];
  return list.map((o) => {
    const qty = Number(o.total_quantity || 0);
    const rem = Number(o.remaining_quantity || 0);
    const filledQty =
      o.filled_quantity != null ? Number(o.filled_quantity) : Math.max(0, qty - rem);
    const avgPrice = Number(o.avg_price || 0) || Number(o.price || 0);
    const settle = Number(o.settlement_currency_avg_price || 0) || usdtInr;
    const sizeInr =
      qty > 0 && avgPrice > 0 && settle > 0
        ? Number((qty * avgPrice * settle).toFixed(2))
        : null;
    return {
      id: String(o.id || crypto.randomUUID()),
      botId: null,
      strategyId: null,
      mode: 'LIVE',
      pair: String(o.pair || ''),
      side: String(o.side || '').toUpperCase(),
      orderType: String(o.order_type || 'MARKET').toUpperCase(),
      status: String(o.status || 'OPEN').toUpperCase(),
      price: Number(o.price || avgPrice || 0),
      avgPrice: avgPrice || null,
      quantity: qty,
      filledQty,
      sizeInr,
      error: null,
      createdAt: toIsoTs(o.created_at ?? o.updated_at),
    };
  });
}

function computePaperProgress(strategyId: string) {
  const paperRows = positions.filter(
    (p) => p.strategyId === strategyId && p.mode === 'PAPER',
  );
  const closed = paperRows.filter((p) => p.status === 'CLOSED');
  const openPositions = paperRows.filter((p) => p.status === 'OPEN').length;
  const closedTrades = closed.length;
  const wins = closed.filter((p) => (p.realizedPnl ?? 0) > 0).length;
  const totalPnl = closed.reduce((sum, p) => sum + (p.realizedPnl ?? 0), 0);
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
app.use((req: Request, res: Response, next: NextFunction) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader(
    'Access-Control-Allow-Methods',
    'GET, POST, PUT, PATCH, DELETE, OPTIONS',
  );
  res.setHeader('Access-Control-Allow-Headers', 'Authorization, Content-Type');
  if (req.method === 'OPTIONS') {
    res.status(204).end();
    return;
  }
  next();
});
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

interface TickerSnapshot {
  pair: string;
  symbol: string;
  lastPrice: number;
  changePct: number;
  high24h: number;
  low24h: number;
  volume: number;
}

let tickerCache: { data: TickerSnapshot[]; fetchedAt: number } | null = null;
const TICKER_PAIRS = [
  'B-BTC_USDT',
  'B-ETH_USDT',
  'B-SOL_USDT',
  'B-XRP_USDT',
  'B-BNB_USDT',
  'B-DOGE_USDT',
];

app.get('/api/v1/market/tickers', async (_req, res) => {
  if (tickerCache && Date.now() - tickerCache.fetchedAt < 5000) {
    res.json(tickerCache.data);
    return;
  }
  const nowIso = new Date().toISOString();
  const fromIso = daysAgo(1);
  const rows = await Promise.all(
    TICKER_PAIRS.map(async (pair): Promise<TickerSnapshot | null> => {
      const candles = await fetchCandles(pair, '1h', fromIso, nowIso, 25);
      if (candles.length === 0) return null;
      const first = candles[0];
      const last = candles[candles.length - 1];
      const high24h = Math.max(...candles.map((c) => c.high));
      const low24h = Math.min(...candles.map((c) => c.low));
      const volume = candles.reduce((s, c) => s + (c.volume || 0), 0);
      const changePct =
        first.open > 0
          ? Number((((last.close - first.open) / first.open) * 100).toFixed(2))
          : 0;
      const symbol = pair.replace(/^B-/, '').replace(/_USDT$/, '');
      return {
        pair,
        symbol,
        lastPrice: last.close,
        changePct,
        high24h,
        low24h,
        volume: Number(volume.toFixed(2)),
      };
    }),
  );
  const data = rows.filter((x): x is TickerSnapshot => x !== null);
  tickerCache = { data, fetchedAt: Date.now() };
  res.json(data);
});

// Portfolio routes (LIVE strictly requires active CoinDCX API key; PAPER uses live mark pricing)
async function enrichPosition(pos: Position) {
  const settle = (await getUsdtInrRate()) || 100;
  if (pos.status !== 'OPEN') {
    return {
      ...pos,
      pnl: pos.realizedPnl,
      unrealizedPnl: null,
      markPrice: null,
      sizeInr: pos.exitPrice
        ? Number((pos.quantity * pos.exitPrice * settle).toFixed(2))
        : null,
      roePct: null,
    };
  }
  const mark = await getLastPrice(pos.pair, pos.entryPrice);
  const sign = pos.side === 'SHORT' ? -1 : 1;
  const unrealized = Number(
    ((mark - pos.entryPrice) * pos.quantity * sign * settle).toFixed(2),
  );
  const sizeInr = Number((pos.quantity * mark * settle).toFixed(2));
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
  if (mode === 'LIVE') {
    const creds = getActiveCoinDcxCredentials(req.principal!.tenantId, req.principal!.userId);
    if (!creds) {
      res.json([]);
      return;
    }
    try {
      const liveList = await fetchLiveCoinDcxPositions(creds.apiKey, creds.apiSecret);
      res.json(liveList);
    } catch (e) {
      res.status(502).json({
        message: e instanceof Error ? e.message : 'Failed to fetch live CoinDCX positions',
      });
    }
    return;
  }
  const list = positions
    .filter(
      (p) =>
        p.tenantId === req.principal!.tenantId &&
        p.userId === req.principal!.userId &&
        p.mode === 'PAPER',
    )
    .sort((a, b) => new Date(b.openedAt).getTime() - new Date(a.openedAt).getTime());
  const enriched = await Promise.all(list.map(enrichPosition));
  res.json(enriched);
});

app.get('/api/v1/portfolio/orders', requireAuth, async (req: AuthedRequest, res) => {
  const mode = String(req.query.mode || 'LIVE').toUpperCase();
  if (mode === 'LIVE') {
    const creds = getActiveCoinDcxCredentials(req.principal!.tenantId, req.principal!.userId);
    if (!creds) {
      res.json([]);
      return;
    }
    try {
      const liveOrders = await fetchLiveCoinDcxOrders(creds.apiKey, creds.apiSecret);
      res.json(liveOrders);
    } catch (e) {
      res.status(502).json({
        message: e instanceof Error ? e.message : 'Failed to fetch live CoinDCX orders',
      });
    }
    return;
  }
  const list = orders
    .filter(
      (o) =>
        o.tenantId === req.principal!.tenantId &&
        o.userId === req.principal!.userId &&
        o.mode === 'PAPER',
    )
    .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
  res.json(list);
});

app.get('/api/v1/portfolio/summary', requireAuth, async (req: AuthedRequest, res) => {
  const mode = String(req.query.mode || 'LIVE').toUpperCase();
  if (mode === 'LIVE') {
    const creds = getActiveCoinDcxCredentials(req.principal!.tenantId, req.principal!.userId);
    if (!creds) {
      res.json({
        mode: 'LIVE',
        hasApiKey: false,
        openPositions: 0,
        closedPositions: 0,
        realizedPnl: 0,
        unrealizedPnl: 0,
        winRate: 0,
      });
      return;
    }
    try {
      const liveList = await fetchLiveCoinDcxPositions(creds.apiKey, creds.apiSecret);
      const openPositions = liveList.filter((p) => p.status === 'OPEN').length;
      const closed = liveList.filter((p) => p.status === 'CLOSED');
      const wins = closed.filter((p) => (p.realizedPnl ?? 0) > 0).length;
      const realizedPnl = closed.reduce((s, p) => s + (p.realizedPnl ?? 0), 0);
      const unrealizedPnl = liveList
        .filter((p) => p.status === 'OPEN')
        .reduce((s, p) => s + (p.unrealizedPnl ?? 0), 0);
      res.json({
        mode: 'LIVE',
        hasApiKey: true,
        openPositions,
        closedPositions: closed.length,
        realizedPnl: Number(realizedPnl.toFixed(2)),
        unrealizedPnl: Number(unrealizedPnl.toFixed(2)),
        winRate: closed.length === 0 ? 0 : wins / closed.length,
      });
    } catch (e) {
      res.status(502).json({
        message: e instanceof Error ? e.message : 'Failed to summarize CoinDCX portfolio',
      });
    }
    return;
  }

  const list = positions.filter(
    (p) =>
      p.tenantId === req.principal!.tenantId &&
      p.userId === req.principal!.userId &&
      p.mode === 'PAPER',
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
    mode: 'PAPER',
    openPositions,
    closedPositions: closed.length,
    realizedPnl: Number(realizedPnl.toFixed(2)),
    unrealizedPnl: Number(unrealizedPnl.toFixed(2)),
    winRate: closed.length === 0 ? 0 : wins / closed.length,
  });
});

app.get('/api/v1/portfolio/wallet', requireAuth, async (req: AuthedRequest, res) => {
  const creds = getActiveCoinDcxCredentials(req.principal!.tenantId, req.principal!.userId);
  if (!creds) {
    res.status(404).json({ message: 'No active CoinDCX key' });
    return;
  }
  try {
    const [walletsRaw, usdtInr, livePos] = await Promise.all([
      coindcxSignedRequest<CoinDcxWalletRow[]>(
        'GET',
        '/exchange/v1/derivatives/futures/wallets',
        { timestamp: Date.now() },
        creds.apiKey,
        creds.apiSecret,
      ),
      getUsdtInrRate(),
      fetchLiveCoinDcxPositions(creds.apiKey, creds.apiSecret).catch(() => []),
    ]);
    let availInr = 0;
    let lockedInr = 0;
    let availUsdt = 0;
    let lockedUsdt = 0;
    if (Array.isArray(walletsRaw)) {
      for (const w of walletsRaw) {
        const ccy = String(w.currency_short_name || '').toUpperCase();
        const avail = Math.max(0, Number(w.balance || 0));
        const locked = Math.max(0, Number(w.locked_balance || 0));
        if (ccy === 'INR') {
          availInr = avail;
          lockedInr = locked;
        } else if (ccy === 'USDT') {
          availUsdt = avail;
          lockedUsdt = locked;
        }
      }
    }
    const activePnl = Number(
      livePos
        .filter((p) => p.status === 'OPEN')
        .reduce((s, p) => s + (p.unrealizedPnl ?? 0), 0)
        .toFixed(2),
    );
    const walletBalance = Number((availInr + lockedInr).toFixed(2));
    const usdtWalletBalance = Number((availUsdt + lockedUsdt).toFixed(4));
    const usdtValueInr = Number((usdtWalletBalance * usdtInr).toFixed(2));
    const currentValue = Number((walletBalance + activePnl).toFixed(2));
    const estTotalFutures = Number((currentValue + usdtValueInr).toFixed(2));
    const futuresWalletBalance = Number((walletBalance + usdtValueInr).toFixed(2));
    res.json({
      currency: 'INR',
      available: availInr,
      locked: lockedInr,
      walletBalance,
      walletEquity: walletBalance,
      activePnl,
      currentValue,
      usdtAvailable: availUsdt,
      usdtValueInr,
      estTotalFutures,
      futuresWalletBalance,
      source: 'CoinDCX futures wallets',
    });
  } catch (e) {
    res.status(502).json({
      message: e instanceof Error ? e.message : 'CoinDCX wallet request failed',
    });
  }
});

// Paper Trading isolated execution endpoints (live CoinDCX prices, zero real funds)
app.post('/api/v1/paper/orders', requireAuth, async (req: AuthedRequest, res) => {
  const pair = String(req.body?.pair || 'B-BTC_USDT').trim().toUpperCase();
  const side = String(req.body?.side || 'LONG').toUpperCase() === 'SHORT' ? 'SHORT' : 'LONG';
  const quantity = Number(req.body?.quantity || 0);
  const leverage = Math.min(20, Math.max(1, Number(req.body?.leverage || 3)));
  if (!pair || !Number.isFinite(quantity) || quantity <= 0) {
    res.status(400).json({ message: 'Valid pair and positive quantity are required' });
    return;
  }
  const [mark, settle] = await Promise.all([getLastPrice(pair, 0), getUsdtInrRate()]);
  if (!mark || mark <= 0 || !settle || settle <= 0) {
    res.status(502).json({ message: `Unable to fetch live CoinDCX price for ${pair}` });
    return;
  }
  const sizeInr = Number((quantity * mark * settle).toFixed(2));
  const marginInr = Number((sizeInr / leverage).toFixed(2));
  const slPrice = Number((mark * (side === 'SHORT' ? 1.015 : 0.985)).toFixed(mark >= 100 ? 2 : 4));
  const targetPrice = Number((mark * (side === 'SHORT' ? 0.972 : 1.028)).toFixed(mark >= 100 ? 2 : 4));
  const nowIso = new Date().toISOString();
  const pos: Position = {
    id: crypto.randomUUID(),
    tenantId: req.principal!.tenantId,
    userId: req.principal!.userId,
    botId: String(req.body?.botId || 'paper-manual'),
    strategyId: String(req.body?.strategyId || ''),
    mode: 'PAPER',
    pair,
    side,
    quantity,
    entryPrice: mark,
    exitPrice: null,
    slPrice,
    targetPrice,
    status: 'OPEN',
    leverage,
    realizedPnl: null,
    marginInr,
    marginCurrency: 'INR',
    openedAt: nowIso,
    closedAt: null,
  };
  positions.unshift(pos);
  const ord: TradeOrder = {
    id: crypto.randomUUID(),
    tenantId: req.principal!.tenantId,
    userId: req.principal!.userId,
    botId: pos.botId,
    strategyId: pos.strategyId,
    mode: 'PAPER',
    pair,
    side: side === 'LONG' ? 'BUY' : 'SELL',
    orderType: 'MARKET',
    status: 'FILLED',
    price: mark,
    avgPrice: mark,
    quantity,
    filledQty: quantity,
    sizeInr,
    error: null,
    createdAt: nowIso,
  };
  orders.unshift(ord);
  res.json(await enrichPosition(pos));
});

app.post('/api/v1/paper/positions/:id/close', requireAuth, async (req: AuthedRequest, res) => {
  const pos = positions.find(
    (p) =>
      p.id === req.params.id &&
      p.tenantId === req.principal!.tenantId &&
      p.userId === req.principal!.userId &&
      p.mode === 'PAPER',
  );
  if (!pos) {
    res.status(404).json({ message: 'Paper position not found' });
    return;
  }
  if (pos.status === 'CLOSED') {
    res.json(pos);
    return;
  }
  const [exitPrice, settle] = await Promise.all([getLastPrice(pos.pair, 0), getUsdtInrRate()]);
  if (!exitPrice || exitPrice <= 0 || !settle || settle <= 0) {
    res.status(502).json({ message: `Unable to fetch live CoinDCX price for ${pos.pair}` });
    return;
  }
  const sign = pos.side === 'SHORT' ? -1 : 1;
  const realized = Number(((exitPrice - pos.entryPrice) * pos.quantity * sign * settle).toFixed(2));
  const nowIso = new Date().toISOString();
  pos.exitPrice = exitPrice;
  pos.status = 'CLOSED';
  pos.realizedPnl = realized;
  pos.closedAt = nowIso;
  orders.unshift({
    id: crypto.randomUUID(),
    tenantId: req.principal!.tenantId,
    userId: req.principal!.userId,
    botId: pos.botId,
    strategyId: pos.strategyId,
    mode: 'PAPER',
    pair: pos.pair,
    side: pos.side === 'LONG' ? 'SELL' : 'BUY',
    orderType: 'MARKET',
    status: 'FILLED',
    price: exitPrice,
    avgPrice: exitPrice,
    quantity: pos.quantity,
    filledQty: pos.quantity,
    sizeInr: Number((pos.quantity * exitPrice * settle).toFixed(2)),
    error: null,
    createdAt: nowIso,
  });
  res.json(await enrichPosition(pos));
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
  auditLogs.unshift({
    id: crypto.randomUUID(),
    tenantId: req.principal!.tenantId,
    entityType: 'STRATEGY',
    entityId: s.id,
    action: 'STRATEGY_PROMOTED_LIVE',
    details: { reason: `${s.name} approved for LIVE trading` },
    createdAt: new Date().toISOString(),
  });
  res.json({ ok: true, status: s.status, reason: null, paper });
});

app.post('/api/v1/strategies/generate', requireAuth, async (req: AuthedRequest, res) => {
  const pairs: string[] = Array.isArray(req.body?.pairs) && req.body.pairs.length
    ? req.body.pairs.map((p: unknown) => String(p).trim()).filter(Boolean)
    : ['B-BTC_USDT'];
  const pair = pairs[0];
  const timeframe = String(req.body?.timeframe || '5m');
  const rawStyle = String(req.body?.style || 'trend-following');
  const style: StrategyStyle =
    rawStyle === 'breakout-squeeze' || rawStyle === 'mean-reversion' || rawStyle === 'donchian-adx'
      ? rawStyle
      : 'trend-following';
  const riskProfile = String(req.body?.riskProfile || 'balanced');
  const stoploss = riskProfile === 'conservative' ? -0.012 : riskProfile === 'aggressive' ? -0.018 : -0.015;
  const roiTarget = riskProfile === 'conservative' ? 0.022 : riskProfile === 'aggressive' ? 0.034 : 0.028;

  const coin = pair.replace(/^B-/, '').replace(/_USDT$/, '').replace(/_INR$/, '');
  const styleLabel =
    style === 'breakout-squeeze'
      ? 'Keltner Squeeze'
      : style === 'mean-reversion'
        ? 'StochRSI-VWAP MeanRev'
        : style === 'donchian-adx'
          ? 'Donchian-ADX Breakout'
          : 'ATR-Regime EMA';
  const name = String(req.body?.name || '').trim() || `${coin} ${styleLabel} v1`;
  const id = crypto.randomUUID();
  const enabledProvider = aiProviders.find((p) => p.tenantId === req.principal!.tenantId && p.enabled);

  const strat: Strategy = {
    id,
    tenantId: req.principal!.tenantId,
    name,
    version: 1,
    status: 'PAPER_TRADING',
    origin: 'AI_PIPELINE',
    sourceCode: buildStrategySource(pair, name, style, timeframe, stoploss, roiTarget),
    marketType: 'FUTURES',
    instrument: pair,
    marginCurrency: 'INR',
    config: {
      timeframe,
      pairs,
      provider_used: enabledProvider?.providerType ?? 'GEMINI',
      model_used: enabledProvider?.models[0] ?? 'gemini-flash-latest',
      style,
      stoploss,
      minimal_roi: { '0': roiTarget, '45': Number((roiTarget * 0.65).toFixed(4)) },
      win_rate_target: 0.65,
    },
    createdAt: new Date().toISOString(),
  };
  strategies.unshift(strat);

  const rangeStart = daysAgo(5);
  const rangeEnd = new Date().toISOString();
  const candles = await fetchCandles(pair, timeframe, rangeStart, rangeEnd, 400).catch(() => []);
  const btResult = runCandleBacktest(candles, timeframe, stoploss, roiTarget);

  backtests.unshift({
    id: crypto.randomUUID(),
    tenantId: req.principal!.tenantId,
    strategyId: id,
    timeframe,
    pairs,
    rangeStart,
    rangeEnd,
    status: btResult.status,
    metrics: btResult.metrics,
    trades: btResult.trades,
    error: btResult.error,
    createdAt: new Date().toISOString(),
  });

  bots.unshift({
    id: crypto.randomUUID(),
    tenantId: req.principal!.tenantId,
    userId: req.principal!.userId,
    strategyId: id,
    exchangeKeyId: null,
    name: `${coin} INR Paper (${styleLabel})`,
    mode: 'PAPER',
    marketType: 'FUTURES',
    pairs,
    stakeCurrency: 'INR',
    stakeAmount: 1000,
    maxOpenTrades: 3,
    leverage: 3,
    status: 'RUNNING',
    killSwitch: false,
    marginCurrency: 'INR',
    createdAt: new Date().toISOString(),
  });

  auditLogs.unshift({
    id: crypto.randomUUID(),
    tenantId: req.principal!.tenantId,
    entityType: 'STRATEGY',
    entityId: id,
    action: 'STRATEGY_GENERATED',
    details: { reason: `Generated ${name} (${timeframe}) for ${pair}` },
    createdAt: new Date().toISOString(),
  });

  res.json(toStrategyView(strat, true));
});

function runCandleBacktest(
  candles: CandleDto[],
  timeframe: string,
  stoploss: number,
  roiTarget: number,
) {
  if (candles.length < 30) {
    return {
      status: 'FAILED' as Backtest['status'],
      metrics: {
        profit_total_pct: 0,
        trades: 0,
        wins: 0,
        win_rate: 0,
        max_drawdown_pct: 0,
        profit_factor: 0,
        sharpe: 0,
        timeframe,
      },
      trades: [] as Array<{ entry_time: string; exit_time: string; profit_ratio: number }>,
      error: 'Insufficient live CoinDCX historical candles for backtest',
    };
  }

  const trades: Array<{ entry_time: string; exit_time: string; profit_ratio: number }> = [];
  let emaFast = candles[0].close;
  let emaSlow = candles[0].close;
  const kFast = 2 / (12 + 1);
  const kSlow = 2 / (26 + 1);

  let inPos: { side: 'LONG' | 'SHORT'; entryPrice: number; entryTime: string; entryIdx: number } | null = null;

  for (let i = 1; i < candles.length; i++) {
    const c = candles[i];
    emaFast = c.close * kFast + emaFast * (1 - kFast);
    emaSlow = c.close * kSlow + emaSlow * (1 - kSlow);

    if (inPos) {
      const sign = inPos.side === 'LONG' ? 1 : -1;
      const movePct = inPos.entryPrice > 0 ? ((c.close - inPos.entryPrice) / inPos.entryPrice) * sign : 0;
      const crossedBack =
        (inPos.side === 'LONG' && emaFast < emaSlow) ||
        (inPos.side === 'SHORT' && emaFast > emaSlow);
      const hitSl = movePct <= stoploss;
      const hitTp = movePct >= roiTarget;
      const maxHold = i - inPos.entryIdx >= 48;
      if (hitSl || hitTp || crossedBack || maxHold || i === candles.length - 1) {
        const clampedRatio = hitSl ? stoploss : hitTp ? roiTarget : movePct;
        trades.push({
          entry_time: inPos.entryTime,
          exit_time: c.ts,
          profit_ratio: Number(clampedRatio.toFixed(4)),
        });
        inPos = null;
      }
      continue;
    }

    if (i >= 26 && i < candles.length - 2) {
      if (emaFast > emaSlow && c.close > emaFast) {
        inPos = { side: 'LONG', entryPrice: c.close, entryTime: c.ts, entryIdx: i };
      } else if (emaFast < emaSlow && c.close < emaFast) {
        inPos = { side: 'SHORT', entryPrice: c.close, entryTime: c.ts, entryIdx: i };
      }
    }
  }

  const totalTrades = trades.length;
  const wins = trades.filter((t) => t.profit_ratio > 0).length;
  const winRate = totalTrades === 0 ? 0 : Number((wins / totalTrades).toFixed(3));
  const profitTotal = Number(trades.reduce((acc, t) => acc + t.profit_ratio * 100, 0).toFixed(2));

  let peak = 0;
  let equity = 0;
  let maxDd = 0;
  let grossGain = 0;
  let grossLoss = 0;
  for (const t of trades) {
    const pct = t.profit_ratio * 100;
    equity += pct;
    if (equity > peak) peak = equity;
    const dd = peak - equity;
    if (dd > maxDd) maxDd = dd;
    if (t.profit_ratio > 0) grossGain += t.profit_ratio;
    else grossLoss += Math.abs(t.profit_ratio);
  }
  const profitFactor =
    grossLoss > 0 ? Number((grossGain / grossLoss).toFixed(2)) : grossGain > 0 ? Number(grossGain.toFixed(2)) : 0;

  const mean = totalTrades > 0 ? trades.reduce((a, b) => a + b.profit_ratio, 0) / totalTrades : 0;
  const variance =
    totalTrades > 1
      ? trades.reduce((a, b) => a + (b.profit_ratio - mean) ** 2, 0) / (totalTrades - 1)
      : 0;
  const sharpe = variance > 0 ? Number(((mean / Math.sqrt(variance)) * Math.sqrt(totalTrades)).toFixed(2)) : 0;

  return {
    status: 'DONE' as Backtest['status'],
    metrics: {
      profit_total_pct: profitTotal,
      trades: totalTrades,
      wins,
      win_rate: winRate,
      max_drawdown_pct: Number(maxDd.toFixed(2)),
      profit_factor: profitFactor,
      sharpe,
      timeframe,
    },
    trades,
    error: null as string | null,
  };
}

app.post('/api/v1/strategies/:id/backtest', requireAuth, async (req: AuthedRequest, res) => {
  const s = strategies.find(
    (x) => x.id === req.params.id && x.tenantId === req.principal!.tenantId,
  );
  if (!s) {
    res.status(404).json({ message: 'Strategy not found' });
    return;
  }
  const timeframe = String(req.body?.timeframe || s.config?.timeframe || '5m');
  const days = Math.min(30, Math.max(2, Number(req.body?.days || 5)));
  const rangeStart = daysAgo(days);
  const rangeEnd = new Date().toISOString();
  const pair = s.instrument || s.config?.pairs?.[0] || 'B-BTC_USDT';
  const candles = await fetchCandles(pair, timeframe, rangeStart, rangeEnd, 600).catch(() => []);
  const roiTarget = Number(s.config?.minimal_roi?.['0'] ?? 0.025);
  const sl = Number(s.config?.stoploss ?? -0.014);
  const btResult = runCandleBacktest(candles, timeframe, sl, roiTarget);

  const bt: Backtest = {
    id: crypto.randomUUID(),
    tenantId: req.principal!.tenantId,
    strategyId: s.id,
    timeframe,
    pairs: [pair],
    rangeStart,
    rangeEnd,
    status: btResult.status,
    metrics: btResult.metrics,
    trades: btResult.trades,
    error: btResult.error,
    createdAt: new Date().toISOString(),
  };
  backtests.unshift(bt);
  if (s.status === 'GENERATED' && btResult.status === 'DONE') {
    s.status = 'BACKTESTED';
  }
  auditLogs.unshift({
    id: crypto.randomUUID(),
    tenantId: req.principal!.tenantId,
    entityType: 'BACKTEST',
    entityId: s.id,
    action: 'BACKTEST_COMPLETED',
    details: {
      reason:
        btResult.status === 'DONE'
          ? `${s.name} backtest (${timeframe}): ${btResult.metrics.profit_total_pct >= 0 ? '+' : ''}${btResult.metrics.profit_total_pct}% · ${(btResult.metrics.win_rate * 100).toFixed(1)}% WR (${btResult.metrics.trades} trades)`
          : `${s.name} backtest failed: ${btResult.error}`,
    },
    createdAt: new Date().toISOString(),
  });
  res.json(bt);
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
    apiKeyEnc: encryptSecret(apiKey),
    apiSecretEnc: encryptSecret(apiSecret),
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
