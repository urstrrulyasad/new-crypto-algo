import { useEffect, useState } from 'react'
import { motion } from 'motion/react'
import { api } from '@/lib/api'
import { Badge, Card, Empty, PageTitle, Spinner, Stat } from '@/components/ui'

interface Summary {
  openPositions: number
  closedPositions: number
  realizedPnl: number
  unrealizedPnl: number
  winRate: number
}

interface Position {
  id: string
  pair: string
  side: string
  quantity: number
  entryPrice: number
  exitPrice?: number
  status: string
  realizedPnl?: number
  openedAt: string
}

interface Tick {
  market: string
  lastPrice: number
  change24h: string
}

const WATCHED = ['BTCUSDT', 'ETHUSDT', 'SOLUSDT', 'XRPUSDT', 'DOGEUSDT', 'ADAUSDT']

export default function Dashboard() {
  const [summary, setSummary] = useState<Summary | null>(null)
  const [positions, setPositions] = useState<Position[] | null>(null)
  const [ticks, setTicks] = useState<Record<string, Tick>>({})

  useEffect(() => {
    api.get<Summary>('/api/v1/portfolio/summary').then(setSummary).catch(() => setSummary(null))
    api.get<Position[]>('/api/v1/portfolio/positions').then(setPositions).catch(() => setPositions([]))
  }, [])

  useEffect(() => {
    const source = new EventSource(`/api/v1/market/ticker/stream?markets=${WATCHED.join(',')}`)
    source.onmessage = (e) => {
      const tick: Tick = JSON.parse(e.data)
      setTicks((prev) => ({ ...prev, [tick.market]: tick }))
    }
    return () => source.close()
  }, [])

  return (
    <div>
      <PageTitle title="Dashboard" subtitle="Live portfolio and market overview" />

      <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
        <Stat label="Realized PnL" value={fmtMoney(summary?.realizedPnl)} accent={pnlAccent(summary?.realizedPnl)} />
        <Stat label="Unrealized PnL" value={fmtMoney(summary?.unrealizedPnl)} accent={pnlAccent(summary?.unrealizedPnl)} delay={0.06} />
        <Stat label="Open Positions" value={summary?.openPositions ?? '—'} delay={0.12} />
        <Stat label="Win Rate" value={summary ? `${(summary.winRate * 100).toFixed(1)}%` : '—'} delay={0.18} />
      </div>

      <div className="mt-6 grid gap-6 xl:grid-cols-5">
        <Card className="xl:col-span-2" delay={0.1}>
          <h2 className="mb-4 font-[family-name:var(--font-display)] text-lg font-semibold text-slate-100">Live Markets</h2>
          <div className="space-y-2">
            {WATCHED.map((m, i) => {
              const t = ticks[m]
              const change = t ? parseFloat(t.change24h) : 0
              return (
                <motion.div
                  key={m}
                  initial={{ opacity: 0, x: -12 }}
                  animate={{ opacity: 1, x: 0 }}
                  transition={{ delay: i * 0.05 }}
                  className="flex items-center justify-between rounded-xl border border-edge/60 bg-surface/50 px-4 py-3 transition-colors hover:border-cyan-500/30"
                >
                  <span className="text-sm font-medium text-slate-200">{m}</span>
                  <div className="text-right">
                    <motion.div
                      key={t?.lastPrice}
                      initial={{ opacity: 0.4 }}
                      animate={{ opacity: 1 }}
                      className="text-sm font-semibold text-slate-100"
                    >
                      {t ? `$${Number(t.lastPrice).toLocaleString()}` : '…'}
                    </motion.div>
                    {t && (
                      <div className={`text-xs ${change >= 0 ? 'text-emerald-400' : 'text-rose-400'}`}>
                        {change >= 0 ? '▲' : '▼'} {Math.abs(change).toFixed(2)}%
                      </div>
                    )}
                  </div>
                </motion.div>
              )
            })}
          </div>
        </Card>

        <Card className="xl:col-span-3" delay={0.15}>
          <h2 className="mb-4 font-[family-name:var(--font-display)] text-lg font-semibold text-slate-100">Positions</h2>
          {positions === null ? (
            <Spinner />
          ) : positions.length === 0 ? (
            <Empty message="No positions yet — create a bot in Strategy Lab and start paper trading." />
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead>
                  <tr className="border-b border-edge text-xs uppercase tracking-wider text-slate-500">
                    <th className="pb-2 pr-4">Pair</th>
                    <th className="pb-2 pr-4">Side</th>
                    <th className="pb-2 pr-4">Qty</th>
                    <th className="pb-2 pr-4">Entry</th>
                    <th className="pb-2 pr-4">Status</th>
                    <th className="pb-2">PnL</th>
                  </tr>
                </thead>
                <tbody>
                  {positions.slice(0, 12).map((p) => {
                    const pnl = positionPnl(p, ticks)
                    return (
                      <tr key={p.id} className="border-b border-edge/40 text-slate-300">
                        <td className="py-2.5 pr-4 font-medium text-slate-200">{p.pair}</td>
                        <td className="py-2.5 pr-4">
                          <Badge tone={p.side === 'LONG' ? 'success' : 'danger'}>{p.side}</Badge>
                        </td>
                        <td className="py-2.5 pr-4">{p.quantity}</td>
                        <td className="py-2.5 pr-4">${Number(p.entryPrice).toLocaleString()}</td>
                        <td className="py-2.5 pr-4">
                          <Badge tone={p.status === 'OPEN' ? 'info' : 'default'}>{p.status}</Badge>
                        </td>
                        <td className={`py-2.5 ${(pnl ?? 0) >= 0 ? 'text-emerald-400' : 'text-rose-400'}`}>
                          {pnl != null ? fmtMoney(pnl) : '—'}
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      </div>
    </div>
  )
}

function fmtMoney(v?: number | null) {
  if (v == null) return '—'
  const sign = v >= 0 ? '+' : '−'
  return `${sign}$${Math.abs(v).toLocaleString(undefined, { maximumFractionDigits: 2 })}`
}

function pairToMarket(pair: string) {
  const dash = pair.indexOf('-')
  const raw = dash >= 0 ? pair.slice(dash + 1) : pair
  return raw.replace('_', '')
}

function positionPnl(p: Position, ticks: Record<string, Tick>): number | null {
  if (p.status === 'CLOSED') return p.realizedPnl ?? null
  const tick = ticks[pairToMarket(p.pair)]
  if (!tick) return null
  const dir = p.side === 'SHORT' ? -1 : 1
  return (Number(tick.lastPrice) - Number(p.entryPrice)) * Number(p.quantity) * dir
}

function pnlAccent(v?: number | null): 'up' | 'down' | 'neutral' {
  if (v == null || v === 0) return 'neutral'
  return v > 0 ? 'up' : 'down'
}
