import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { motion } from 'motion/react'
import { api } from '@/lib/api'
import { formatDateTime } from '@/lib/datetime'
import { PositionCard } from '@/components/TradeLedger'
import { Badge, Button, Card, Empty, PageShell, PageTitle, Spinner } from '@/components/ui'

interface Bot {
  id: string
  strategyId?: string
  name: string
  mode: string
  marketType: string
  status: string
  killSwitch: boolean
  stakeAmount: number
  stakeCurrency: string
  marginCurrency?: string
  maxOpenTrades: number
  leverage?: number
}

interface Position {
  id: string
  botId: string
  pair: string
  side: string
  quantity: number
  entryPrice: number
  exitPrice?: number | null
  status: string
  realizedPnl?: number
  marginCurrency?: string
  openedAt?: string
  closedAt?: string | null
}

export default function FuturesPaper() {
  const navigate = useNavigate()
  const [bots, setBots] = useState<Bot[] | null>(null)
  const [liveStrategyIds, setLiveStrategyIds] = useState<Set<string>>(new Set())
  const [positions, setPositions] = useState<Position[]>([])
  const [stopping, setStopping] = useState(false)

  const load = () => {
    api
      .get<Bot[]>('/api/v1/bots?marketType=FUTURES&mode=PAPER')
      .then(setBots)
      .catch(() => setBots([]))
    api
      .get<Bot[]>('/api/v1/bots?marketType=FUTURES&mode=LIVE')
      .then((live) => {
        setLiveStrategyIds(
          new Set(
            live
              .filter((b) => b.status === 'RUNNING' && b.strategyId)
              .map((b) => b.strategyId as string),
          ),
        )
      })
      .catch(() => setLiveStrategyIds(new Set()))
    api
      .get<Position[]>('/api/v1/portfolio/positions?mode=PAPER')
      .then(setPositions)
      .catch(() => setPositions([]))
  }

  useEffect(() => {
    load()
    const poll = setInterval(load, 10_000)
    return () => clearInterval(poll)
  }, [])

  async function action(id: string, act: 'start' | 'stop') {
    await api.post(`/api/v1/bots/${id}/${act}`)
    load()
  }

  async function kill(id: string, enabled: boolean) {
    await api.post(`/api/v1/bots/${id}/kill-switch?enabled=${enabled}`)
    load()
  }

  async function stopAll() {
    if (!confirm('Stop all running futures bots?')) return
    setStopping(true)
    try {
      await api.post('/api/v1/bots/stop-all-futures')
      load()
    } finally {
      setStopping(false)
    }
  }

  const open = positions.filter((p) => p.status === 'OPEN')
  const closed = positions.filter((p) => p.status !== 'OPEN')

  // Automated paper: only show RUNNING bots. Hide STOPPED (incl. promoted-to-LIVE).
  const visibleBots = useMemo(() => {
    if (!bots) return null
    return bots.filter((b) => b.status === 'RUNNING')
  }, [bots, liveStrategyIds])

  return (
    <PageShell>
      <div className="mb-6 flex flex-col gap-4 sm:flex-row sm:flex-wrap sm:items-start sm:justify-between">
        <PageTitle
          title="Futures Paper Trade"
          subtitle="Open & closed paper positions with entry / exit — stop/kill bots only"
        />
        <Button variant="danger" onClick={stopAll} disabled={stopping} className="w-full sm:w-auto">
          {stopping ? 'Stopping…' : 'Stop all futures'}
        </Button>
      </div>

      {visibleBots === null ? (
        <Spinner />
      ) : visibleBots.length === 0 ? (
        <Card>
          <Empty message="No active paper bots. Promoted strategies run under LIVE on the Dashboard." />
        </Card>
      ) : (
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {visibleBots.map((b, i) => {
            const promoted = !!(b.strategyId && liveStrategyIds.has(b.strategyId))
            return (
              <Card key={b.id} delay={i * 0.04}>
                <div className="flex items-start justify-between gap-2">
                  <div>
                    <div className="font-medium text-slate-100">{b.name}</div>
                    <div className="mt-1 flex flex-wrap gap-1.5">
                      <Badge tone="info">{b.mode}</Badge>
                      <Badge>FUTURES</Badge>
                      <Badge tone={b.status === 'RUNNING' ? 'success' : 'default'}>{b.status}</Badge>
                      {promoted && <Badge tone="success">LIVE</Badge>}
                      {b.killSwitch && <Badge tone="danger">KILL</Badge>}
                    </div>
                  </div>
                  {b.status === 'RUNNING' && (
                    <motion.span
                      animate={{ opacity: [1, 0.3, 1] }}
                      transition={{ repeat: Infinity, duration: 2 }}
                      className="mt-1 h-2.5 w-2.5 shrink-0 rounded-full bg-emerald-400"
                    />
                  )}
                </div>
                <div className="mt-3 text-xs text-slate-400">
                  Stake ₹{b.stakeAmount} · {b.leverage ?? 1}x · max {b.maxOpenTrades} open
                </div>
                <div className="mt-2 text-xs text-slate-500">
                  Open positions: {open.filter((p) => p.botId === b.id).length}
                </div>
                <div className="mt-4 flex flex-wrap gap-2">
                  <Button variant="ghost" onClick={() => action(b.id, 'stop')}>
                    Stop
                  </Button>
                  <Button variant="danger" onClick={() => kill(b.id, !b.killSwitch)}>
                    {b.killSwitch ? 'Release kill' : 'Kill switch'}
                  </Button>
                </div>
              </Card>
            )
          })}
        </div>
      )}

      <PositionTable
        title={`Open paper positions (${open.length})`}
        rows={open}
        empty="No open paper positions."
        onRow={(p) =>
          navigate(`/futures/chart/${encodeURIComponent(p.pair)}?mode=paper&positionId=${p.id}&timeframe=5m`)
        }
      />
      <PositionTable
        title={`Closed paper positions (${closed.length})`}
        rows={closed.slice(0, 50)}
        empty="No closed paper positions yet."
        onRow={(p) =>
          navigate(`/futures/chart/${encodeURIComponent(p.pair)}?mode=paper&positionId=${p.id}&timeframe=5m`)
        }
      />
    </PageShell>
  )
}

