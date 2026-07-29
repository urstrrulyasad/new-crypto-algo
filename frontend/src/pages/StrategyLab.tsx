import { useEffect, useRef, useState } from 'react'
import { AnimatePresence, motion } from 'motion/react'
import { api } from '@/lib/api'
import { Badge, Button, Card, Empty, Input, Label, PageTitle, Spinner } from '@/components/ui'
import { CandleChart, type Candle, type TradeMarker } from '@/components/CandleChart'

interface PaperProgress {
  closedTrades: number
  wins: number
  winRate: number
  totalPnl: number
  requiredTrades: number
  requiredWinRate: number
}

interface Strategy {
  id: string
  name: string
  status: string
  origin: string
  sourceCode: string
  config: {
    timeframe?: string
    pairs?: string[]
    provider_used?: string
    model_used?: string
    generation_errors?: unknown
  }
  prompt?: string
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

const DEFAULT_PAIR = 'B-BTC_USDT'
const PIPELINE_STEPS = ['GENERATED', 'BACKTESTED', 'PAPER_TRADING', 'LIVE_APPROVED']

const STATUS_TONE: Record<string, 'success' | 'info' | 'warn' | 'danger'> = {
  LIVE_APPROVED: 'success',
  PAPER_TRADING: 'info',
  BACKTESTED: 'info',
  GENERATED: 'warn',
  REJECTED: 'danger',
}

export default function StrategyLab() {
  const [strategies, setStrategies] = useState<Strategy[] | null>(null)
  const [selected, setSelected] = useState<Strategy | null>(null)
  const [showGenerate, setShowGenerate] = useState(false)

  const load = () =>
    api.get<Strategy[]>('/api/v1/strategies').then((list) => {
      setStrategies(list)
      setSelected((prev) => (prev ? list.find((s) => s.id === prev.id) ?? prev : prev))
    }).catch(() => setStrategies([]))

  useEffect(() => {
    load()
    // the pipeline advances in the background (backtest -> paper bot -> live gate)
    const poll = setInterval(load, 10_000)
    return () => clearInterval(poll)
  }, [])

  return (
    <div>
      <PageTitle
        title="AI Strategy Pipeline"
        subtitle="Fully autonomous: AI generates → auto backtest → paper trades → auto-approved live at the win-rate gate"
      />

      <div className="mb-5 flex flex-wrap items-center gap-3">
        <Button onClick={() => setShowGenerate(true)}>✦ Generate strategy</Button>
      </div>

      <AnimatePresence>
        {showGenerate && (
          <GeneratePanel
            onClose={() => setShowGenerate(false)}
            onCreated={() => {
              setShowGenerate(false)
              load()
            }}
          />
        )}
      </AnimatePresence>

      <div className="grid gap-6 xl:grid-cols-3">
        <div className="space-y-3 xl:col-span-1">
          {strategies === null ? (
            <Spinner />
          ) : strategies.length === 0 ? (
            <Empty message="No strategies yet. Hit Generate — the AI does the rest." />
          ) : (
            strategies.map((s, i) => (
              <motion.button
                key={s.id}
                initial={{ opacity: 0, y: 14 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ delay: Math.min(i * 0.05, 0.4) }}
                onClick={() => setSelected(s)}
                className={`glass w-full rounded-2xl p-4 text-left transition-all duration-200 hover:border-cyan-500/40 ${selected?.id === s.id ? 'border-cyan-500/60 ring-1 ring-cyan-500/30' : ''}`}
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="truncate font-medium text-slate-100">{s.name}</span>
                  <Badge tone={STATUS_TONE[s.status] ?? 'warn'}>{s.status.replace('_', ' ')}</Badge>
                </div>
                <div className="mt-1 flex gap-2 text-xs text-slate-500">
                  <span>✦ {s.config?.provider_used ?? 'AI'}</span>
                  <span>·</span>
                  <span>{s.config?.timeframe ?? '1h'}</span>
                </div>
                {(s.status === 'PAPER_TRADING' || s.status === 'LIVE_APPROVED') && (
                  <PaperBar paper={s.paper} compact />
                )}
              </motion.button>
            ))
          )}
        </div>

        <div className="xl:col-span-2">
          {selected
            ? <StrategyDetail strategy={selected} />
            : <Card><Empty message="Select a strategy to follow its pipeline, backtest and paper trades." /></Card>}
        </div>
      </div>
    </div>
  )
}

function GeneratePanel({ onClose, onCreated }: { onClose: () => void; onCreated: () => void }) {
  const [name, setName] = useState('')
  const [goal, setGoal] = useState('')
  const [timeframe, setTimeframe] = useState('1m')
  const [risk, setRisk] = useState('balanced')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  async function generate() {
    setBusy(true)
    setError('')
    try {
      await api.post('/api/v1/strategies/generate', {
        name: name || undefined,
        goal: goal || undefined,
        timeframe,
        pairs: [DEFAULT_PAIR],
        riskProfile: risk,
      })
      onCreated()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Generation failed')
    } finally {
      setBusy(false)
    }
  }

  return (
    <motion.div
      initial={{ opacity: 0, height: 0 }}
      animate={{ opacity: 1, height: 'auto' }}
      exit={{ opacity: 0, height: 0 }}
      transition={{ duration: 0.4, ease: [0.22, 1, 0.36, 1] }}
      className="mb-6 overflow-hidden"
    >
      <div className="glass rounded-2xl p-6">
        <div className="mb-2 flex items-center justify-between">
          <h3 className="font-[family-name:var(--font-display)] text-lg font-semibold text-slate-100">✦ Autonomous pipeline</h3>
          <button onClick={onClose} className="text-slate-500 hover:text-slate-300">✕</button>
        </div>
        <p className="mb-4 text-xs text-slate-500">
          The AI picks its own indicators using live market data, backtests automatically and starts
          paper trading. Once it closes {`≥`} the required paper trades at the required win rate, it is
          auto-approved for live trading. Every field below is optional.
        </p>
        <div className="grid gap-4 md:grid-cols-2">
          <div>
            <Label>Name (optional)</Label>
            <Input value={name} onChange={(e) => setName(e.target.value)} placeholder="Auto-named if empty" />
          </div>
          <div>
            <Label>Timeframe</Label>
            <select value={timeframe} onChange={(e) => setTimeframe(e.target.value)} className="w-full rounded-xl border border-edge bg-surface px-4 py-2.5 text-sm text-slate-200">
              {['1m', '15m', '1h', '1d'].map((tf) => <option key={tf}>{tf}</option>)}
            </select>
          </div>
          <div className="md:col-span-2">
            <Label>Goal (optional — the AI defaults to a risk-controlled momentum strategy)</Label>
            <textarea
              value={goal}
              onChange={(e) => setGoal(e.target.value)}
              rows={2}
              placeholder="e.g. Mean-reversion on Bollinger Bands with volume confirmation…"
              className="w-full rounded-xl border border-edge bg-surface px-4 py-2.5 text-sm text-slate-200 placeholder-slate-500 outline-none focus:border-cyan-500/60"
            />
          </div>
          <div>
            <Label>Risk profile</Label>
            <select value={risk} onChange={(e) => setRisk(e.target.value)} className="w-full rounded-xl border border-edge bg-surface px-4 py-2.5 text-sm text-slate-200">
              <option value="conservative">Conservative</option>
              <option value="balanced">Balanced</option>
              <option value="aggressive">Aggressive</option>
            </select>
          </div>
          {error && <p className="text-sm text-rose-400 md:col-span-2">{error}</p>}
          <div className="md:col-span-2">
            <Button onClick={generate} disabled={busy}>
              {busy ? 'Generating (AI failover chain)…' : '✦ Generate & start pipeline'}
            </Button>
          </div>
        </div>
      </div>
    </motion.div>
  )
}

function PipelineStepper({ status }: { status: string }) {
  if (status === 'REJECTED') {
    return <p className="text-sm text-rose-400">Rejected — the generated code failed safety validation.</p>
  }
  const current = PIPELINE_STEPS.indexOf(status)
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      {PIPELINE_STEPS.map((step, i) => (
        <div key={step} className="flex items-center gap-1.5">
          <div className={`flex items-center gap-1.5 rounded-full px-3 py-1 text-[11px] font-medium ${
            i < current ? 'bg-emerald-500/15 text-emerald-300'
              : i === current ? 'bg-cyan-500/20 text-cyan-300 ring-1 ring-cyan-500/40'
              : 'bg-surface/80 text-slate-600'
          }`}>
            {i < current ? '✓' : i === current ? '●' : '○'} {step.replace('_', ' ')}
          </div>
          {i < PIPELINE_STEPS.length - 1 && <span className="text-slate-700">→</span>}
        </div>
      ))}
    </div>
  )
}

