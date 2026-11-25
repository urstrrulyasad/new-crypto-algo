import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { AnimatePresence, motion } from 'motion/react'
import { api } from '@/lib/api'
import { formatDateTime } from '@/lib/datetime'
import { useToast } from '@/lib/toast'
import { EquityCurve } from '@/components/EquityCurve'
import { IconCheck, IconChevronRight, IconCopy, IconPlay, IconSpark } from '@/components/Icons'
import { OrderCard, PositionCard } from '@/components/TradeLedger'
import { Badge, Button, Callout, Card, Empty, Input, Label, PageShell, PageTitle, Spinner } from '@/components/ui'
import { CandleChart, type Candle, type TradeMarker } from '@/components/CandleChart'

interface PaperProgress {
  closedTrades: number
  wins: number
  winRate: number
  totalPnl: number
  requiredTrades: number
  requiredWinRate: number
  openPositions?: number
}

interface Strategy {
  id: string
  name: string
  status: string
  origin: string
  sourceCode: string
  instrument?: string
  marketType?: string
  marginCurrency?: string
  config: {
    timeframe?: string
    pairs?: string[]
    provider_used?: string
    model_used?: string
    style?: string
    stoploss?: number
    minimal_roi?: Record<string, number>
    generation_errors?: string[] | string
  }
  createdAt: string
  paper: PaperProgress
}

interface Backtest {
  id: string
  status: string
  timeframe: string
  metrics?: Record<string, unknown> | null
  trades?: { entry_time: string; exit_time: string; profit_ratio: number }[] | null
  rangeStart: string
  rangeEnd: string
  error?: string | null
}

const PIPELINE_STEPS = ['GENERATED', 'BACKTESTED', 'PAPER_TRADING', 'LIVE_APPROVED']

const STATUS_TONE: Record<string, 'success' | 'info' | 'warn' | 'danger' | 'default'> = {
  LIVE_APPROVED: 'success',
  PAPER_TRADING: 'info',
  BACKTESTED: 'info',
  GENERATED: 'warn',
  REJECTED: 'danger',
  ARCHIVED: 'default',
}

const POPULAR_PAIRS = [
  'B-BTC_USDT',
  'B-ETH_USDT',
  'B-SOL_USDT',
  'B-XRP_USDT',
  'B-BNB_USDT',
  'B-SUI_USDT',
  'B-DOGE_USDT',
  'B-ADA_USDT',
]

const STYLE_LABELS: Record<string, string> = {
  'trend-following': 'ATR-Regime EMA Momentum',
  'keltner-squeeze': 'Keltner Squeeze Breakout',
  'breakout-squeeze': 'Keltner Squeeze Breakout',
  'mean-reversion': 'StochRSI-VWAP MeanRev',
  'donchian-adx': 'Donchian-ADX Breakout',
}

function coinKey(s: Strategy): string {
  return s.instrument || s.config?.pairs?.[0] || 'UNKNOWN'
}

function shortCoin(pair: string): string {
  return pair.replace(/^B-/, '').replace(/_USDT$/, '').replace(/_INR$/, '')
}

interface CoinBucket {
  instrument: string
  strategies: Strategy[]
  bestStatus: string
  openPositions: number
  bestWinRate: number
}

function groupByCoin(list: Strategy[]): CoinBucket[] {
  const map = new Map<string, Strategy[]>()
  for (const s of list) {
    const k = coinKey(s)
    const arr = map.get(k) ?? []
    arr.push(s)
    map.set(k, arr)
  }
  const rank = (status: string) => {
    const i = ['LIVE_APPROVED', 'PAPER_TRADING', 'BACKTESTED', 'GENERATED', 'REJECTED', 'ARCHIVED'].indexOf(status)
    return i < 0 ? 99 : i
  }
  return [...map.entries()]
    .map(([instrument, strategies]) => {
      const sorted = [...strategies].sort((a, b) => rank(a.status) - rank(b.status))
      const openPositions = strategies.reduce((n, s) => n + (s.paper?.openPositions ?? 0), 0)
      const bestWinRate = Math.max(0, ...strategies.map((s) => s.paper?.winRate ?? 0))
      return {
        instrument,
        strategies: sorted,
        bestStatus: sorted[0]?.status ?? 'GENERATED',
        openPositions,
        bestWinRate,
      }
    })
    .sort((a, b) => shortCoin(a.instrument).localeCompare(shortCoin(b.instrument)))
}

