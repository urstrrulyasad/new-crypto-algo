import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { motion } from 'motion/react'
import { api } from '@/lib/api'
import { formatDateTime } from '@/lib/datetime'
import { useToast } from '@/lib/toast'
import { OrderCard, PositionCard } from '@/components/TradeLedger'
import { Badge, Button, Callout, Card, Empty, Input, Label, PageShell, PageTitle, Spinner, Stat } from '@/components/ui'

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

interface Summary {
  openPositions: number
  closedPositions: number
  realizedPnl: number
  unrealizedPnl: number
  winRate: number
}

interface Position {
  id: string
  botId: string
  pair: string
  side: string
  quantity: number
  entryPrice: number
  exitPrice?: number | null
  markPrice?: number | null
  status: string
  realizedPnl?: number | null
  unrealizedPnl?: number | null
  pnl?: number | null
  marginCurrency?: string
  openedAt?: string
  closedAt?: string | null
}

interface Order {
  id: string
  pair: string
  side: string
  status: string
  price: number
  quantity: number
  avgPrice?: number | null
  sizeInr?: number | null
  mode: string
  error?: string | null
  createdAt: string
}

const PAPER_PAIRS = [
  'B-BTC_USDT',
  'B-ETH_USDT',
  'B-SOL_USDT',
  'B-XRP_USDT',
  'B-BNB_USDT',
  'B-DOGE_USDT',
]

