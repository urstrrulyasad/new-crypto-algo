import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { motion } from 'motion/react'
import { api } from '@/lib/api'
import { Badge, Button, Callout, Card, Empty, PageShell, PageTitle, Spinner } from '@/components/ui'
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
      return {
        instrument,
        strategies: sorted,
        bestStatus: sorted[0]?.status ?? 'GENERATED',
        openPositions,
      }
    })
    .sort((a, b) => shortCoin(a.instrument).localeCompare(shortCoin(b.instrument)))
}

export default function FuturesStrategies() {
  const [strategies, setStrategies] = useState<Strategy[] | null>(null)
  const [selectedCoin, setSelectedCoin] = useState<string | null>(null)
  const [selected, setSelected] = useState<Strategy | null>(null)

  const load = () =>
    api
      .get<Strategy[]>('/api/v1/strategies?marketType=FUTURES')
      .then((list) => {
        setStrategies(list)
        setSelected((prev) => (prev ? list.find((s) => s.id === prev.id) ?? prev : prev))
      })
      .catch(() => setStrategies([]))

  useEffect(() => {
    load()
    const poll = setInterval(load, 10_000)
    return () => clearInterval(poll)
  }, [])

  const coins = strategies ? groupByCoin(strategies) : null
  const coinStrategies =
    coins && selectedCoin ? coins.find((c) => c.instrument === selectedCoin)?.strategies ?? [] : []

  return (
    <PageShell>
      <PageTitle
        title="Futures Strategies"
        subtitle="Pick a coin, then inspect the AI strategies generated for it"
      />

      <Callout tone="info">
        Strategies are generated automatically per coin. Backtest is a smoke screen into paper.
        LIVE when paper hits ≥60% win rate or ≥60% profit vs stake (plus enough trades and a
        quality backtest). Rejected/archived strategies are removed automatically.
      </Callout>

      {!selectedCoin ? (
        <div>
          <p className="mb-3 text-xs uppercase tracking-widest text-slate-500">Coins with strategies</p>
          {coins === null ? (
            <Spinner />
          ) : coins.length === 0 ? (
            <Empty message="Waiting for the auto scheduler to generate the first futures strategies…" />
          ) : (
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
              {coins.map((c, i) => (
                <motion.button
                  key={c.instrument}
                  type="button"
                  initial={{ opacity: 0, y: 14, scale: 0.98 }}
                  animate={{ opacity: 1, y: 0, scale: 1 }}
                  whileHover={{ y: -3, scale: 1.01 }}
                  whileTap={{ scale: 0.99 }}
                  transition={{ delay: Math.min(i * 0.03, 0.3), duration: 0.4, ease: [0.22, 1, 0.36, 1] }}
                  onClick={() => {
                    setSelectedCoin(c.instrument)
                    setSelected(c.strategies[0] ?? null)
                  }}
                  className="glass glass-hover rounded-2xl p-4 text-left"
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className="font-[family-name:var(--font-display)] text-lg font-semibold text-slate-100">
                      {shortCoin(c.instrument)}
                    </span>
                    <Badge tone={STATUS_TONE[c.bestStatus] ?? 'warn'}>
                      {c.bestStatus.replace('_', ' ')}
                    </Badge>
                  </div>
                  <div className="mt-1 text-xs text-slate-500">{c.instrument}</div>
                  <div className="mt-3 flex items-center justify-between text-xs text-slate-400">
                    <span>
                      {c.strategies.length} strateg{c.strategies.length === 1 ? 'y' : 'ies'}
                      {c.openPositions > 0 ? ` · ${c.openPositions} open` : ''}
                    </span>
                    <span className="text-cyan-400/80">Open →</span>
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
              <span className="ml-2 text-sm font-normal text-slate-500">{selectedCoin}</span>
            </h2>
          </div>

          <div className="grid gap-6 xl:grid-cols-3">
            <div className="space-y-3 xl:col-span-1">
              {coinStrategies.length === 0 ? (
                <Empty message="No strategies for this coin yet." />
              ) : (
                coinStrategies.map((s, i) => (
                  <motion.button
                    key={s.id}
                    type="button"
                    initial={{ opacity: 0, y: 14 }}
                    animate={{ opacity: 1, y: 0 }}
                    transition={{ delay: Math.min(i * 0.04, 0.35) }}
                    onClick={() => setSelected(s)}
                    whileHover={{ x: 2 }}
                    className={`glass glass-hover w-full rounded-2xl p-4 text-left ${selected?.id === s.id ? 'border-cyan-500/60 ring-1 ring-cyan-500/30' : ''}`}
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className="truncate font-medium text-slate-100">{s.name}</span>
                      <Badge tone={STATUS_TONE[s.status] ?? 'warn'}>{s.status.replace('_', ' ')}</Badge>
                    </div>
                    <div className="mt-1 flex flex-wrap gap-2 text-xs text-slate-500">
                      <span>{s.marginCurrency ?? 'INR'}</span>
                      <span>·</span>
                      <span>{s.config?.timeframe ?? '1h'}</span>
                      <span>·</span>
                      <span className={(s.paper?.openPositions ?? 0) > 0 ? 'text-cyan-300' : ''}>
                        {s.paper?.openPositions ?? 0} open
                      </span>
                    </div>
                    {(s.status === 'PAPER_TRADING' || s.status === 'LIVE_APPROVED') && (
                      <PaperBar paper={s.paper} compact />
                    )}
                  </motion.button>
                ))
              )}
            </div>

            <div className="xl:col-span-2">
              {selected ? (
                <StrategyDetail strategy={selected} />
              ) : (
                <Card>
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
        Rejected — {reason || 'failed validation or backtest smoke gate (too few trades / high drawdown). New strategies will regenerate for this coin.'}
      </p>
    )
  }
  if (status === 'ARCHIVED') {
    return (
      <p className="text-sm text-slate-400">
        Archived — retired (failed backtest quality gate, unpromotable paper, delisted, or superseded).
      </p>
    )
  }
  const current = PIPELINE_STEPS.indexOf(status)
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      {PIPELINE_STEPS.map((step, i) => (
        <div key={step} className="flex items-center gap-1.5">
          <div
            className={`flex items-center gap-1.5 rounded-full px-3 py-1 text-[11px] font-medium ${
              i < current
                ? 'bg-emerald-500/15 text-emerald-300'
                : i === current
                  ? 'bg-cyan-500/20 text-cyan-300 ring-1 ring-cyan-500/40'
                  : 'bg-surface/80 text-slate-600'
            }`}
          >
            {i < current ? '✓' : i === current ? '●' : '○'} {step.replace('_', ' ')}
          </div>
          {i < PIPELINE_STEPS.length - 1 && <span className="text-slate-700">→</span>}
        </div>
      ))}
    </div>
  )
}

function paperGateLikelyMet(paper?: PaperProgress | null) {
  if (!paper) return false
  const need = paper.requiredTrades ?? 30
  if ((paper.closedTrades ?? 0) < need) return false
  const wrNeed = paper.requiredWinRate ?? 0.6
  return (paper.winRate ?? 0) >= wrNeed || (paper.totalPnl ?? 0) > 0
}

function PaperBar({ paper, compact }: { paper: PaperProgress; compact?: boolean }) {
  const tradePct = Math.min(100, (paper.closedTrades / Math.max(1, paper.requiredTrades)) * 100)
  const winPct = Math.round(paper.winRate * 100)
  const needPct = Math.round(paper.requiredWinRate * 100)
  return (
    <div className={compact ? 'mt-2' : 'mt-1'}>
      <div className="flex items-center justify-between text-[11px] text-slate-500">
        <span>
          Paper: {paper.closedTrades}/{paper.requiredTrades} trades
        </span>
        <span className={paper.winRate >= paper.requiredWinRate ? 'text-emerald-400' : ''}>
          {winPct}% wins (need {needPct}%)
        </span>
      </div>
      <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-surface">
        <motion.div
          className={`h-full rounded-full ${paper.winRate >= paper.requiredWinRate ? 'bg-emerald-400' : 'bg-cyan-400'}`}
          initial={{ width: 0 }}
          animate={{ width: `${tradePct}%` }}
          transition={{ duration: 0.6 }}
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

function StrategyDetail({ strategy }: { strategy: Strategy }) {
  const [tab, setTab] = useState<'backtest' | 'paper' | 'orders' | 'code'>('paper')
  const [detail, setDetail] = useState<Strategy>(strategy)
  const [backtests, setBacktests] = useState<Backtest[]>([])
  const [active, setActive] = useState<Backtest | null>(null)
  const [candles, setCandles] = useState<Candle[]>([])
  const [trades, setTrades] = useState<StrategyTrade[]>([])
  const [orders, setOrders] = useState<StrategyOrder[]>([])
  const [skips, setSkips] = useState<LiveSkip[]>([])
  const [approveBusy, setApproveBusy] = useState(false)
  const [approveErr, setApproveErr] = useState('')
  const activeRef = useRef<Backtest | null>(null)

  const loadBacktests = () =>
    api.get<Backtest[]>(`/api/v1/backtests?strategyId=${strategy.id}`).then((list) => {
      setBacktests(list)
      const done = list.find((b) => b.status === 'DONE')
      if (done && !activeRef.current) selectBacktest(done)
    }).catch(() => setBacktests([]))

  const loadHistory = () => {
    api.get<StrategyTrade[]>(`/api/v1/strategies/${strategy.id}/trades?mode=PAPER`)
      .then(setTrades)
      .catch(() => setTrades([]))
    api.get<StrategyOrder[]>(`/api/v1/strategies/${strategy.id}/orders`)
      .then(setOrders)
      .catch(() => setOrders([]))
    api.get<LiveSkip[]>(`/api/v1/strategies/${strategy.id}/live-skips`)
      .then(setSkips)
      .catch(() => setSkips([]))
  }

  useEffect(() => {
    activeRef.current = null
    setActive(null)
    setCandles([])
    setDetail(strategy)
    setTab(strategy.status === 'GENERATED' || strategy.status === 'BACKTESTED' ? 'backtest' : 'paper')
    // List payload omits sourceCode for speed — load full row for the code tab.
    api.get<Strategy>(`/api/v1/strategies/${strategy.id}`)
      .then(setDetail)
      .catch(() => setDetail(strategy))
    loadBacktests()
    loadHistory()
    const poll = setInterval(() => {
      loadBacktests()
      loadHistory()
    }, 8_000)
    return () => clearInterval(poll)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [strategy.id])

  async function selectBacktest(bt: Backtest) {
    activeRef.current = bt
    setActive(bt)
    try {
      const pair = strategy.instrument ?? strategy.config?.pairs?.[0] ?? ''
      if (!pair) {
        setCandles([])
        return
      }
      const data = await api.get<Candle[]>(
        `/api/v1/market/candles?pair=${encodeURIComponent(pair)}&timeframe=${bt.timeframe}&from=${bt.rangeStart}&to=${bt.rangeEnd}&marketType=FUTURES`,
      )
      setCandles(data)
    } catch {
      setCandles([])
    }
  }

  async function approveLive() {
    setApproveBusy(true)
    setApproveErr('')
    try {
      await api.post(`/api/v1/strategies/${strategy.id}/approve-live`)
      const updated = await api.get<Strategy>(`/api/v1/strategies/${strategy.id}`)
      setDetail(updated)
      loadHistory()
    } catch (e) {
      setApproveErr(e instanceof Error ? e.message : 'Approve failed')
    } finally {
      setApproveBusy(false)
    }
  }

  const markers: TradeMarker[] = (active?.trades ?? []).flatMap((t) => [
    { time: t.entry_time, type: 'entry' as const },
    { time: t.exit_time, type: 'exit' as const, text: `${(t.profit_ratio * 100).toFixed(1)}%` },
  ])
  const metrics = active?.metrics as Record<string, number | string> | undefined

  return (
    <Card>
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="font-[family-name:var(--font-display)] text-xl font-semibold text-slate-100">
            {strategy.instrument ?? strategy.name}
          </h2>
          <div className="mt-1 text-xs text-slate-500">
            {strategy.config?.provider_used && (
              <span>
                ✦ {strategy.config.provider_used} / {strategy.config.model_used} ·{' '}
              </span>
            )}
            {strategy.config?.timeframe ?? '1h'} · {strategy.marginCurrency ?? 'INR'}
          </div>
        </div>
        <div className="flex flex-wrap gap-2">
          {(strategy.instrument || strategy.config?.pairs?.[0]) && (
            <Link
              to={`/futures/chart/${encodeURIComponent(strategy.instrument ?? strategy.config!.pairs![0])}?mode=strategy&strategyId=${strategy.id}&timeframe=5m`}
            >
              <Button variant="ghost">Open chart</Button>
            </Link>
          )}
          {(['paper', 'orders', 'backtest', 'code'] as const).map((t) => (
            <Button key={t} variant={tab === t ? 'primary' : 'ghost'} onClick={() => setTab(t)}>
              {t === 'paper' ? 'Paper trades' : t === 'orders' ? 'Orders' : t === 'backtest' ? 'Backtest' : 'Code'}
            </Button>
          ))}
        </div>
      </div>

      <div className="mb-4">
        <PipelineStepper
          status={strategy.status}
          reason={
            strategy.config?.generation_errors
              ? Array.isArray(strategy.config.generation_errors)
                ? strategy.config.generation_errors.join('; ')
                : String(strategy.config.generation_errors)
              : undefined
          }
        />
      </div>

      {(detail.status === 'PAPER_TRADING' || detail.status === 'LIVE_APPROVED') && (
        <div className="mb-4 rounded-xl border border-edge/60 bg-surface/50 px-4 py-3">
          <div className="mb-1 text-xs font-medium uppercase tracking-widest text-slate-500">
            {detail.status === 'LIVE_APPROVED'
              ? 'Live gate passed — paper history below'
              : `Paper gate: need ≥${Math.round((detail.paper?.requiredWinRate ?? 0.6) * 100)}% win rate over ${detail.paper?.requiredTrades ?? 30} closed trades`}
          </div>
          <PaperBar paper={detail.paper ?? strategy.paper} />
          <div className="mt-2 flex flex-wrap gap-4 text-xs text-slate-400">
            <span>
              Open:{' '}
              <span className="text-cyan-300">{detail.paper?.openPositions ?? 0}</span>
            </span>
            <span>
              Wins: <span className="text-emerald-400">{detail.paper?.wins ?? 0}</span>
            </span>
            <span>
              Paper PnL:{' '}
              <span className={(detail.paper?.totalPnl ?? 0) >= 0 ? 'text-emerald-400' : 'text-rose-400'}>
                ₹{Number(detail.paper?.totalPnl ?? 0).toFixed(2)}
              </span>
            </span>
          </div>
          {detail.status === 'PAPER_TRADING' && (
            <div className="mt-3 flex flex-wrap items-center gap-3">
              <Button
                onClick={approveLive}
                disabled={approveBusy || !paperGateLikelyMet(detail.paper)}
                title={
                  paperGateLikelyMet(detail.paper)
                    ? 'Promote using existing paper + backtest gates'
                    : 'Paper gate not met yet'
                }
              >
                {approveBusy ? 'Approving…' : 'Approve for LIVE'}
              </Button>
              {!paperGateLikelyMet(detail.paper) && (
                <span className="text-xs text-slate-500">
                  Enabled only when paper trades + WR/profit gate already pass (same as auto).
                </span>
              )}
              {approveErr && <span className="text-xs text-rose-400">{approveErr}</span>}
            </div>
          )}
        </div>
      )}

      {skips.length > 0 && (
        <div className="mb-4 rounded-xl border border-rose-500/30 bg-rose-500/5 px-4 py-3">
          <div className="mb-2 text-xs font-medium uppercase tracking-widest text-rose-300/90">
            LIVE skip reasons
          </div>
          <ul className="space-y-1.5 text-xs text-slate-400">
            {skips.slice(0, 8).map((s, i) => (
              <li key={`${s.action}-${s.createdAt}-${i}`}>
                <span className="text-rose-300">{s.action.replace('AUTO_LIVE_SKIPPED_', '')}</span>
                {s.details?.reason ? ` — ${s.details.reason}` : ''}
                {s.details?.backtestProfit != null
                  ? ` · bt profit ${s.details.backtestProfit}% WR ${Number(s.details.backtestWinRate ?? 0) * 100}%`
                  : ''}
                {s.details?.available != null ? ` (INR avail ${s.details.available})` : ''}
                <span className="ml-2 text-slate-600">{new Date(s.createdAt).toLocaleString()}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {tab === 'code' && (
        <pre className="max-h-[480px] overflow-auto rounded-xl border border-edge bg-black/40 p-4 text-xs leading-relaxed text-emerald-200/90">
          {detail.sourceCode || 'Loading source…'}
        </pre>
      )}

      {tab === 'paper' && (
        <div>
          <p className="mb-3 text-xs text-slate-500">
            PAPER fills at live CoinDCX futures price (not invented). Labeled PAPER — never shown as account money on Dashboard.
          </p>
          {trades.length === 0 ? (
            <Empty message="No paper trades recorded yet for this strategy." />
          ) : (
            <div className="overflow-x-auto rounded-xl border border-edge/60">
              <table className="w-full text-left text-sm">
                <thead className="bg-surface/80 text-[11px] uppercase tracking-wider text-slate-500">
                  <tr>
                    <th className="px-3 py-2">Mode</th>
                    <th className="px-3 py-2">Pair</th>
                    <th className="px-3 py-2">Side</th>
                    <th className="px-3 py-2">Qty</th>
                    <th className="px-3 py-2">Entry</th>
                    <th className="px-3 py-2">Exit</th>
                    <th className="px-3 py-2">Status</th>
                    <th className="px-3 py-2">PnL</th>
                    <th className="px-3 py-2">Opened</th>
                  </tr>
                </thead>
                <tbody>
                  {trades.map((t) => (
                    <tr key={t.id} className="border-t border-edge/40 text-slate-300">
                      <td className="px-3 py-2"><Badge tone={t.mode === 'LIVE' ? 'success' : 'info'}>{t.mode}</Badge></td>
                      <td className="px-3 py-2">{t.pair}</td>
                      <td className="px-3 py-2">{t.side}</td>
                      <td className="px-3 py-2">{Number(t.quantity)}</td>
                      <td className="px-3 py-2">₹{Number(t.entryPrice).toLocaleString()}</td>
                      <td className="px-3 py-2">{t.exitPrice != null ? `₹${Number(t.exitPrice).toLocaleString()}` : '—'}</td>
                      <td className="px-3 py-2"><Badge tone={t.status === 'OPEN' ? 'warn' : 'default'}>{t.status}</Badge></td>
                      <td className={`px-3 py-2 ${Number(t.realizedPnl ?? 0) >= 0 ? 'text-emerald-400' : 'text-rose-400'}`}>
                        {t.realizedPnl != null ? `₹${Number(t.realizedPnl).toFixed(2)}` : '—'}
                      </td>
                      <td className="px-3 py-2 text-xs text-slate-500">{new Date(t.openedAt).toLocaleString()}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      {tab === 'orders' && (
        <div>
          <p className="mb-3 text-xs text-slate-500">
            Order ledger (paper fills + live exchange attempts, including FAILED).
          </p>
          {orders.length === 0 ? (
            <Empty message="No orders recorded yet for this strategy." />
          ) : (
            <div className="overflow-x-auto rounded-xl border border-edge/60">
              <table className="w-full text-left text-sm">
                <thead className="bg-surface/80 text-[11px] uppercase tracking-wider text-slate-500">
                  <tr>
                    <th className="px-3 py-2">Mode</th>
                    <th className="px-3 py-2">Side</th>
                    <th className="px-3 py-2">Qty</th>
                    <th className="px-3 py-2">Price</th>
                    <th className="px-3 py-2">Status</th>
                    <th className="px-3 py-2">Error</th>
                    <th className="px-3 py-2">Time</th>
                  </tr>
                </thead>
                <tbody>
                  {orders.map((o) => (
                    <tr key={o.id} className="border-t border-edge/40 text-slate-300">
                      <td className="px-3 py-2"><Badge tone={o.mode === 'LIVE' ? 'success' : 'info'}>{o.mode}</Badge></td>
                      <td className="px-3 py-2">{o.side}</td>
                      <td className="px-3 py-2">{Number(o.quantity)}</td>
                      <td className="px-3 py-2">₹{Number(o.price).toLocaleString()}</td>
                      <td className="px-3 py-2">
                        <Badge tone={o.status === 'FAILED' ? 'danger' : o.status === 'FILLED' ? 'success' : 'warn'}>
                          {o.status}
                        </Badge>
                      </td>
                      <td className="max-w-[240px] truncate px-3 py-2 text-xs text-rose-300" title={o.error ?? ''}>
                        {o.error ?? '—'}
                      </td>
                      <td className="px-3 py-2 text-xs text-slate-500">{new Date(o.createdAt).toLocaleString()}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      {tab === 'backtest' && (
        <div>
          <p className="mb-3 text-xs text-slate-500">
            Historical simulation only — LIVE requires this gate plus ≥{Math.round(strategy.paper.requiredWinRate * 100)}% paper win rate.
          </p>
          {backtests.length > 0 && (
            <div className="mb-4 flex flex-wrap items-center gap-3">
              <select
                className="rounded-xl border border-edge bg-surface px-3 py-2 text-xs text-slate-300"
                value={active?.id ?? ''}
                onChange={(e) => {
                  const bt = backtests.find((b) => b.id === e.target.value)
                  if (bt?.status === 'DONE') selectBacktest(bt)
                }}
              >
                {backtests.map((b) => (
                  <option key={b.id} value={b.id}>
                    Backtest · {new Date(b.rangeStart).toLocaleDateString()} →{' '}
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
              <Metric label="Win rate" value={`${((metrics.win_rate as number) * 100).toFixed(1)}%`} good={(metrics.win_rate as number) >= 0.55} />
              <Metric label="Max DD" value={`${metrics.max_drawdown_pct ?? 0}%`} good={false} />
              <Metric label="PF" value={String(metrics.profit_factor ?? '—')} good={Number(metrics.profit_factor) >= 1} />
              <Metric label="Sharpe" value={String(metrics.sharpe ?? '—')} />
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
    <div className="rounded-xl border border-edge/60 bg-surface/60 px-3 py-2.5">
      <div className="text-[10px] uppercase tracking-widest text-slate-500">{label}</div>
      <div
        className={`mt-0.5 text-sm font-semibold ${good === undefined ? 'text-slate-200' : good ? 'text-emerald-400' : 'text-rose-400'}`}
      >
        {value}
      </div>
    </div>
  )
}