export default function FuturesStrategies() {
  const { pushToast } = useToast()
  const [strategies, setStrategies] = useState<Strategy[] | null>(null)
  const [selectedCoin, setSelectedCoin] = useState<string | null>(null)
  const [selected, setSelected] = useState<Strategy | null>(null)

  const [genOpen, setGenOpen] = useState(false)
  const [genPair, setGenPair] = useState('B-BTC_USDT')
  const [genStyle, setGenStyle] = useState('trend-following')
  const [genTf, setGenTf] = useState('5m')
  const [genRisk, setGenRisk] = useState('balanced')
  const [genName, setGenName] = useState('')
  const [generating, setGenerating] = useState(false)

  const load = useCallback(() => {
    api
      .get<Strategy[]>('/api/v1/strategies?marketType=FUTURES')
      .then((list) => {
        setStrategies(list)
        setSelected((prev) => (prev ? list.find((s) => s.id === prev.id) ?? prev : prev))
      })
      .catch(() => setStrategies([]))
  }, [])

  useEffect(() => {
    load()
    const poll = setInterval(load, 10_000)
    return () => clearInterval(poll)
  }, [load])

  async function handleGenerate(e: React.FormEvent) {
    e.preventDefault()
    setGenerating(true)
    try {
      const created = await api.post<Strategy>('/api/v1/strategies/generate', {
        pairs: [genPair.trim().toUpperCase()],
        style: genStyle,
        timeframe: genTf,
        riskProfile: genRisk,
        name: genName.trim() || undefined,
      })
      setGenOpen(false)
      setGenName('')
      await load()
      setSelectedCoin(created.instrument ?? genPair.trim().toUpperCase())
      setSelected(created)
      pushToast({
        title: `Strategy generated: ${created.name}`,
        detail: `${created.instrument} · ${created.config?.timeframe ?? genTf} · Paper bot launched`,
        tone: 'success',
      })
    } catch (err) {
      pushToast({
        title: 'Strategy generation failed',
        detail: err instanceof Error ? err.message : 'Unable to generate strategy',
        tone: 'error',
      })
    } finally {
      setGenerating(false)
    }
  }

  const coins = strategies ? groupByCoin(strategies) : null
  const coinStrategies =
    coins && selectedCoin ? coins.find((c) => c.instrument === selectedCoin)?.strategies ?? [] : []

  return (
    <PageShell>
      <div className="mb-6 flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
        <PageTitle
          title="Futures Strategies"
          subtitle="Regime-gated quantitative strategies with automated backtesting and paper-to-LIVE promotion"
        />
        <Button
          variant={genOpen ? 'ghost' : 'primary'}
          onClick={() => setGenOpen((v) => !v)}
          className="w-full sm:w-auto"
        >
          <IconSpark className="h-4 w-4" />
          {genOpen ? 'Close generator' : 'Generate strategy'}
        </Button>
      </div>

      <AnimatePresence>
        {genOpen && (
          <motion.form
            onSubmit={handleGenerate}
            initial={{ opacity: 0, y: -8 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -8 }}
            transition={{ duration: 0.2 }}
            className="glass mb-6 rounded-2xl p-5"
          >
            <div className="mb-4 flex items-center justify-between">
              <div>
                <h2 className="font-[family-name:var(--font-display)] text-base font-semibold text-slate-100">
                  Synthesize Quantitative Futures Strategy
                </h2>
                <p className="text-xs text-slate-400">
                  Includes ADX regime filter, ATR chop guard, volume confirmation, and ≥1.8× reward:risk
                </p>
              </div>
            </div>
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
              <div>
                <Label>Pair</Label>
                <select
                  value={genPair}
                  onChange={(e) => setGenPair(e.target.value)}
                  className="w-full rounded-xl border border-edge bg-surface px-3.5 py-2.5 text-sm text-slate-200 outline-none focus:border-amber-500/60"
                >
                  {POPULAR_PAIRS.map((p) => (
                    <option key={p} value={p}>
                      {p}
                    </option>
                  ))}
                </select>
              </div>
              <div>
                <Label>Quantitative Style</Label>
                <select
                  value={genStyle}
                  onChange={(e) => setGenStyle(e.target.value)}
                  className="w-full rounded-xl border border-edge bg-surface px-3.5 py-2.5 text-sm text-slate-200 outline-none focus:border-amber-500/60"
                >
                  {['trend-following', 'keltner-squeeze', 'mean-reversion', 'donchian-adx'].map((k) => (
                    <option key={k} value={k}>
                      {STYLE_LABELS[k]}
                    </option>
                  ))}
                </select>
              </div>
              <div>
                <Label>Timeframe</Label>
                <select
                  value={genTf}
                  onChange={(e) => setGenTf(e.target.value)}
                  className="w-full rounded-xl border border-edge bg-surface px-3.5 py-2.5 text-sm text-slate-200 outline-none focus:border-amber-500/60"
                >
                  <option value="1m">1m (Scalp)</option>
                  <option value="5m">5m (Intraday)</option>
                  <option value="15m">15m (Swing)</option>
                  <option value="1h">1h (Macro)</option>
                </select>
              </div>
              <div>
                <Label>Risk Profile</Label>
                <select
                  value={genRisk}
                  onChange={(e) => setGenRisk(e.target.value)}
                  className="w-full rounded-xl border border-edge bg-surface px-3.5 py-2.5 text-sm text-slate-200 outline-none focus:border-amber-500/60"
                >
                  <option value="conservative">Conservative (-1.2% SL / 2.2% TP)</option>
                  <option value="balanced">Balanced (-1.5% SL / 2.8% TP)</option>
                  <option value="aggressive">Aggressive (-1.8% SL / 3.4% TP)</option>
                </select>
              </div>
              <div>
                <Label>Custom Name (optional)</Label>
                <Input
                  value={genName}
                  onChange={(e) => setGenName(e.target.value)}
                  placeholder="Auto-named if blank"
                />
              </div>
            </div>
            <div className="mt-4 flex justify-end gap-2">
              <Button type="button" variant="ghost" onClick={() => setGenOpen(false)}>
                Cancel
              </Button>
              <Button type="submit" disabled={generating}>
                {generating ? 'Synthesizing & Backtesting…' : 'Generate & Run Backtest'}
              </Button>
            </div>
          </motion.form>
        )}
      </AnimatePresence>

      <Callout tone="info">
        Promotion gate: strategy advances from paper to LIVE after ≥60% win rate across 100 closed paper trades and a passing historical backtest.
      </Callout>

      {!selectedCoin ? (
        <div>
          <p className="mb-3 text-[11px] font-medium uppercase tracking-[0.14em] text-slate-500">
            Coins with active strategies
          </p>
          {coins === null ? (
            <Spinner />
          ) : coins.length === 0 ? (
            <Empty message="No futures strategies yet. Click Generate strategy above to synthesize one." />
          ) : (
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
              {coins.map((c, i) => (
                <motion.button
                  key={c.instrument}
                  type="button"
                  initial={{ opacity: 0, y: 10 }}
                  animate={{ opacity: 1, y: 0 }}
                  whileHover={{ y: -2 }}
                  transition={{ delay: Math.min(i * 0.03, 0.2), duration: 0.22 }}
                  onClick={() => {
                    setSelectedCoin(c.instrument)
                    setSelected(c.strategies[0] ?? null)
                  }}
                  className="glass glass-hover cursor-pointer rounded-2xl p-4 text-left"
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className="font-[family-name:var(--font-display)] text-lg font-bold text-slate-100">
                      {shortCoin(c.instrument)}
                    </span>
                    <Badge tone={STATUS_TONE[c.bestStatus] ?? 'warn'}>
                      {c.bestStatus.replace('_', ' ')}
                    </Badge>
                  </div>
                  <div className="mt-0.5 font-mono-num text-xs text-slate-500">{c.instrument}</div>
                  <div className="mt-4 flex items-center justify-between border-t border-edge/70 pt-3 text-xs text-slate-400">
                    <span className="font-mono-num">
                      {c.strategies.length} strateg{c.strategies.length === 1 ? 'y' : 'ies'}
                      {c.openPositions > 0 ? ` · ${c.openPositions} open` : ''}
                    </span>
                    <span className="inline-flex items-center gap-1 font-medium text-amber-400">
                      Inspect <IconChevronRight className="h-3.5 w-3.5" />
                    </span>
                  </div>
                </motion.button>
              ))}
            </div>
          )}
        </div>
      ) : (
        <div>
          <div className="mb-4 flex flex-wrap items-center gap-3">
            <Button
              variant="ghost"
              onClick={() => {
                setSelectedCoin(null)
                setSelected(null)
              }}
            >
              ← All coins
            </Button>
            <h2 className="font-[family-name:var(--font-display)] text-lg font-semibold text-slate-100">
              {shortCoin(selectedCoin)}
              <span className="ml-2 font-mono-num text-sm font-normal text-slate-500">{selectedCoin}</span>
            </h2>
          </div>

          <div className="grid gap-6 lg:grid-cols-3">
            <div className="space-y-3 lg:col-span-1">
              {coinStrategies.length === 0 ? (
                <Empty message="No strategies for this coin yet." />
              ) : (
                coinStrategies.map((s) => (
                  <button
                    key={s.id}
                    type="button"
                    onClick={() => setSelected(s)}
                    className={`glass glass-hover w-full cursor-pointer rounded-2xl p-4 text-left ${
                      selected?.id === s.id ? 'border-amber-500/60 bg-[#171923]' : ''
                    }`}
                  >
                    <div className="flex items-start justify-between gap-2">
                      <span className="font-medium leading-snug text-slate-100">{s.name}</span>
                      <Badge tone={STATUS_TONE[s.status] ?? 'warn'}>{s.status.replace('_', ' ')}</Badge>
                    </div>
                    <div className="mt-1.5 font-mono-num text-xs text-slate-400">
                      {s.marginCurrency ?? 'INR'} · {s.config?.timeframe ?? '5m'} ·{' '}
                      <span className={(s.paper?.openPositions ?? 0) > 0 ? 'text-amber-300' : 'text-slate-500'}>
                        {s.paper?.openPositions ?? 0} open
                      </span>
                    </div>
                    {(s.status === 'PAPER_TRADING' || s.status === 'LIVE_APPROVED') && (
                      <PaperBar paper={s.paper} compact />
                    )}
                  </button>
                ))
              )}
            </div>

            <div className="min-w-0 lg:col-span-2">
              {selected ? (
                <StrategyDetail key={selected.id} strategy={selected} onUpdated={load} />
              ) : (
                <Card hover={false}>
                  <Empty message="Select a strategy to inspect its pipeline and backtest." />
                </Card>
              )}
            </div>
          </div>
        </div>
      )}
    </PageShell>
  )
}

