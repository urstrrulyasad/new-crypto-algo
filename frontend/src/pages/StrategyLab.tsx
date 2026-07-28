import { useEffect, useState } from 'react'
import { AnimatePresence, motion } from 'motion/react'
import { api } from '@/lib/api'
import { Badge, Button, Card, Empty, Input, Label, PageTitle, Spinner } from '@/components/ui'
import { CandleChart, type Candle, type TradeMarker } from '@/components/CandleChart'

interface Strategy {
  id: string
  name: string
  status: string
  origin: string
  sourceCode: string
  config: { timeframe?: string }
  createdAt: string
}

interface Provider {
  id: string
  name: string
  providerType: string
  model: string
  enabled: boolean
}

interface Backtest {
  id: string
  status: string
  timeframe: string
  metrics?: Record<string, unknown> | null
  trades?: { entry_time: string; exit_time: string; profit_ratio: number }[] | null
  rangeStart: string
  rangeEnd: string
}

const DEFAULT_PAIR = 'B-BTC_USDT'

export default function StrategyLab() {
  const [strategies, setStrategies] = useState<Strategy[] | null>(null)
  const [providers, setProviders] = useState<Provider[]>([])
  const [selected, setSelected] = useState<Strategy | null>(null)
  const [showGenerate, setShowGenerate] = useState(false)

  const load = () => api.get<Strategy[]>('/api/v1/strategies').then(setStrategies).catch(() => setStrategies([]))

  useEffect(() => {
    load()
    api.get<Provider[]>('/api/v1/ai/providers').then(setProviders).catch(() => setProviders([]))
  }, [])

  return (
    <div>
      <PageTitle title="Strategy Lab" subtitle="Generate strategies with AI, validate, backtest, approve" />

      <div className="mb-5 flex flex-wrap items-center gap-3">
        <Button onClick={() => setShowGenerate(true)}>✦ Generate with AI</Button>
      </div>

      <AnimatePresence>
        {showGenerate && (
          <GeneratePanel
            providers={providers}
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
            <Empty message="No strategies yet. Generate one with AI to get started." />
          ) : (
            strategies.map((s, i) => (
              <motion.button
                key={s.id}
                initial={{ opacity: 0, y: 14 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ delay: i * 0.05 }}
                onClick={() => setSelected(s)}
                className={`glass w-full rounded-2xl p-4 text-left transition-all duration-200 hover:border-cyan-500/40 ${selected?.id === s.id ? 'border-cyan-500/60 ring-1 ring-cyan-500/30' : ''}`}
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="truncate font-medium text-slate-100">{s.name}</span>
                  <Badge tone={s.status === 'APPROVED' ? 'success' : s.status === 'VALIDATED' ? 'info' : 'warn'}>
                    {s.status}
                  </Badge>
                </div>
                <div className="mt-1 flex gap-2 text-xs text-slate-500">
                  <span>{s.origin === 'AI_GENERATED' ? '✦ AI' : 'Manual'}</span>
                  <span>·</span>
                  <span>{s.config?.timeframe ?? '1h'}</span>
                </div>
              </motion.button>
            ))
          )}
        </div>

        <div className="xl:col-span-2">
          {selected ? <StrategyDetail strategy={selected} onChanged={load} /> : <Card><Empty message="Select a strategy to view code and run backtests." /></Card>}
        </div>
      </div>
    </div>
  )
}

