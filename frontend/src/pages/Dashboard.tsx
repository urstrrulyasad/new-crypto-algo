import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { motion } from 'motion/react'
import { api } from '@/lib/api'
import { formatDateTime } from '@/lib/datetime'
import { OrderCard, PositionCard } from '@/components/TradeLedger'
import { Badge, Button, Callout, Card, Empty, PageShell, PageTitle, Spinner, Stat } from '@/components/ui'

interface Summary {
  mode?: string
  openPositions: number
  closedPositions: number
  realizedPnl: number
  unrealizedPnl: number
  winRate: number
}

interface Wallet {
  currency: string
  available: number
  source: string
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
  unrealizedPnl?: number | null
  pnl?: number | null
  markPrice?: number | null
  slPrice?: number | null
  targetPrice?: number | null
  marginCurrency?: string
  openedAt: string
  closedAt?: string | null
}

interface Order {
  id: string
  pair: string
  side: string
  status: string
  price: number
  quantity: number
  mode: string
  error?: string | null
  createdAt: string
  pnl?: number | null
  markPrice?: number | null
}

type OrderFilter = 'all' | 'pending' | 'success' | 'failed'

/** LIVE cockpit only — CoinDCX INR wallet + LIVE bot money. No paper. No instrument list. */
export default function Dashboard() {
  const navigate = useNavigate()
  const [summary, setSummary] = useState<Summary | null>(null)
  const [wallet, setWallet] = useState<Wallet | null>(null)
  const [walletErr, setWalletErr] = useState('')
  const [positions, setPositions] = useState<Position[] | null>(null)
  const [orders, setOrders] = useState<Order[] | null>(null)
  const [stopping, setStopping] = useState(false)
  const [orderFilter, setOrderFilter] = useState<OrderFilter>('all')

  const load = () => {
    api.get<Summary>('/api/v1/portfolio/summary?mode=LIVE').then(setSummary).catch(() => setSummary(null))
    api
      .get<Wallet>('/api/v1/portfolio/wallet')
      .then((w) => {
        setWallet(w)
        setWalletErr('')
      })
      .catch((e) => {
        setWallet(null)
        setWalletErr(e instanceof Error ? e.message : 'Wallet unavailable')
      })
    api
      .get<Position[]>('/api/v1/portfolio/positions?mode=LIVE')
      .then(setPositions)
      .catch(() => setPositions([]))
    api
      .get<Order[]>('/api/v1/portfolio/orders?mode=LIVE')
      .then(setOrders)
      .catch(() => setOrders([]))
  }

  useEffect(() => {
    load()
    const poll = setInterval(load, 12_000)
    return () => clearInterval(poll)
  }, [])

  async function stopAll() {
    if (!confirm('Stop all running futures bots for this tenant?')) return
    setStopping(true)
    try {
      await api.post('/api/v1/bots/stop-all-futures')
      load()
    } finally {
      setStopping(false)
    }
  }

  const pendingOrders = (orders ?? []).filter(isPendingOrder)
  const successOrders = (orders ?? []).filter(isSuccessOrder)
  const failedOrders = (orders ?? []).filter(isFailedOrder)
  const filteredOrders =
    orderFilter === 'all'
      ? (orders ?? [])
      : orderFilter === 'pending'
        ? pendingOrders
        : orderFilter === 'success'
          ? successOrders
          : failedOrders

  return (
    <PageShell>
      <div className="mb-6 flex flex-col gap-4 sm:flex-row sm:flex-wrap sm:items-start sm:justify-between">
        <PageTitle
          title="Dashboard"
          subtitle="LIVE only — CoinDCX INR futures wallet + live bot PnL and trades"
        />
        <Button variant="danger" onClick={stopAll} disabled={stopping} className="w-full sm:w-auto">
          {stopping ? 'Stopping…' : 'Stop all futures'}
        </Button>
      </div>

      <Callout tone="warn">
        Paper trading lives on Strategies / Paper Trade. This page never shows paper as account money.
      </Callout>

      <div className="grid grid-cols-2 gap-3 sm:gap-4 md:grid-cols-3 xl:grid-cols-5">
        <Stat
          label="CoinDCX INR"
          value={wallet ? fmtInr(wallet.available, false) : walletErr ? '—' : '…'}
          delay={0}
        />
        <Stat label="LIVE realized" value={fmtInr(summary?.realizedPnl)} accent={pnlAccent(summary?.realizedPnl)} delay={0.05} />
        <Stat label="LIVE unrealized" value={fmtInr(summary?.unrealizedPnl)} accent={pnlAccent(summary?.unrealizedPnl)} delay={0.1} />
        <Stat label="LIVE open" value={summary?.openPositions ?? '—'} delay={0.15} />
        <Stat label="LIVE win rate" value={summary ? `${(summary.winRate * 100).toFixed(1)}%` : '—'} delay={0.2} />
      </div>
      {walletErr && <p className="mt-2 text-xs text-rose-400">Wallet: {walletErr}</p>}

      <div className="mt-6 grid gap-6 xl:grid-cols-2">
        <Card delay={0.1}>
          <h2 className="mb-4 font-[family-name:var(--font-display)] text-lg font-semibold text-slate-100">
            LIVE positions
          </h2>
          {positions === null ? (
            <Spinner />
          ) : positions.length === 0 ? (
            <Empty message="No LIVE positions yet." />
          ) : (
            <>
              <div className="space-y-2 md:hidden">
                {positions.slice(0, 30).map((p) => (
                  <PositionCard
                    key={p.id}
                    pair={p.pair}
                    side={p.side}
                    status={p.status}
                    quantity={p.quantity}
                    entryPrice={p.entryPrice}
                    exitPrice={p.exitPrice}
                    pnl={p.status === 'CLOSED' ? p.realizedPnl : (p.pnl ?? p.unrealizedPnl ?? null)}
                    openedAt={p.openedAt}
                    closedAt={p.closedAt}
                    onClick={() =>
                      navigate(
                        `/futures/chart/${encodeURIComponent(p.pair)}?mode=live&positionId=${p.id}&timeframe=5m`,
                      )
                    }
                  />
                ))}
              </div>
              <div className="hidden overflow-x-auto md:block">
                <table className="w-full min-w-[720px] text-left text-sm">
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
                    {positions.slice(0, 30).map((p, i) => (
                      <motion.tr
                        key={p.id}
                        initial={{ opacity: 0, x: -6 }}
                        animate={{ opacity: 1, x: 0 }}
                        transition={{ delay: Math.min(i * 0.03, 0.35) }}
                        className="data-row cursor-pointer border-b border-edge/40 text-slate-300"
                        onClick={() =>
                          navigate(
                            `/futures/chart/${encodeURIComponent(p.pair)}?mode=live&positionId=${p.id}&timeframe=5m`,
                          )
                        }
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
                        <td
                          className={`py-2.5 pr-4 ${
                            Number(p.status === 'CLOSED' ? p.realizedPnl : (p.pnl ?? p.unrealizedPnl) ?? 0) >= 0
                              ? 'text-emerald-400'
                              : 'text-rose-400'
                          }`}
                        >
                          {p.status === 'CLOSED'
                            ? fmtInr(p.realizedPnl)
                            : p.pnl != null || p.unrealizedPnl != null
                              ? fmtInr(p.pnl ?? p.unrealizedPnl)
                              : '—'}
                        </td>
                        <td className="whitespace-nowrap py-2.5 pr-4 text-xs text-slate-500">
                          {formatDateTime(p.openedAt)}
                        </td>
                        <td className="whitespace-nowrap py-2.5 text-xs text-slate-500">
                          {formatDateTime(p.closedAt)}
                        </td>
                      </motion.tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </Card>

        <Card delay={0.15}>
          <div className="mb-4 flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-center sm:justify-between">
            <h2 className="font-[family-name:var(--font-display)] text-lg font-semibold text-slate-100">
              LIVE orders
            </h2>
            <div className="flex flex-wrap gap-2">
              {(
                [
                  ['all', (orders ?? []).length, 'All'],
                  ['pending', pendingOrders.length, 'Pending'],
                  ['success', successOrders.length, 'Success'],
                  ['failed', failedOrders.length, 'Failed'],
                ] as const
              ).map(([key, count, label]) => (
                <button
                  key={key}
                  type="button"
                  onClick={() => setOrderFilter(key)}
                  className={`rounded-full px-3 py-1 text-xs font-medium transition ${
                    orderFilter === key
                      ? 'bg-cyan-500/20 text-cyan-200 ring-1 ring-cyan-500/40'
                      : 'bg-surface/60 text-slate-400 hover:text-slate-200'
                  }`}
                >
                  {label} · {orders === null ? '—' : count}
                </button>
              ))}
            </div>
          </div>
          {orders === null ? (
            <Spinner />
          ) : filteredOrders.length === 0 ? (
            <Empty message={`No ${orderFilter} LIVE orders.`} />
          ) : (
            <>
              <div className="space-y-2 md:hidden">
                {filteredOrders.slice(0, 40).map((o) => (
                  <OrderCard
                    key={o.id}
                    pair={o.pair}
                    side={o.side}
                    status={o.status}
                    quantity={o.quantity}
                    createdAt={o.createdAt}
                    tone={isFailedOrder(o) ? 'danger' : isSuccessOrder(o) ? 'success' : 'warn'}
                    detail={
                      o.error ||
                      (o.pnl != null
                        ? `PnL ${fmtInr(o.pnl)} · ₹${Number(o.price).toLocaleString()}`
                        : `₹${Number(o.price).toLocaleString()}`)
                    }
                    onClick={() =>
                      navigate(`/futures/chart/${encodeURIComponent(o.pair)}?mode=live&timeframe=5m`)
                    }
                  />
                ))}
              </div>
              <div className="hidden overflow-x-auto md:block">
                <table className="w-full min-w-[760px] text-left text-sm">
                  <thead>
                    <tr className="border-b border-edge text-xs uppercase tracking-wider text-slate-500">
                      <th className="pb-2 pr-4">Instrument</th>
                      <th className="pb-2 pr-4">Side</th>
                      <th className="pb-2 pr-4">Status</th>
                      <th className="pb-2 pr-4">Qty</th>
                      <th className="pb-2 pr-4">PnL</th>
                      <th className="pb-2 pr-4">Detail</th>
                      <th className="pb-2">Order time</th>
                    </tr>
                  </thead>
                  <tbody>
                    {filteredOrders.slice(0, 40).map((o, i) => (
                      <motion.tr
                        key={o.id}
                        initial={{ opacity: 0, x: -6 }}
                        animate={{ opacity: 1, x: 0 }}
                        transition={{ delay: Math.min(i * 0.03, 0.35) }}
                        className="data-row cursor-pointer border-b border-edge/40 text-slate-300"
                        onClick={() =>
                          navigate(`/futures/chart/${encodeURIComponent(o.pair)}?mode=live&timeframe=5m`)
                        }
                      >
                        <td className="py-2.5 pr-4 font-medium text-slate-200">{o.pair}</td>
                        <td className="py-2.5 pr-4">{o.side}</td>
                        <td className="py-2.5 pr-4">
                          <Badge
                            tone={
                              isFailedOrder(o) ? 'danger' : isSuccessOrder(o) ? 'success' : 'warn'
                            }
                          >
                            {o.status}
                          </Badge>
                        </td>
                        <td className="py-2.5 pr-4">{o.quantity}</td>
                        <td
                          className={`py-2.5 pr-4 ${
                            o.pnl == null ? 'text-slate-500' : o.pnl >= 0 ? 'text-emerald-400' : 'text-rose-400'
                          }`}
                        >
                          {o.pnl != null ? fmtInr(o.pnl) : '—'}
                        </td>
                        <td
                          className="max-w-[240px] truncate py-2.5 pr-4 text-xs text-slate-500"
                          title={o.error ?? ''}
                        >
                          {o.error || `₹${Number(o.price).toLocaleString()}`}
                        </td>
                        <td className="whitespace-nowrap py-2.5 text-xs text-slate-300">
                          {formatDateTime(o.createdAt)}
                        </td>
                      </motion.tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </Card>
      </div>
    </PageShell>
  )
}

function isFailedOrder(o: Order) {
  const s = (o.status || '').toUpperCase()
  return s === 'FAILED' || s === 'REJECTED' || s === 'CANCELLED'
}

function isSuccessOrder(o: Order) {
  return (o.status || '').toUpperCase() === 'FILLED'
}

function isPendingOrder(o: Order) {
  return !isFailedOrder(o) && !isSuccessOrder(o)
}

function fmtInr(v?: number | null, signed = true) {
  if (v == null) return '—'
  if (!signed) return `₹${Math.abs(v).toLocaleString(undefined, { maximumFractionDigits: 2 })}`
  const sign = v >= 0 ? '+' : '−'
  return `${sign}₹${Math.abs(v).toLocaleString(undefined, { maximumFractionDigits: 2 })}`
}

function pnlAccent(v?: number | null): 'up' | 'down' | 'neutral' {
  if (v == null || v === 0) return 'neutral'
  return v > 0 ? 'up' : 'down'
}