function PipelineStepper({ status, reason }: { status: string; reason?: string }) {
  if (status === 'REJECTED') {
    return (
      <p className="text-sm text-rose-400">
        Rejected — {reason || 'failed validation or backtest smoke gate.'}
      </p>
    )
  }
  if (status === 'ARCHIVED') {
    return (
      <p className="text-sm text-slate-400">
        Archived — retired or superseded.
      </p>
    )
  }
  const current = PIPELINE_STEPS.indexOf(status)
  return (
    <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
      {PIPELINE_STEPS.map((step, i) => {
        const done = i < current
        const active = i === current
        return (
          <div
            key={step}
            className={`flex items-center gap-2 rounded-lg border px-2.5 py-1.5 font-mono-num text-[11px] font-medium ${
              done
                ? 'border-emerald-500/30 bg-emerald-500/10 text-emerald-300'
                : active
                  ? 'border-amber-500/45 bg-amber-500/15 text-amber-200'
                  : 'border-edge bg-surface/60 text-slate-500'
            }`}
          >
            <span className="shrink-0">{done ? '✓' : active ? '●' : '○'}</span>
            <span className="truncate">{step.replace('_', ' ')}</span>
          </div>
        )
      })}
    </div>
  )
}

function paperGateLikelyMet(paper?: PaperProgress | null) {
  if (!paper) return false
  const tradesNeed = paper.requiredTrades ?? 100
  const wrNeed = paper.requiredWinRate ?? 0.6
  return (paper.closedTrades ?? 0) >= tradesNeed && (paper.winRate ?? 0) >= wrNeed
}