function PositionTable({
  title,
  rows,
  empty,
  onRow,
}: {
  title: string
  rows: Position[]
  empty: string
  onRow: (p: Position) => void
}) {
  return (
    <Card className="mt-6" delay={0.1} hover={false}>
      <h2 className="mb-4 font-[family-name:var(--font-display)] text-lg font-semibold text-slate-100">
        {title}
      </h2>
      {rows.length === 0 ? (
        <Empty message={empty} />
      ) : (
        <>
          <div className="space-y-2 md:hidden">
            {rows.map((p) => (
              <PositionCard
                key={p.id}
                pair={p.pair}
                side={p.side}
                status={p.status}
                quantity={p.quantity}
                entryPrice={p.entryPrice}
                exitPrice={p.exitPrice}
                pnl={p.realizedPnl}
                openedAt={p.openedAt}
                closedAt={p.closedAt}
                onClick={() => onRow(p)}
              />
            ))}
          </div>
          <div className="hidden overflow-x-auto md:block">
            <table className="w-full min-w-[780px] text-left text-sm">
              <thead>
                <tr className="border-b border-edge text-xs uppercase tracking-wider text-slate-500">
                  <th className="pb-2 pr-4">Instrument</th>
                  <th className="pb-2 pr-4">Side</th>
                  <th className="pb-2 pr-4">Qty</th>
                  <th className="pb-2 pr-4">Entry</th>
                  <th className="pb-2 pr-4">Exit</th>
                  <th className="pb-2 pr-4">Status</th>
                  <th className="pb-2 pr-4">PnL</th>
                  <th className="pb-2 pr-4">Opened</th>
                  <th className="pb-2">Closed</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((p) => (
                  <tr
                    key={p.id}
                    className="data-row cursor-pointer border-b border-edge/40 text-slate-300"
                    onClick={() => onRow(p)}
                  >
                    <td className="py-2.5 pr-4 font-medium text-slate-200">{p.pair}</td>
                    <td className="py-2.5 pr-4">
                      <Badge tone={p.side === 'LONG' ? 'success' : 'danger'}>{p.side}</Badge>
                    </td>
                    <td className="py-2.5 pr-4">{p.quantity}</td>
                    <td className="py-2.5 pr-4">₹{Number(p.entryPrice).toLocaleString()}</td>
                    <td className="py-2.5 pr-4">
                      {p.exitPrice != null ? `₹${Number(p.exitPrice).toLocaleString()}` : '—'}
                    </td>
                    <td className="py-2.5 pr-4">
                      <Badge tone={p.status === 'OPEN' ? 'info' : 'default'}>{p.status}</Badge>
                    </td>
                    <td className={`py-2.5 pr-4 ${(p.realizedPnl ?? 0) >= 0 ? 'text-emerald-400' : 'text-rose-400'}`}>
                      {p.realizedPnl != null ? `₹${Number(p.realizedPnl).toFixed(2)}` : '—'}
                    </td>
                    <td className="whitespace-nowrap py-2.5 pr-4 text-xs text-slate-300">
                      {formatDateTime(p.openedAt)}
                    </td>
                    <td className="whitespace-nowrap py-2.5 text-xs text-slate-300">
                      {formatDateTime(p.closedAt)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </Card>
  )
}
