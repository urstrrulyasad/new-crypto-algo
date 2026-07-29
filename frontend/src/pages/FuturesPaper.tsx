import { useEffect, useState } from 'react'
import { motion } from 'motion/react'
import { api } from '@/lib/api'
import { Badge, Button, Card, Empty, PageTitle, Spinner } from '@/components/ui'

interface Bot {
  id: string
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
  status: string
  realizedPnl?: number
  marginCurrency?: string
}

export default function FuturesPaper() {
  const [bots, setBots] = useState<Bot[] | null>(null)
  const [positions, setPositions] = useState<Position[]>([])
  const [stopping, setStopping] = useState(false)

  const load = () => {
    api
      .get<Bot[]>('/api/v1/bots?marketType=FUTURES&mode=PAPER')
      .then(setBots)
      .catch(() => setBots([]))
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

  const openByBot = positions.filter((p) => p.status === 'OPEN')

  return (
    <div>
      <div className="mb-6 flex flex-wrap items-start justify-between gap-4">
        <PageTitle
          title="Futures Paper Trade"
          subtitle="Auto-spawned INR paper bots — stop/kill only, no manual create"
        />
        <Button variant="danger" onClick={stopAll} disabled={stopping}>
          {stopping ? 'Stopping…' : 'Stop all futures'}
        </Button>
      </div>

      {bots === null ? (
        <Spinner />
      ) : bots.length === 0 ? (
        <Card>
          <Empty message="No paper bots yet. They appear when a futures strategy passes the backtest gate." />
        </Card>
      ) : (
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {bots.map((b, i) => (
            <Card key={b.id} delay={i * 0.04}>
              <div className="flex items-start justify-between gap-2">
                <div>
                  <div className="font-medium text-slate-100">{b.name}</div>
                  <div className="mt-1 flex flex-wrap gap-1.5">
                    <Badge tone="info">{b.mode}</Badge>
                    <Badge>FUTURES</Badge>
                    <Badge tone={b.status === 'RUNNING' ? 'success' : 'default'}>{b.status}</Badge>
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
                Open positions:{' '}
                {openByBot.filter((p) => p.botId === b.id).length}
              </div>
              <div className="mt-4 flex gap-2">
                {b.status === 'RUNNING' ? (
                  <Button variant="ghost" onClick={() => action(b.id, 'stop')}>
                    Stop
                  </Button>
                ) : (
                  <Button onClick={() => action(b.id, 'start')}>Start</Button>
                )}
                <Button variant="danger" onClick={() => kill(b.id, !b.killSwitch)}>
                  {b.killSwitch ? 'Release kill' : 'Kill switch'}
                </Button>
              </div>
            </Card>
          ))}
        </div>
      )}

      <Card className="mt-6" delay={0.1}>
        <h2 className="mb-4 font-[family-name:var(--font-display)] text-lg font-semibold text-slate-100">
          Recent paper positions
        </h2>
        {positions.length === 0 ? (
          <Empty message="No positions yet." />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead>
                <tr className="border-b border-edge text-xs uppercase tracking-wider text-slate-500">
                  <th className="pb-2 pr-4">Instrument</th>
                  <th className="pb-2 pr-4">Side</th>
                  <th className="pb-2 pr-4">Qty</th>
                  <th className="pb-2 pr-4">Entry</th>
                  <th className="pb-2 pr-4">Status</th>
                  <th className="pb-2">PnL</th>
                </tr>
              </thead>
              <tbody>
                {positions.slice(0, 30).map((p) => (
                  <tr key={p.id} className="border-b border-edge/40 text-slate-300">
                    <td className="py-2.5 pr-4 font-medium text-slate-200">{p.pair}</td>
                    <td className="py-2.5 pr-4">
                      <Badge tone={p.side === 'LONG' ? 'success' : 'danger'}>{p.side}</Badge>
                    </td>
                    <td className="py-2.5 pr-4">{p.quantity}</td>
                    <td className="py-2.5 pr-4">₹{Number(p.entryPrice).toLocaleString()}</td>
                    <td className="py-2.5 pr-4">
                      <Badge tone={p.status === 'OPEN' ? 'info' : 'default'}>{p.status}</Badge>
                    </td>
                    <td
                      className={`py-2.5 ${(p.realizedPnl ?? 0) >= 0 ? 'text-emerald-400' : 'text-rose-400'}`}
                    >
                      {p.realizedPnl != null
                        ? `₹${Number(p.realizedPnl).toFixed(2)}`
                        : '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </div>
  )
}