function PaperBar({ paper, compact }: { paper: PaperProgress; compact?: boolean }) {
  const tradePct = Math.min(100, (paper.closedTrades / Math.max(1, paper.requiredTrades)) * 100)
  const winPct = Math.round(paper.winRate * 100)
  const needPct = Math.round(paper.requiredWinRate * 100)
  return (
    <div className={compact ? 'mt-2.5' : 'mt-1'}>
      <div className="flex items-center justify-between font-mono-num text-[11px] text-slate-400">
        <span>
          Paper {paper.closedTrades}/{paper.requiredTrades}
        </span>
        <span className={paper.winRate >= paper.requiredWinRate ? 'text-emerald-400' : 'text-slate-400'}>
          {winPct}% WR (need {needPct}%)
        </span>
      </div>
      <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-zinc-900">
        <motion.div
          className={`h-full rounded-full ${paper.winRate >= paper.requiredWinRate ? 'bg-emerald-400' : 'bg-amber-400'}`}
          initial={{ width: 0 }}
          animate={{ width: `${tradePct}%` }}
          transition={{ duration: 0.45, ease: [0.16, 1, 0.3, 1] }}
        />
      </div>
    </div>
  )
}

interface StrategyTrade {
  id: string
  mode: string
  pair: string
  side: string
  quantity: number
  entryPrice: number
  exitPrice?: number | null
  status: string
  realizedPnl?: number | null
  openedAt: string
  closedAt?: string | null
}

interface StrategyOrder {
  id: string
  mode: string
  pair: string
  side: string
  orderType: string
  status: string
  price: number
  quantity: number
  filledQty?: number | null
  error?: string | null
  createdAt: string
}

interface LiveSkip {
  action: string
  details?: Record<string, string> | null
  createdAt: string
}