function GeneratePanel({ providers, onClose, onCreated }: { providers: Provider[]; onClose: () => void; onCreated: () => void }) {
  const [name, setName] = useState('')
  const [goal, setGoal] = useState('')
  const [providerId, setProviderId] = useState(providers[0]?.id ?? '')
  const [timeframe, setTimeframe] = useState('1h')
  const [risk, setRisk] = useState('balanced')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  async function generate() {
    setBusy(true)
    setError('')
    try {
      await api.post('/api/v1/strategies/generate', {
        name,
        aiProviderId: providerId,
        goal,
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
        <div className="mb-4 flex items-center justify-between">
          <h3 className="font-[family-name:var(--font-display)] text-lg font-semibold text-slate-100">✦ AI Strategy Generator</h3>
          <button onClick={onClose} className="text-slate-500 hover:text-slate-300">✕</button>
        </div>
        {providers.length === 0 ? (
          <p className="text-sm text-amber-300">
            No AI providers configured. Ask your admin to add one in the Admin panel (Claude, Gemini, Grok or any
            OpenAI-compatible endpoint).
          </p>
        ) : (
          <div className="grid gap-4 md:grid-cols-2">
            <div>
              <Label>Strategy name</Label>
              <Input value={name} onChange={(e) => setName(e.target.value)} placeholder="Momentum RSI v1" />
            </div>
            <div>
              <Label>AI provider</Label>
              <select
                value={providerId}
                onChange={(e) => setProviderId(e.target.value)}
                className="w-full rounded-xl border border-edge bg-surface px-4 py-2.5 text-sm text-slate-200"
              >
                {providers.filter((p) => p.enabled).map((p) => (
                  <option key={p.id} value={p.id}>{p.name} · {p.model}</option>
                ))}
              </select>
            </div>
            <div className="md:col-span-2">
              <Label>Goal — describe what the strategy should do</Label>
              <textarea
                value={goal}
                onChange={(e) => setGoal(e.target.value)}
                rows={3}
                placeholder="Trend-following strategy using EMA crossover with RSI confirmation, conservative stoploss…"
                className="w-full rounded-xl border border-edge bg-surface px-4 py-2.5 text-sm text-slate-200 placeholder-slate-500 outline-none focus:border-cyan-500/60"
              />
            </div>
            <div>
              <Label>Timeframe</Label>
              <select value={timeframe} onChange={(e) => setTimeframe(e.target.value)} className="w-full rounded-xl border border-edge bg-surface px-4 py-2.5 text-sm text-slate-200">
                {['5m', '15m', '30m', '1h', '4h', '1d'].map((tf) => <option key={tf}>{tf}</option>)}
              </select>
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
              <Button onClick={generate} disabled={busy || !name || !goal}>
                {busy ? 'Generating & validating…' : 'Generate strategy'}
              </Button>
            </div>
          </div>
        )}
      </div>
    </motion.div>
  )
}

function StrategyDetail({ strategy, onChanged }: { strategy: Strategy; onChanged: () => void }) {
  const [tab, setTab] = useState<'backtest' | 'code'>('backtest')
  const [backtests, setBacktests] = useState<Backtest[]>([])
  const [active, setActive] = useState<Backtest | null>(null)
  const [candles, setCandles] = useState<Candle[]>([])
  const [busy, setBusy] = useState(false)
  const [days, setDays] = useState(30)

  const loadBacktests = () =>
    api.get<Backtest[]>(`/api/v1/backtests?strategyId=${strategy.id}`).then((list) => {
      setBacktests(list)
      const done = list.find((b) => b.status === 'DONE')
      if (done && !active) selectBacktest(done)
    })

  useEffect(() => {
    setActive(null)
    setCandles([])
    loadBacktests()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [strategy.id])

  async function selectBacktest(bt: Backtest) {
    setActive(bt)
    const data = await api.get<Candle[]>(
      `/api/v1/market/candles?pair=${DEFAULT_PAIR}&timeframe=${bt.timeframe}&from=${bt.rangeStart}&to=${bt.rangeEnd}`,
    )
    setCandles(data)
  }

  async function runBacktest() {
    setBusy(true)
    try {
      const to = new Date()
      const from = new Date(Date.now() - days * 86400_000)
      await api.post('/api/v1/backtests', {
        strategyId: strategy.id,
        timeframe: strategy.config?.timeframe ?? '1h',
        pairs: [DEFAULT_PAIR],
        from: from.toISOString(),
        to: to.toISOString(),
      })
      // poll until finished
      const poll = setInterval(async () => {
        const list = await api.get<Backtest[]>(`/api/v1/backtests?strategyId=${strategy.id}`)
        setBacktests(list)
        const latest = list[0]
        if (latest && latest.status !== 'RUNNING' && latest.status !== 'PENDING') {
          clearInterval(poll)
          setBusy(false)
          if (latest.status === 'DONE') selectBacktest(latest)
        }
      }, 3000)
    } catch {
      setBusy(false)
    }
  }

  async function approve() {
    await api.post(`/api/v1/strategies/${strategy.id}/approve`)
    onChanged()
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
          <div className="mt-1 flex items-center gap-2">
            <Badge tone={strategy.status === 'APPROVED' ? 'success' : 'info'}>{strategy.status}</Badge>
            <span className="text-xs text-slate-500">{strategy.config?.timeframe ?? '1h'} · {DEFAULT_PAIR}</span>
          </div>
        </div>
        <div className="flex gap-2">
          {strategy.status === 'VALIDATED' && <Button onClick={approve}>Approve for trading</Button>}
          <Button variant="ghost" onClick={() => setTab(tab === 'code' ? 'backtest' : 'code')}>
            {tab === 'code' ? '📊 Backtest' : '⟨/⟩ Code'}
          </Button>
        </div>
      </div>

      {tab === 'code' ? (
        <pre className="max-h-[480px] overflow-auto rounded-xl border border-edge bg-black/40 p-4 text-xs leading-relaxed text-emerald-200/90">
          {strategy.sourceCode}
        </pre>
      ) : (
        <div>
          <div className="mb-4 flex flex-wrap items-end gap-3">
            <div>
              <Label>Lookback (days)</Label>
              <Input type="number" value={days} onChange={(e) => setDays(Number(e.target.value))} className="w-28" />
            </div>
            <Button onClick={runBacktest} disabled={busy}>
              {busy ? 'Running backtest…' : '▶ Run backtest'}
            </Button>
            {backtests.length > 0 && (
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
                    {new Date(b.rangeStart).toLocaleDateString()} → {new Date(b.rangeEnd).toLocaleDateString()} · {b.status}
                  </option>
                ))}
              </select>
            )}
          </div>

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
          ) : (
            <Empty message="Run a backtest to see trades plotted on the chart." />
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