function PaperBar({ paper, compact }: { paper: PaperProgress; compact?: boolean }) {
  const tradePct = Math.min(100, (paper.closedTrades / paper.requiredTrades) * 100)
  const winPct = Math.round(paper.winRate * 100)
  const needPct = Math.round(paper.requiredWinRate * 100)
  return (
    <div className={compact ? 'mt-2' : 'mt-1'}>
      <div className="flex items-center justify-between text-[11px] text-slate-500">
        <span>Paper: {paper.closedTrades}/{paper.requiredTrades} trades</span>
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

function StrategyDetail({ strategy }: { strategy: Strategy }) {
  const [tab, setTab] = useState<'backtest' | 'code'>('backtest')
  const [backtests, setBacktests] = useState<Backtest[]>([])
  const [active, setActive] = useState<Backtest | null>(null)
  const [candles, setCandles] = useState<Candle[]>([])
  const activeRef = useRef<Backtest | null>(null)

  const loadBacktests = () =>
    api.get<Backtest[]>(`/api/v1/backtests?strategyId=${strategy.id}`).then((list) => {
      setBacktests(list)
      const done = list.find((b) => b.status === 'DONE')
      if (done && !activeRef.current) selectBacktest(done)
    }).catch(() => setBacktests([]))

  useEffect(() => {
    activeRef.current = null
    setActive(null)
    setCandles([])
    loadBacktests()
    // the auto-backtest may still be running right after generation
    const poll = setInterval(loadBacktests, 8_000)
    return () => clearInterval(poll)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [strategy.id])

  async function selectBacktest(bt: Backtest) {
    activeRef.current = bt
    setActive(bt)
    try {
      const pair = strategy.config?.pairs?.[0] ?? DEFAULT_PAIR
      const data = await api.get<Candle[]>(
        `/api/v1/market/candles?pair=${pair}&timeframe=${bt.timeframe}&from=${bt.rangeStart}&to=${bt.rangeEnd}`,
      )
      setCandles(data)
    } catch {
      setCandles([])
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
          <h2 className="font-[family-name:var(--font-display)] text-xl font-semibold text-slate-100">{strategy.name}</h2>
          <div className="mt-1 text-xs text-slate-500">
            {strategy.config?.provider_used && (
              <span>✦ {strategy.config.provider_used} / {strategy.config.model_used} · </span>
            )}
            {strategy.config?.timeframe ?? '1h'} · {(strategy.config?.pairs ?? [DEFAULT_PAIR]).join(', ')}
          </div>
        </div>
        <Button variant="ghost" onClick={() => setTab(tab === 'code' ? 'backtest' : 'code')}>
          {tab === 'code' ? '📊 Pipeline' : '⟨/⟩ Code'}
        </Button>
      </div>

      <div className="mb-4">
        <PipelineStepper status={strategy.status} />
      </div>

      {(strategy.status === 'PAPER_TRADING' || strategy.status === 'LIVE_APPROVED') && (
        <div className="mb-4 rounded-xl border border-edge/60 bg-surface/50 px-4 py-3">
          <div className="mb-1 text-xs font-medium uppercase tracking-widest text-slate-500">
            {strategy.status === 'LIVE_APPROVED' ? 'Passed the paper gate — live bot running' : 'Paper-trade gate'}
          </div>
          <PaperBar paper={strategy.paper} />
          <div className="mt-2 flex gap-4 text-xs text-slate-400">
            <span>Wins: <span className="text-emerald-400">{strategy.paper.wins}</span></span>
            <span>Losses: <span className="text-rose-400">{strategy.paper.closedTrades - strategy.paper.wins}</span></span>
            <span>Paper PnL: <span className={strategy.paper.totalPnl >= 0 ? 'text-emerald-400' : 'text-rose-400'}>
              {Number(strategy.paper.totalPnl).toFixed(2)} USDT
            </span></span>
          </div>
        </div>
      )}

      {tab === 'code' ? (
        <pre className="max-h-[480px] overflow-auto rounded-xl border border-edge bg-black/40 p-4 text-xs leading-relaxed text-emerald-200/90">
          {strategy.sourceCode}
        </pre>
      ) : (
        <div>
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
                    Auto backtest · {new Date(b.rangeStart).toLocaleDateString()} → {new Date(b.rangeEnd).toLocaleDateString()} · {b.status}
                  </option>
                ))}
              </select>
              {backtests[0]?.status === 'RUNNING' && (
                <span className="text-xs text-cyan-300">Auto backtest running…</span>
              )}
              {backtests[0]?.status === 'FAILED' && (
                <span className="text-xs text-rose-400">Backtest failed: {backtests[0]?.error ?? 'unknown'}</span>
              )}
            </div>
          )}

          {metrics && (
            <motion.div
              initial={{ opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
              className="mb-4 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6"
            >
              <Metric label="Profit" value={`${metrics.profit_total_pct ?? 0}%`} good={Number(metrics.profit_total_pct) > 0} />
              <Metric label="Trades" value={String(metrics.trades ?? 0)} />
              <Metric label="Win rate" value={`${((metrics.win_rate as number) * 100).toFixed(1)}%`} good={(metrics.win_rate as number) > 0.5} />
              <Metric label="Max DD" value={`${metrics.max_drawdown_pct ?? 0}%`} good={false} />
              <Metric label="Wins" value={String(metrics.wins ?? 0)} good />
              <Metric label="TF" value={String(metrics.timeframe ?? '')} />
            </motion.div>
          )}

          {candles.length > 0 ? (
            <CandleChart candles={candles} markers={markers} />
          ) : strategy.status === 'GENERATED' ? (
            <Empty message="Auto backtest is queued — results appear here when it finishes." />
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
      <div className={`mt-0.5 text-sm font-semibold ${good === undefined ? 'text-slate-200' : good ? 'text-emerald-400' : 'text-rose-400'}`}>
        {value}
      </div>
    </div>
  )
}