function StrategyDetail({ strategy, onUpdated }: { strategy: Strategy; onUpdated: () => void }) {
  const { pushToast } = useToast()
  const [tab, setTab] = useState<'backtest' | 'paper' | 'orders' | 'code'>(() =>
    strategy.status === 'GENERATED' || strategy.status === 'BACKTESTED' ? 'backtest' : 'paper',
  )
  const [detail, setDetail] = useState<Strategy>(strategy)
  const [backtests, setBacktests] = useState<Backtest[]>([])
  const [active, setActive] = useState<Backtest | null>(null)
  const [candles, setCandles] = useState<Candle[]>([])
  const [trades, setTrades] = useState<StrategyTrade[]>([])
  const [orders, setOrders] = useState<StrategyOrder[]>([])
  const [skips, setSkips] = useState<LiveSkip[]>([])
  const [approveBusy, setApproveBusy] = useState(false)
  const [backtestBusy, setBacktestBusy] = useState(false)
  const [copied, setCopied] = useState(false)
  const activeRef = useRef<Backtest | null>(null)
  const primaryPair = strategy.instrument ?? strategy.config?.pairs?.[0] ?? ''

  const selectBacktest = useCallback(
    async (bt: Backtest) => {
      activeRef.current = bt
      setActive(bt)
      try {
        if (!primaryPair) {
          setCandles([])
          return
        }
        const data = await api.get<Candle[]>(
          `/api/v1/market/candles?pair=${encodeURIComponent(primaryPair)}&timeframe=${bt.timeframe}&from=${bt.rangeStart}&to=${bt.rangeEnd}&marketType=FUTURES`,
        )
        setCandles(data)
      } catch {
        setCandles([])
      }
    },
    [primaryPair],
  )

  const loadBacktests = useCallback(() => {
    api
      .get<Backtest[]>(`/api/v1/backtests?strategyId=${strategy.id}`)
      .then((list) => {
        setBacktests(list)
        const done = list.find((b) => b.status === 'DONE')
        if (done && !activeRef.current) void selectBacktest(done)
      })
      .catch(() => setBacktests([]))
  }, [strategy.id, selectBacktest])

  const loadHistory = useCallback(() => {
    api
      .get<StrategyTrade[]>(`/api/v1/strategies/${strategy.id}/trades?mode=PAPER`)
      .then(setTrades)
      .catch(() => setTrades([]))
    api
      .get<StrategyOrder[]>(`/api/v1/strategies/${strategy.id}/orders`)
      .then(setOrders)
      .catch(() => setOrders([]))
    api
      .get<LiveSkip[]>(`/api/v1/strategies/${strategy.id}/live-skips`)
      .then(setSkips)
      .catch(() => setSkips([]))
  }, [strategy.id])

  useEffect(() => {
    activeRef.current = null
    api
      .get<Strategy>(`/api/v1/strategies/${strategy.id}`)
      .then(setDetail)
      .catch(() => setDetail(strategy))
    loadBacktests()
    loadHistory()
    const poll = setInterval(() => {
      loadBacktests()
      loadHistory()
    }, 10_000)
    return () => clearInterval(poll)
  }, [strategy, loadBacktests, loadHistory])

  async function approveLive() {
    setApproveBusy(true)
    try {
      await api.post(`/api/v1/strategies/${strategy.id}/approve-live`)
      const updated = await api.get<Strategy>(`/api/v1/strategies/${strategy.id}`)
      setDetail(updated)
      loadHistory()
      onUpdated()
      pushToast({
        title: `${strategy.name} approved for LIVE`,
        detail: 'Paper gate passed and strategy promoted to LIVE execution.',
        tone: 'success',
      })
    } catch (e) {
      pushToast({
        title: 'LIVE approval blocked',
        detail: e instanceof Error ? e.message : 'Approve failed',
        tone: 'error',
      })
    } finally {
      setApproveBusy(false)
    }
  }

  async function runBacktest() {
    setBacktestBusy(true)
    try {
      const bt = await api.post<Backtest>(`/api/v1/strategies/${strategy.id}/backtest`, {
        timeframe: detail.config?.timeframe ?? '5m',
        days: 5,
      })
      await loadBacktests()
      await selectBacktest(bt)
      setTab('backtest')
      onUpdated()
      const m = bt.metrics as Record<string, number> | undefined
      pushToast({
        title: `Backtest complete (${bt.timeframe})`,
        detail: m ? `+${m.profit_total_pct}% · ${Math.round((m.win_rate ?? 0) * 100)}% WR · PF ${m.profit_factor}` : undefined,
        tone: 'success',
      })
    } catch (e) {
      pushToast({
        title: 'Backtest failed',
        detail: e instanceof Error ? e.message : 'Unable to run backtest',
        tone: 'error',
      })
    } finally {
      setBacktestBusy(false)
    }
  }

  async function copyCode() {
    if (!detail.sourceCode) return
    try {
      await navigator.clipboard.writeText(detail.sourceCode)
      setCopied(true)
      setTimeout(() => setCopied(false), 1800)
      pushToast({ title: 'Strategy code copied', tone: 'info' })
    } catch {
      pushToast({ title: 'Copy failed', tone: 'error' })
    }
  }

  const markers: TradeMarker[] = (active?.trades ?? []).flatMap((t) => [
    { time: t.entry_time, type: 'entry' as const },
    { time: t.exit_time, type: 'exit' as const, text: `${(t.profit_ratio * 100).toFixed(1)}%` },
  ])
  const btCurve = useMemo(() => {
    const list = active?.trades ?? []
    if (list.length === 0) return []
    const pts = [0]
    let cum = 0
    for (const t of list) {
      cum += Number(t.profit_ratio || 0) * 100
      pts.push(Number(cum.toFixed(2)))
    }
    return pts
  }, [active])
  const metrics = active?.metrics as Record<string, number | string> | undefined

  return (
    <Card hover={false}>
      <div className="mb-4 flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0">
          <h2 className="truncate font-[family-name:var(--font-display)] text-xl font-bold text-slate-100">
            {detail.name}
          </h2>
          <div className="mt-1 font-mono-num text-xs text-slate-400">
            {detail.instrument ?? '—'} · {detail.config?.timeframe ?? '5m'} · {detail.marginCurrency ?? 'INR'}
            {detail.config?.provider_used ? ` · ${detail.config.provider_used}/${detail.config.model_used}` : ''}
          </div>
          {detail.config?.stoploss != null && (
            <div className="mt-1 font-mono-num text-[11px] text-slate-500">
              SL {(Number(detail.config.stoploss) * 100).toFixed(1)}% · Target ROI{' '}
              {detail.config.minimal_roi?.['0'] != null
                ? `+${(Number(detail.config.minimal_roi['0']) * 100).toFixed(1)}%`
                : '—'}
              {detail.config.style ? ` · ${STYLE_LABELS[detail.config.style] ?? detail.config.style}` : ''}
            </div>
          )}
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Button variant="subtle" onClick={runBacktest} disabled={backtestBusy} className="px-3 py-1.5 text-xs">
            <IconPlay className="h-3.5 w-3.5" />
            {backtestBusy ? 'Backtesting…' : 'Run backtest'}
          </Button>
          {(detail.instrument || detail.config?.pairs?.[0]) && (
            <Link
              to={`/futures/chart/${encodeURIComponent(detail.instrument ?? detail.config!.pairs![0])}?mode=strategy&strategyId=${detail.id}&timeframe=${detail.config?.timeframe ?? '5m'}`}
            >
              <Button variant="ghost" className="px-3 py-1.5 text-xs">
                Open chart
              </Button>
            </Link>
          )}
        </div>
      </div>

      <div className="mb-4">
        <PipelineStepper
          status={detail.status}
          reason={
            detail.config?.generation_errors
              ? Array.isArray(detail.config.generation_errors)
                ? detail.config.generation_errors.join('; ')
                : String(detail.config.generation_errors)
              : undefined
          }
        />
      </div>

      {(detail.status === 'PAPER_TRADING' || detail.status === 'LIVE_APPROVED') && (
        <div className="mb-4 rounded-xl border border-edge bg-surface/60 px-4 py-3">
          <div className="mb-1.5 text-[11px] font-medium uppercase tracking-widest text-slate-400">
            {detail.status === 'LIVE_APPROVED'
              ? 'LIVE Gate Passed'
              : `Paper Gate: ≥${Math.round((detail.paper?.requiredWinRate ?? 0.6) * 100)}% WR over ${detail.paper?.requiredTrades ?? 100} closed trades`}
          </div>
          <PaperBar paper={detail.paper ?? strategy.paper} />
          <div className="mt-2.5 flex flex-wrap items-center justify-between gap-3 font-mono-num text-xs text-slate-400">
            <div className="flex flex-wrap gap-4">
              <span>
                Open <strong className="text-amber-300">{detail.paper?.openPositions ?? 0}</strong>
              </span>
              <span>
                Wins <strong className="text-emerald-400">{detail.paper?.wins ?? 0}</strong>
              </span>
              <span>
                Paper PnL{' '}
                <strong className={(detail.paper?.totalPnl ?? 0) >= 0 ? 'text-emerald-400' : 'text-rose-400'}>
                  ₹{Number(detail.paper?.totalPnl ?? 0).toFixed(2)}
                </strong>
              </span>
            </div>
            {detail.status === 'PAPER_TRADING' && (
              <Button
                onClick={approveLive}
                disabled={approveBusy || !paperGateLikelyMet(detail.paper)}
                className="px-3 py-1.5 text-xs"
              >
                {approveBusy ? 'Approving…' : 'Approve for LIVE'}
              </Button>
            )}
          </div>
        </div>
      )}

      {skips.length > 0 && (
        <div className="mb-4 rounded-xl border border-rose-500/30 bg-rose-500/[0.04] px-4 py-3">
          <div className="mb-2 text-[11px] font-medium uppercase tracking-widest text-rose-300">
            LIVE Gate Log
          </div>
          <ul className="space-y-1 text-xs text-slate-400">
            {skips.slice(0, 6).map((s, i) => (
              <li key={`${s.action}-${s.createdAt}-${i}`} className="font-mono-num">
                <span className="text-rose-300">{s.action.replace('AUTO_LIVE_SKIPPED_', '')}</span>
                {s.details?.reason ? ` — ${s.details.reason}` : ''}
                <span className="ml-2 text-slate-500">{formatDateTime(s.createdAt)}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      <div className="mb-4 inline-flex flex-wrap gap-1 rounded-xl border border-edge bg-surface/80 p-1">
        {(['paper', 'orders', 'backtest', 'code'] as const).map((t) => (
          <button
            key={t}
            type="button"
            onClick={() => setTab(t)}
            className={`relative cursor-pointer rounded-lg px-3 py-1.5 text-xs font-medium transition-colors ${
              tab === t ? 'text-amber-200' : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            {tab === t && (
              <motion.span
                layoutId="strategy-detail-tabs"
                className="absolute inset-0 rounded-lg border border-amber-500/35 bg-amber-500/15"
                transition={{ type: 'spring', stiffness: 420, damping: 32 }}
              />
            )}
            <span className="relative z-10">
              {t === 'paper'
                ? 'Paper trades'
                : t === 'orders'
                  ? 'Orders'
                  : t === 'backtest'
                    ? 'Backtest'
                    : 'Source code'}
            </span>
          </button>
        ))}
      </div>

      {tab === 'code' && (
        <div className="overflow-hidden rounded-xl border border-edge bg-[#06070b]">
          <div className="flex items-center justify-between border-b border-edge px-3.5 py-2">
            <span className="font-mono-num text-[11px] text-slate-400">strategy.py</span>
            <button
              type="button"
              onClick={copyCode}
              className="inline-flex cursor-pointer items-center gap-1.5 rounded-md border border-edge px-2.5 py-1 text-[11px] text-slate-300 transition-colors hover:border-amber-500/40 hover:text-amber-200"
            >
              {copied ? <IconCheck className="h-3.5 w-3.5 text-emerald-400" /> : <IconCopy className="h-3.5 w-3.5" />}
              {copied ? 'Copied' : 'Copy'}
            </button>
          </div>
          <pre className="max-h-[460px] overflow-auto p-4 font-[family-name:var(--font-mono)] text-xs leading-relaxed text-emerald-200/90">
            {detail.sourceCode || 'Loading source…'}
          </pre>
        </div>
      )}

      {tab === 'paper' && (
        <div>
          <p className="mb-3 text-xs text-slate-500">
            PAPER fills track live CoinDCX futures pricing without committing account equity.
          </p>
          {trades.length === 0 ? (
            <Empty message="No paper trades recorded yet for this strategy." />
          ) : (
            <>
              <div className="space-y-2 md:hidden">
                {trades.map((t) => (
                  <PositionCard
                    key={t.id}
                    pair={`${t.mode} · ${t.pair}`}
                    side={t.side}
                    status={t.status}
                    quantity={Number(t.quantity)}
                    entryPrice={Number(t.entryPrice)}
                    exitPrice={t.exitPrice}
                    pnl={t.realizedPnl}
                    openedAt={t.openedAt}
                    closedAt={t.closedAt}
                  />
                ))}
              </div>
              <div className="hidden overflow-x-auto rounded-xl border border-edge md:block">
                <table className="w-full min-w-[780px] text-left text-sm">
                  <thead className="bg-surface/80 text-[11px] uppercase tracking-wider text-slate-500">
                    <tr>
                      <th className="px-3 py-2.5">Mode</th>
                      <th className="px-3 py-2.5">Pair</th>
                      <th className="px-3 py-2.5">Side</th>
                      <th className="px-3 py-2.5">Qty</th>
                      <th className="px-3 py-2.5">Entry</th>
                      <th className="px-3 py-2.5">Exit</th>
                      <th className="px-3 py-2.5">Status</th>
                      <th className="px-3 py-2.5">PnL</th>
                      <th className="px-3 py-2.5">Opened</th>
                    </tr>
                  </thead>
                  <tbody>
                    {trades.map((t) => (
                      <tr key={t.id} className="data-row border-t border-edge/50 text-slate-300">
                        <td className="px-3 py-2.5"><Badge tone={t.mode === 'LIVE' ? 'success' : 'info'}>{t.mode}</Badge></td>
                        <td className="px-3 py-2.5 font-medium text-slate-100">{t.pair}</td>
                        <td className="px-3 py-2.5 font-mono-num">{t.side}</td>
                        <td className="px-3 py-2.5 font-mono-num">{Number(t.quantity)}</td>
                        <td className="px-3 py-2.5 font-mono-num">₹{Number(t.entryPrice).toLocaleString()}</td>
                        <td className="px-3 py-2.5 font-mono-num">{t.exitPrice != null ? `₹${Number(t.exitPrice).toLocaleString()}` : '—'}</td>
                        <td className="px-3 py-2.5"><Badge tone={t.status === 'OPEN' ? 'warn' : 'default'}>{t.status}</Badge></td>
                        <td className={`px-3 py-2.5 font-mono-num ${Number(t.realizedPnl ?? 0) >= 0 ? 'text-emerald-400' : 'text-rose-400'}`}>
                          {t.realizedPnl != null ? `₹${Number(t.realizedPnl).toFixed(2)}` : '—'}
                        </td>
                        <td className="whitespace-nowrap px-3 py-2.5 font-mono-num text-xs text-slate-400">{formatDateTime(t.openedAt)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </div>
      )}

      {tab === 'orders' && (
        <div>
          {orders.length === 0 ? (
            <Empty message="No orders recorded yet for this strategy." />
          ) : (
            <>
              <div className="space-y-2 md:hidden">
                {orders.map((o) => (
                  <OrderCard
                    key={o.id}
                    pair={`${o.mode} · ${o.pair ?? ''}`}
                    side={o.side}
                    status={o.status}
                    quantity={Number(o.quantity)}
                    createdAt={o.createdAt}
                    tone={o.status === 'FAILED' ? 'danger' : o.status === 'FILLED' ? 'success' : 'warn'}
                    detail={o.error || `₹${Number(o.price).toLocaleString()}`}
                  />
                ))}
              </div>
              <div className="hidden overflow-x-auto rounded-xl border border-edge md:block">
                <table className="w-full min-w-[700px] text-left text-sm">
                  <thead className="bg-surface/80 text-[11px] uppercase tracking-wider text-slate-500">
                    <tr>
                      <th className="px-3 py-2.5">Mode</th>
                      <th className="px-3 py-2.5">Side</th>
                      <th className="px-3 py-2.5">Qty</th>
                      <th className="px-3 py-2.5">Price</th>
                      <th className="px-3 py-2.5">Status</th>
                      <th className="px-3 py-2.5">Error</th>
                      <th className="px-3 py-2.5">Order time</th>
                    </tr>
                  </thead>
                  <tbody>
                    {orders.map((o) => (
                      <tr key={o.id} className="data-row border-t border-edge/50 text-slate-300">
                        <td className="px-3 py-2.5"><Badge tone={o.mode === 'LIVE' ? 'success' : 'info'}>{o.mode}</Badge></td>
                        <td className="px-3 py-2.5 font-mono-num">{o.side}</td>
                        <td className="px-3 py-2.5 font-mono-num">{Number(o.quantity)}</td>
                        <td className="px-3 py-2.5 font-mono-num">₹{Number(o.price).toLocaleString()}</td>
                        <td className="px-3 py-2.5">
                          <Badge tone={o.status === 'FAILED' ? 'danger' : o.status === 'FILLED' ? 'success' : 'warn'}>
                            {o.status}
                          </Badge>
                        </td>
                        <td className="max-w-[240px] truncate px-3 py-2.5 text-xs text-rose-300" title={o.error ?? ''}>
                          {o.error ?? '—'}
                        </td>
                        <td className="whitespace-nowrap px-3 py-2.5 font-mono-num text-xs text-slate-400">{formatDateTime(o.createdAt)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </div>
      )}

      {tab === 'backtest' && (
        <div>
          {backtests.length > 0 && (
            <div className="mb-4 flex flex-wrap items-center gap-3">
              <select
                className="rounded-xl border border-edge bg-surface px-3 py-2 font-mono-num text-xs text-slate-300 outline-none focus:border-amber-500/60"
                value={active?.id ?? ''}
                onChange={(e) => {
                  const bt = backtests.find((b) => b.id === e.target.value)
                  if (bt?.status === 'DONE') void selectBacktest(bt)
                }}
              >
                {backtests.map((b) => (
                  <option key={b.id} value={b.id}>
                    {b.timeframe} · {new Date(b.rangeStart).toLocaleDateString()} →{' '}
                    {new Date(b.rangeEnd).toLocaleDateString()} · {b.status}
                  </option>
                ))}
              </select>
            </div>
          )}

          {metrics && (
            <div className="mb-4 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
              <Metric label="Profit" value={`${metrics.profit_total_pct ?? 0}%`} good={Number(metrics.profit_total_pct) > 0} />
              <Metric label="Trades" value={String(metrics.trades ?? 0)} />
              <Metric label="Win rate" value={`${((metrics.win_rate as number) * 100).toFixed(1)}%`} good={(metrics.win_rate as number) >= 0.58} />
              <Metric label="Max DD" value={`${metrics.max_drawdown_pct ?? 0}%`} good={Number(metrics.max_drawdown_pct) <= 8} />
              <Metric label="PF" value={String(metrics.profit_factor ?? '—')} good={Number(metrics.profit_factor) >= 1.4} />
              <Metric label="Sharpe" value={String(metrics.sharpe ?? '—')} good={Number(metrics.sharpe) >= 1.4} />
            </div>
          )}

          {btCurve.length >= 2 && (
            <div className="mb-4 rounded-xl border border-edge bg-surface/65 p-3">
              <div className="mb-1 flex items-center justify-between font-mono-num text-[11px] uppercase tracking-wider text-slate-400">
                <span>Cumulative Backtest Return</span>
                <span className={btCurve[btCurve.length - 1] >= 0 ? 'text-emerald-400' : 'text-rose-400'}>
                  {btCurve[btCurve.length - 1] >= 0 ? '+' : ''}
                  {btCurve[btCurve.length - 1].toFixed(2)}%
                </span>
              </div>
              <EquityCurve points={btCurve} height={56} />
            </div>
          )}

          {candles.length > 0 ? (
            <CandleChart candles={candles} markers={markers} />
          ) : (
            <Empty message="No backtest chart yet." />
          )}
        </div>
      )}
    </Card>
  )
}

function Metric({ label, value, good }: { label: string; value: string; good?: boolean }) {
  return (
    <div className="rounded-xl border border-edge bg-surface/65 px-3 py-2.5">
      <div className="text-[10px] uppercase tracking-widest text-slate-500">{label}</div>
      <div
        className={`mt-1 font-mono-num text-sm font-semibold ${good === undefined ? 'text-slate-100' : good ? 'text-emerald-400' : 'text-rose-400'}`}
      >
        {value}
      </div>
    </div>
  )
}