export default function FuturesPaper() {
  const navigate = useNavigate()
  const { pushToast } = useToast()
  const [bots, setBots] = useState<Bot[] | null>(null)
  const [summary, setSummary] = useState<Summary | null>(null)
  const [positions, setPositions] = useState<Position[]>([])
  const [orders, setOrders] = useState<Order[]>([])
  const [stopping, setStopping] = useState(false)
  const [confirmStop, setConfirmStop] = useState(false)

  const [orderOpen, setOrderOpen] = useState(false)
  const [pair, setPair] = useState('B-BTC_USDT')
  const [side, setSide] = useState<'LONG' | 'SHORT'>('LONG')
  const [quantity, setQuantity] = useState('0.01')
  const [leverage, setLeverage] = useState('3')
  const [placing, setPlacing] = useState(false)
  const [closingId, setClosingId] = useState<string | null>(null)

  const load = useCallback(() => {
    api.get<Summary>('/api/v1/portfolio/summary?mode=PAPER').then(setSummary).catch(() => setSummary(null))
    api
      .get<Bot[]>('/api/v1/bots?marketType=FUTURES&mode=PAPER')
      .then(setBots)
      .catch(() => setBots([]))
    api
      .get<Position[]>('/api/v1/portfolio/positions?mode=PAPER')
      .then(setPositions)
      .catch(() => setPositions([]))
    api
      .get<Order[]>('/api/v1/portfolio/orders?mode=PAPER')
      .then(setOrders)
      .catch(() => setOrders([]))
  }, [])

  useEffect(() => {
    load()
    const poll = setInterval(load, 8_000)
    return () => clearInterval(poll)
  }, [load])

  async function placePaperOrder(e: React.FormEvent) {
    e.preventDefault()
    const qty = Number(quantity)
    const lev = Number(leverage)
    if (!Number.isFinite(qty) || qty <= 0) {
      pushToast({ title: 'Enter a valid positive quantity', tone: 'error' })
      return
    }
    setPlacing(true)
    try {
      const created = await api.post<Position>('/api/v1/paper/orders', {
        pair,
        side,
        quantity: qty,
        leverage: Number.isFinite(lev) ? lev : 3,
      })
      setOrderOpen(false)
      pushToast({
        title: `Paper ${created.side} opened on ${created.pair}`,
        detail: `Entry ${created.entryPrice} · Live CoinDCX mark pricing`,
        tone: 'success',
      })
      load()
    } catch (err) {
      pushToast({
        title: 'Paper order failed',
        detail: err instanceof Error ? err.message : 'Unable to execute paper trade',
        tone: 'error',
      })
    } finally {
      setPlacing(false)
    }
  }

  async function closePosition(pos: Position) {
    setClosingId(pos.id)
    try {
      const closed = await api.post<Position>(`/api/v1/paper/positions/${pos.id}/close`)
      pushToast({
        title: `Closed ${closed.pair} paper position`,
        detail: closed.realizedPnl != null ? `Realized ${fmtInr(closed.realizedPnl)}` : undefined,
        tone: (closed.realizedPnl ?? 0) >= 0 ? 'success' : 'warn',
      })
      load()
    } catch (err) {
      pushToast({
        title: 'Close failed',
        detail: err instanceof Error ? err.message : 'Unable to close paper position',
        tone: 'error',
      })
    } finally {
      setClosingId(null)
    }
  }

  async function action(bot: Bot, act: 'start' | 'stop') {
    try {
      await api.post(`/api/v1/bots/${bot.id}/${act}`)
      pushToast({
        title: act === 'start' ? `Started ${bot.name}` : `Stopped ${bot.name}`,
        tone: act === 'start' ? 'success' : 'info',
      })
      load()
    } catch (e) {
      pushToast({
        title: `Failed to ${act} bot`,
        detail: e instanceof Error ? e.message : 'Request failed',
        tone: 'error',
      })
    }
  }

  async function kill(bot: Bot, enabled: boolean) {
    try {
      await api.post(`/api/v1/bots/${bot.id}/kill-switch?enabled=${enabled}`)
      pushToast({
        title: enabled ? `Kill switch engaged on ${bot.name}` : `Kill switch released on ${bot.name}`,
        tone: enabled ? 'warn' : 'info',
      })
      load()
    } catch (e) {
      pushToast({
        title: 'Kill-switch request failed',
        detail: e instanceof Error ? e.message : 'Request failed',
        tone: 'error',
      })
    }
  }

  async function stopAll() {
    setStopping(true)
    try {
      const res = await api.post<{ stopped: number }>('/api/v1/bots/stop-all-futures')
      setConfirmStop(false)
      pushToast({
        title: 'All futures bots stopped',
        detail: `${res.stopped} bot${res.stopped === 1 ? '' : 's'} halted.`,
        tone: 'warn',
      })
      load()
    } catch (e) {
      pushToast({
        title: 'Failed to stop bots',
        detail: e instanceof Error ? e.message : 'Request failed',
        tone: 'error',
      })
    } finally {
      setStopping(false)
    }
  }

  const open = positions.filter((p) => p.status === 'OPEN')
  const closed = positions.filter((p) => p.status !== 'OPEN')

  return (
    <PageShell>
      <div className="mb-6 flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
        <PageTitle
          title="Paper Trading"
          subtitle="Isolated simulated futures trades executed at real CoinDCX live market prices — zero real funds at risk"
        />
        <div className="flex flex-wrap items-center gap-2">
          <Button
            variant={orderOpen ? 'ghost' : 'primary'}
            onClick={() => setOrderOpen((v) => !v)}
            className="w-full sm:w-auto"
          >
            {orderOpen ? 'Close ticket' : '+ New paper trade'}
          </Button>
          {bots && bots.length > 0 && (
            !confirmStop ? (
              <Button variant="danger" onClick={() => setConfirmStop(true)} disabled={stopping} className="w-full sm:w-auto">
                Stop all paper bots
              </Button>
            ) : (
              <div className="flex items-center gap-2 rounded-xl border border-rose-500/40 bg-rose-500/10 px-3 py-1.5">
                <span className="text-xs font-medium text-rose-200">Halt paper bots?</span>
                <Button variant="danger" onClick={stopAll} disabled={stopping} className="px-3 py-1 text-xs">
                  {stopping ? 'Stopping…' : 'Confirm'}
                </Button>
                <Button variant="ghost" onClick={() => setConfirmStop(false)} className="px-3 py-1 text-xs">
                  Cancel
                </Button>
              </div>
            )
          )}
        </div>
      </div>

      <Callout tone="info">
        Paper trades stay completely separate from{' '}
        <Link to="/" className="font-semibold underline underline-offset-2 hover:text-cyan-100">
          Real Trades (Live)
        </Link>{' '}
        and work without a CoinDCX API key.
      </Callout>

      {orderOpen && (
        <Card hover={false} className="mb-6">
          <h2 className="mb-4 font-[family-name:var(--font-display)] text-base font-semibold text-slate-100">
            Execute Paper Trade (Live CoinDCX Price)
          </h2>
          <form onSubmit={placePaperOrder} className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
            <div>
              <Label>Instrument</Label>
              <select
                value={pair}
                onChange={(e) => setPair(e.target.value)}
                className="w-full rounded-xl border border-edge bg-surface px-3.5 py-2.5 text-sm text-slate-200 outline-none focus:border-amber-500/60"
              >
                {PAPER_PAIRS.map((p) => (
                  <option key={p} value={p}>
                    {p}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <Label>Side</Label>
              <select
                value={side}
                onChange={(e) => setSide(e.target.value === 'SHORT' ? 'SHORT' : 'LONG')}
                className="w-full rounded-xl border border-edge bg-surface px-3.5 py-2.5 text-sm text-slate-200 outline-none focus:border-amber-500/60"
              >
                <option value="LONG">LONG</option>
                <option value="SHORT">SHORT</option>
              </select>
            </div>
            <div>
              <Label>Quantity</Label>
              <Input
                type="number"
                step="any"
                min="0.0001"
                value={quantity}
                onChange={(e) => setQuantity(e.target.value)}
                placeholder="0.01"
              />
            </div>
            <div>
              <Label>Leverage</Label>
              <Input
                type="number"
                min="1"
                max="20"
                value={leverage}
                onChange={(e) => setLeverage(e.target.value)}
                placeholder="3"
              />
            </div>
            <div className="flex items-end">
              <Button type="submit" disabled={placing} className="w-full">
                {placing ? 'Executing…' : `Open Paper ${side}`}
              </Button>
            </div>
          </form>
        </Card>
      )}

      <div className="mb-6 grid grid-cols-2 gap-3 sm:gap-4 lg:grid-cols-4">
        <Stat label="Open Paper" value={String(summary?.openPositions ?? open.length)} sub="Active simulations" delay={0.02} />
        <Stat label="Closed Paper" value={String(summary?.closedPositions ?? closed.length)} sub="Settled paper cycles" delay={0.04} />
        <Stat
          label="Paper Unrealized"
          value={fmtInr(summary?.unrealizedPnl)}
          accent={(summary?.unrealizedPnl ?? 0) > 0 ? 'up' : (summary?.unrealizedPnl ?? 0) < 0 ? 'down' : 'neutral'}
          delay={0.06}
        />
        <Stat
          label="Paper Realized"
          value={fmtInr(summary?.realizedPnl)}
          sub={summary ? `${(summary.winRate * 100).toFixed(1)}% Win Rate` : undefined}
          accent={(summary?.realizedPnl ?? 0) > 0 ? 'up' : (summary?.realizedPnl ?? 0) < 0 ? 'down' : 'neutral'}
          delay={0.08}
        />
      </div>

      {bots === null ? (
        <Spinner />
      ) : bots.length === 0 ? (
        <Card hover={false}>
          <Empty message="No automated paper bots running yet. Use '+ New paper trade' above or generate a strategy in Market & Engine → Strategies." />
        </Card>
      ) : (
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {bots.map((b, i) => {
            const running = b.status === 'RUNNING'
            return (
              <Card key={b.id} delay={i * 0.03} hover={false}>
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <div className="truncate font-medium text-slate-100">{b.name}</div>
                    <div className="mt-1 font-mono-num text-xs text-slate-400">
                      PAPER · FUTURES · {b.leverage ?? 1}x · Stake ₹{b.stakeAmount}
                    </div>
                  </div>
                  <div className="flex shrink-0 items-center gap-2">
                    {running && (
                      <motion.span
                        animate={{ opacity: [1, 0.35, 1] }}
                        transition={{ repeat: Infinity, duration: 1.8 }}
                        className="h-2 w-2 rounded-full bg-emerald-400"
                        title="Bot running"
                      />
                    )}
                    <Badge tone={running ? 'success' : b.killSwitch ? 'danger' : 'default'}>
                      {b.killSwitch ? 'KILL' : b.status}
                    </Badge>
                  </div>
                </div>
                <div className="mt-3 font-mono-num text-xs text-slate-500">
                  Open positions: {open.filter((p) => p.botId === b.id).length} / {b.maxOpenTrades}
                </div>
                <div className="mt-4 flex flex-wrap gap-2 border-t border-edge/70 pt-3">
                  {running ? (
                    <Button variant="ghost" onClick={() => action(b, 'stop')} className="px-3 py-1.5 text-xs">
                      Stop
                    </Button>
                  ) : (
                    <Button variant="subtle" onClick={() => action(b, 'start')} disabled={b.killSwitch} className="px-3 py-1.5 text-xs">
                      Resume
                    </Button>
                  )}
                  <Button variant="danger" onClick={() => kill(b, !b.killSwitch)} className="px-3 py-1.5 text-xs">
                    {b.killSwitch ? 'Release kill' : 'Kill switch'}
                  </Button>
                </div>
              </Card>
            )
          })}
        </div>
      )}

      <PositionTable
        title={`Open Paper Positions (${open.length})`}
        rows={open}
        empty="No open paper positions."
        closingId={closingId}
        onClose={closePosition}
        onRow={(p) =>
          navigate(`/futures/chart/${encodeURIComponent(p.pair)}?mode=paper&positionId=${p.id}&timeframe=5m`)
        }
      />
      <PositionTable
        title={`Closed Paper Positions (${closed.length})`}
        rows={closed.slice(0, 50)}
        empty="No closed paper positions yet."
        onRow={(p) =>
          navigate(`/futures/chart/${encodeURIComponent(p.pair)}?mode=paper&positionId=${p.id}&timeframe=5m`)
        }
      />

      <Card className="mt-6" delay={0.12} hover={false}>
        <h2 className="mb-4 font-[family-name:var(--font-display)] text-lg font-semibold text-slate-100">
          Paper Orders ({orders.length})
        </h2>
        {orders.length === 0 ? (
          <Empty message="No paper orders yet." />
        ) : (
          <>
            <div className="space-y-2 md:hidden">
              {orders.slice(0, 40).map((o) => (
                <OrderCard
                  key={o.id}
                  pair={o.pair}
                  side={o.side}
                  status={o.status}
                  quantity={o.quantity}
                  createdAt={o.createdAt}
                  tone={o.status === 'FILLED' ? 'success' : 'warn'}
                  detail={[
                    o.sizeInr != null ? `Size ${fmtInr(o.sizeInr, false)}` : null,
                    o.avgPrice != null ? `Fill ${o.avgPrice}` : null,
                  ]
                    .filter(Boolean)
                    .join(' · ')}
                  onClick={() => navigate(`/futures/chart/${encodeURIComponent(o.pair)}?mode=paper&timeframe=5m`)}
                />
              ))}
            </div>
            <div className="hidden overflow-x-auto md:block">
              <table className="w-full min-w-[640px] text-left text-sm">
                <thead>
                  <tr className="border-b border-edge text-[11px] uppercase tracking-wider text-slate-500">
                    <th className="pb-2.5 pr-4">Instrument</th>
                    <th className="pb-2.5 pr-4">Side</th>
                    <th className="pb-2.5 pr-4">Status</th>
                    <th className="pb-2.5 pr-4">Fill Price</th>
                    <th className="pb-2.5 pr-4">Size (INR)</th>
                    <th className="pb-2.5 pr-4">Qty</th>
                    <th className="pb-2.5">Order Time</th>
                  </tr>
                </thead>
                <tbody>
                  {orders.slice(0, 40).map((o) => (
                    <tr
                      key={o.id}
                      onClick={() => navigate(`/futures/chart/${encodeURIComponent(o.pair)}?mode=paper&timeframe=5m`)}
                      className="data-row cursor-pointer border-b border-edge/50 text-slate-300"
                    >
                      <td className="py-2.5 pr-4 font-medium text-slate-100">{o.pair}</td>
                      <td className={`py-2.5 pr-4 font-mono-num text-xs font-semibold ${o.side === 'BUY' ? 'text-emerald-400' : 'text-rose-400'}`}>
                        {o.side}
                      </td>
                      <td className="py-2.5 pr-4">
                        <Badge tone={o.status === 'FILLED' ? 'success' : 'warn'}>{o.status}</Badge>
                      </td>
                      <td className="py-2.5 pr-4 font-mono-num">{o.avgPrice ?? o.price}</td>
                      <td className="py-2.5 pr-4 font-mono-num">{o.sizeInr != null ? fmtInr(o.sizeInr, false) : '—'}</td>
                      <td className="py-2.5 pr-4 font-mono-num text-slate-400">{o.quantity}</td>
                      <td className="whitespace-nowrap py-2.5 font-mono-num text-xs text-slate-400">
                        {formatDateTime(o.createdAt)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </Card>
    </PageShell>
  )
}

function PositionTable({
  title,
  rows,
  empty,
  closingId,
  onClose,
  onRow,
}: {
  title: string
  rows: Position[]
  empty: string
  closingId?: string | null
  onClose?: (p: Position) => void
  onRow: (p: Position) => void
}) {
  return (
    <Card className="mt-6" delay={0.08} hover={false}>
      <h2 className="mb-4 font-[family-name:var(--font-display)] text-lg font-semibold text-slate-100">
        {title}
      </h2>
      {rows.length === 0 ? (
        <Empty message={empty} />
      ) : (
        <>
          <div className="space-y-2 md:hidden">
            {rows.map((p) => (
              <div key={p.id} className="space-y-2">
                <PositionCard
                  pair={p.pair}
                  side={p.side}
                  status={p.status}
                  quantity={p.quantity}
                  entryPrice={p.entryPrice}
                  exitPrice={p.exitPrice}
                  pnl={p.status === 'OPEN' ? (p.pnl ?? p.unrealizedPnl ?? null) : p.realizedPnl}
                  openedAt={p.openedAt}
                  closedAt={p.closedAt}
                  onClick={() => onRow(p)}
                />
                {p.status === 'OPEN' && onClose && (
                  <Button
                    variant="subtle"
                    onClick={() => onClose(p)}
                    disabled={closingId === p.id}
                    className="w-full py-1.5 text-xs"
                  >
                    {closingId === p.id ? 'Closing…' : 'Close paper position'}
                  </Button>
                )}
              </div>
            ))}
          </div>
          <div className="hidden overflow-x-auto md:block">
            <table className="w-full min-w-[780px] text-left text-sm">
              <thead>
                <tr className="border-b border-edge text-[11px] uppercase tracking-wider text-slate-500">
                  <th className="pb-2.5 pr-4">Instrument</th>
                  <th className="pb-2.5 pr-4">Side</th>
                  <th className="pb-2.5 pr-4">Qty</th>
                  <th className="pb-2.5 pr-4">Entry</th>
                  <th className="pb-2.5 pr-4">Mark / Exit</th>
                  <th className="pb-2.5 pr-4">Status</th>
                  <th className="pb-2.5 pr-4">PnL</th>
                  <th className="pb-2.5 pr-4">Opened</th>
                  <th className="pb-2.5">Action / Closed</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((p) => {
                  const rowPnl = p.status === 'OPEN' ? (p.pnl ?? p.unrealizedPnl ?? null) : p.realizedPnl
                  const secondPrice = p.status === 'OPEN' ? p.markPrice : p.exitPrice
                  return (
                    <tr
                      key={p.id}
                      className="data-row cursor-pointer border-b border-edge/50 text-slate-300"
                      onClick={() => onRow(p)}
                    >
                      <td className="py-2.5 pr-4 font-medium text-slate-100">{p.pair}</td>
                      <td className="py-2.5 pr-4">
                        <Badge tone={p.side === 'LONG' ? 'success' : 'danger'}>{p.side}</Badge>
                      </td>
                      <td className="py-2.5 pr-4 font-mono-num">{p.quantity}</td>
                      <td className="py-2.5 pr-4 font-mono-num">{Number(p.entryPrice)}</td>
                      <td className="py-2.5 pr-4 font-mono-num">
                        {secondPrice != null ? Number(secondPrice) : '—'}
                      </td>
                      <td className="py-2.5 pr-4">
                        <Badge tone={p.status === 'OPEN' ? 'info' : 'default'}>{p.status}</Badge>
                      </td>
                      <td className={`py-2.5 pr-4 font-mono-num ${(rowPnl ?? 0) >= 0 ? 'text-emerald-400' : 'text-rose-400'}`}>
                        {rowPnl != null ? fmtInr(rowPnl) : '—'}
                      </td>
                      <td className="whitespace-nowrap py-2.5 pr-4 font-mono-num text-xs text-slate-400">
                        {formatDateTime(p.openedAt)}
                      </td>
                      <td className="whitespace-nowrap py-2.5 font-mono-num text-xs text-slate-400" onClick={(e) => p.status === 'OPEN' && onClose ? e.stopPropagation() : undefined}>
                        {p.status === 'OPEN' && onClose ? (
                          <Button
                            variant="subtle"
                            onClick={() => onClose(p)}
                            disabled={closingId === p.id}
                            className="px-2.5 py-1 text-xs"
                          >
                            {closingId === p.id ? 'Closing…' : 'Close'}
                          </Button>
                        ) : (
                          formatDateTime(p.closedAt)
                        )}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        </>
      )}
    </Card>
  )
}

function fmtInr(v?: number | null, signed = true) {
  if (v == null) return '—'
  if (!signed) return `₹${Math.abs(v).toLocaleString(undefined, { maximumFractionDigits: 2 })}`
  const sign = v >= 0 ? '+' : '−'
  return `${sign}₹${Math.abs(v).toLocaleString(undefined, { maximumFractionDigits: 2 })}`
}
