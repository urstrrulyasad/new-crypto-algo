import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { motion } from 'motion/react'
import { api } from '@/lib/api'
import { formatDateTime } from '@/lib/datetime'
import { useToast } from '@/lib/toast'
import { EquityCurve } from '@/components/EquityCurve'
import { IconKey } from '@/components/Icons'
import { TickerStrip } from '@/components/TickerStrip'
import { OrderCard, PositionCard } from '@/components/TradeLedger'
import { Badge, Button, Callout, Card, Empty, PageShell, PageTitle, Spinner, Stat } from '@/components/ui'

interface ExchangeKey {
  id: string
  exchange: string
  label: string
  keyLast4: string
  status: string
}

interface Summary {
  mode?: string
  hasApiKey?: boolean
  openPositions: number
  closedPositions: number
  realizedPnl: number
  unrealizedPnl: number
  winRate: number
}

interface Wallet {
  currency: string
  available: number
  locked?: number
  walletBalance?: number
  walletEquity?: number
  currentValue?: number
  activePnl?: number
  usdtAvailable?: number
  usdtValueInr?: number
  estTotalFutures?: number
  futuresWalletBalance?: number
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
  marginInr?: number | null
  sizeInr?: number | null
  roePct?: number | null
  leverage?: number | null
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
  avgPrice?: number | null
  sizeInr?: number | null
  mode: string
  error?: string | null
  createdAt: string
  pnl?: number | null
  markPrice?: number | null
}

type OrderFilter = 'all' | 'pending' | 'success' | 'failed'

export default function Dashboard() {
  const navigate = useNavigate()
  const { pushToast } = useToast()
  const [keys, setKeys] = useState<ExchangeKey[] | null>(null)
  const [summary, setSummary] = useState<Summary | null>(null)
  const [wallet, setWallet] = useState<Wallet | null>(null)
  const [walletErr, setWalletErr] = useState('')
  const [positions, setPositions] = useState<Position[] | null>(null)
  const [orders, setOrders] = useState<Order[] | null>(null)
  const [stopping, setStopping] = useState(false)
  const [confirmStop, setConfirmStop] = useState(false)
  const [orderFilter, setOrderFilter] = useState<OrderFilter>('all')

  const hasActiveKey = keys !== null && keys.some((k) => k.status === 'ACTIVE')

  const load = useCallback(async () => {
    try {
      const keyList = await api.get<ExchangeKey[]>('/api/v1/keys')
      setKeys(keyList)
      const active = keyList.some((k) => k.status === 'ACTIVE')
      if (!active) {
        setSummary(null)
        setWallet(null)
        setWalletErr('')
        setPositions([])
        setOrders([])
        return
      }
    } catch {
      setKeys([])
      setSummary(null)
      setWallet(null)
      setPositions([])
      setOrders([])
      return
    }

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
  }, [])

  const openLive = (positions ?? []).filter((p) => p.status === 'OPEN').length

  useEffect(() => {
    void load()
    const ms = openLive > 0 ? 3_000 : 12_000
    const poll = setInterval(() => void load(), ms)
    return () => clearInterval(poll)
  }, [load, openLive])

  async function stopAll() {
    setStopping(true)
    try {
      const res = await api.post<{ stopped: number }>('/api/v1/bots/stop-all-futures')
      setConfirmStop(false)
      pushToast({
        title: 'Emergency stop executed',
        detail: `${res.stopped} futures bot${res.stopped === 1 ? '' : 's'} halted & kill-switch engaged.`,
        tone: 'warn',
      })
      void load()
    } catch (e) {
      pushToast({
        title: 'Stop-all failed',
        detail: e instanceof Error ? e.message : 'Unable to stop futures bots',
        tone: 'error',
      })
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

  const walletBalance = wallet
    ? (wallet.walletBalance ?? wallet.walletEquity ?? wallet.available + (wallet.locked ?? 0))
    : null
  const activePnl = wallet?.activePnl ?? summary?.unrealizedPnl
  const currentValue =
    wallet?.currentValue ?? (walletBalance != null ? walletBalance + (activePnl ?? 0) : null)
  const estTotal =
    wallet?.estTotalFutures ??
    (currentValue != null ? currentValue + (wallet?.usdtValueInr ?? 0) : null)

  const equitySeries = useMemo(() => {
    const sorted = [...(positions ?? [])].sort(
      (a, b) => new Date(a.openedAt).getTime() - new Date(b.openedAt).getTime(),
    )
    if (sorted.length === 0) return []
    const pts: number[] = [0]
    let cum = 0
    for (const p of sorted) {
      const delta = p.status === 'CLOSED' ? Number(p.realizedPnl ?? 0) : Number(p.pnl ?? p.unrealizedPnl ?? 0)
      cum += delta
      pts.push(Number(cum.toFixed(2)))
    }
    return pts
  }, [positions])

  return (
    <PageShell>
      <div className="mb-6 flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
        <PageTitle
          title="Real Trades (LIVE)"
          subtitle="Live CoinDCX futures wallet, verified positions, and exchange execution ledger"
        />
        {hasActiveKey && (
          <div className="flex flex-wrap items-center gap-2">
            {!confirmStop ? (
              <Button
                variant="danger"
                onClick={() => setConfirmStop(true)}
                disabled={stopping}
                className="w-full sm:w-auto"
              >
                Stop all futures
              </Button>
            ) : (
              <div className="flex items-center gap-2 rounded-xl border border-rose-500/40 bg-rose-500/10 px-3 py-1.5">
                <span className="text-xs font-medium text-rose-200">Halt all bots?</span>
                <Button variant="danger" onClick={stopAll} disabled={stopping} className="px-3 py-1 text-xs">
                  {stopping ? 'Stopping…' : 'Confirm'}
                </Button>
                <Button variant="ghost" onClick={() => setConfirmStop(false)} className="px-3 py-1 text-xs">
                  Cancel
                </Button>
              </div>
            )}
          </div>
        )}
      </div>

      <TickerStrip />

      <Callout tone="warn">
        This section shows real CoinDCX LIVE data only. Simulated trading is isolated under{' '}
        <Link to="/futures/paper" className="font-semibold underline underline-offset-2 hover:text-amber-100">
          Paper Trading
        </Link>
        .
      </Callout>

      {keys === null ? (
        <Card hover={false} className="p-8">
          <Spinner />
        </Card>
      ) : !hasActiveKey ? (
        <Card hover={false} className="border-amber-500/30 p-6 sm:p-8">
          <div className="flex flex-col gap-5 sm:flex-row sm:items-center sm:justify-between">
            <div className="max-w-2xl">
              <div className="inline-flex items-center gap-2 rounded-lg border border-amber-500/30 bg-amber-500/10 px-2.5 py-1 text-xs font-medium text-amber-200">
                <IconKey />
                CoinDCX API Key Required for Real Trades
              </div>
              <h2 className="mt-3 font-[family-name:var(--font-display)] text-xl font-bold text-slate-100">
                To get started, add your CoinDCX API key and secret
              </h2>
              <p className="mt-2 text-sm leading-relaxed text-slate-400">
                No active CoinDCX API credentials are configured on your account, so no LIVE positions, balances, or exchange orders are displayed. Connect your CoinDCX Futures API key and secret to view your real portfolio and execute live trades, or practice risk-free in the separate Paper Trading section.
              </p>
            </div>
            <div className="flex shrink-0 flex-col gap-2.5 sm:items-end">
              <Link
                to="/settings"
                className="inline-flex items-center justify-center gap-2 rounded-xl bg-amber-400 px-5 py-2.5 text-sm font-semibold text-zinc-950 shadow-sm shadow-amber-500/20 transition hover:bg-amber-300"
              >
                <IconKey />
                Add CoinDCX API Key &amp; Secret
              </Link>
              <Link
                to="/futures/paper"
                className="inline-flex items-center justify-center rounded-xl border border-edge bg-surface/70 px-4 py-2 text-xs font-medium text-slate-300 transition hover:border-amber-500/40 hover:text-slate-100"
              >
                Open Paper Trading →
              </Link>
            </div>
          </div>
        </Card>
      ) : (
        <>
          <Card hover={false} className="mb-5 p-5">
            <div className="flex flex-col gap-5 lg:flex-row lg:items-center lg:justify-between">
              <div className="min-w-0 flex-1">
                <div className="text-[11px] font-medium uppercase tracking-[0.14em] text-slate-400">
                  Estimated Futures Equity
                </div>
                <div className="mt-1.5 flex flex-wrap items-baseline gap-3">
                  <span className="font-mono-num text-3xl font-semibold tracking-tight text-slate-100 sm:text-4xl">
                    {estTotal != null ? fmtInr(estTotal, false) : walletErr ? '—' : '…'}
                  </span>
                  {activePnl != null && (
                    <span
                      className={`font-mono-num text-sm font-medium ${
                        activePnl >= 0 ? 'text-emerald-400' : 'text-rose-400'
                      }`}
                    >
                      {fmtInr(activePnl)} unrealized
                    </span>
                  )}
                </div>
                {wallet?.source && (
                  <div className="mt-1.5 text-xs text-slate-500">{wallet.source}</div>
                )}
              </div>

              {equitySeries.length >= 2 && (
                <div className="w-full lg:w-52">
                  <div className="mb-1 flex items-center justify-between font-mono-num text-[10px] uppercase tracking-wider text-slate-500">
                    <span>Session PnL Curve</span>
                    <span className={equitySeries[equitySeries.length - 1] >= 0 ? 'text-emerald-400' : 'text-rose-400'}>
                      {fmtInr(equitySeries[equitySeries.length - 1])}
                    </span>
                  </div>
                  <EquityCurve points={equitySeries} height={56} />
                </div>
              )}

              <div className="grid grid-cols-2 gap-4 border-t border-edge pt-4 sm:grid-cols-4 lg:border-l lg:border-t-0 lg:pl-6 lg:pt-0">
                <div>
                  <div className="text-[11px] uppercase tracking-wider text-slate-500">Available</div>
                  <div className="mt-1 font-mono-num text-base font-semibold text-slate-200">
                    {wallet ? fmtInr(wallet.available, false) : '—'}
                  </div>
                </div>
                <div>
                  <div className="text-[11px] uppercase tracking-wider text-slate-500">Locked Margin</div>
                  <div className="mt-1 font-mono-num text-base font-semibold text-slate-200">
                    {wallet?.locked != null ? fmtInr(wallet.locked, false) : '—'}
                  </div>
                </div>
                <div>
                  <div className="text-[11px] uppercase tracking-wider text-slate-500">Wallet Balance</div>
                  <div className="mt-1 font-mono-num text-base font-semibold text-slate-200">
                    {walletBalance != null ? fmtInr(walletBalance, false) : '—'}
                  </div>
                </div>
                <div>
                  <div className="text-[11px] uppercase tracking-wider text-slate-500">Current Value</div>
                  <div className="mt-1 font-mono-num text-base font-semibold text-amber-300">
                    {currentValue != null ? fmtInr(currentValue, false) : '—'}
                  </div>
                </div>
              </div>
            </div>
          </Card>

          <div className="grid grid-cols-2 gap-3 sm:gap-4 lg:grid-cols-4">
            <Stat
              label="Open Positions"
              value={summary ? String(summary.openPositions) : '—'}
              sub="Active LIVE futures"
              delay={0.02}
            />
            <Stat
              label="Closed Trades"
              value={summary ? String(summary.closedPositions) : '—'}
              sub="Completed LIVE cycles"
              delay={0.04}
            />
            <Stat
              label="Realized PnL"
              value={fmtInr(summary?.realizedPnl)}
              accent={pnlAccent(summary?.realizedPnl)}
              delay={0.06}
            />
            <Stat
              label="LIVE Win Rate"
              value={summary ? `${(summary.winRate * 100).toFixed(1)}%` : '—'}
              accent={summary && summary.winRate >= 0.6 ? 'up' : 'neutral'}
              delay={0.08}
            />
          </div>

          {walletErr && (
            <p className="mt-3 text-xs text-rose-400">
              CoinDCX Wallet error: {walletErr}{' '}
              <Link to="/settings" className="underline underline-offset-2 hover:text-rose-300">
                Check API Keys →
              </Link>
            </p>
          )}

          <div className="mt-6 grid gap-6 xl:grid-cols-2">
        <Card delay={0.1} hover={false}>
          <div className="mb-4 flex items-center justify-between gap-2">
            <h2 className="font-[family-name:var(--font-display)] text-lg font-semibold text-slate-100">
              LIVE Positions
            </h2>
            <span className="font-mono-num text-xs text-slate-500">
              {positions ? `${positions.length} total` : ''}
            </span>
          </div>
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
                    <tr className="border-b border-edge text-[11px] uppercase tracking-wider text-slate-500">
                      <th className="pb-2.5 pr-4">Instrument</th>
                      <th className="pb-2.5 pr-4">Side</th>
                      <th className="pb-2.5 pr-4">Margin</th>
                      <th className="pb-2.5 pr-4">Entry</th>
                      <th className="pb-2.5 pr-4">Mark</th>
                      <th className="pb-2.5 pr-4">Status</th>
                      <th className="pb-2.5 pr-4">PnL</th>
                      <th className="pb-2.5 pr-4">ROE</th>
                      <th className="pb-2.5">Time</th>
                    </tr>
                  </thead>
                  <tbody>
                    {positions.slice(0, 30).map((p, i) => {
                      const livePnl =
                        p.status === 'CLOSED' ? p.realizedPnl : (p.pnl ?? p.unrealizedPnl ?? null)
                      return (
                        <motion.tr
                          key={p.id}
                          initial={{ opacity: 0, x: -4 }}
                          animate={{ opacity: 1, x: 0 }}
                          transition={{ delay: Math.min(i * 0.025, 0.25) }}
                          className="data-row cursor-pointer border-b border-edge/50 text-slate-300"
                          onClick={() =>
                            navigate(
                              `/futures/chart/${encodeURIComponent(p.pair)}?mode=live&positionId=${p.id}&timeframe=5m`,
                            )
                          }
                        >
                          <td className="py-2.5 pr-4 font-medium text-slate-100">
                            {p.pair}
                            {p.leverage ? (
                              <span className="ml-1.5 font-mono-num text-xs text-slate-500">
                                {p.leverage}x
                              </span>
                            ) : null}
                          </td>
                          <td className="py-2.5 pr-4">
                            <Badge tone={p.side === 'LONG' ? 'success' : 'danger'}>{p.side}</Badge>
                          </td>
                          <td className="py-2.5 pr-4 font-mono-num">
                            {p.marginInr != null ? fmtInr(p.marginInr, false) : '—'}
                          </td>
                          <td className="py-2.5 pr-4 font-mono-num">{Number(p.entryPrice)}</td>
                          <td className="py-2.5 pr-4 font-mono-num">
                            {p.status === 'OPEN' && p.markPrice != null ? Number(p.markPrice) : '—'}
                          </td>
                          <td className="py-2.5 pr-4">
                            <Badge tone={p.status === 'OPEN' ? 'info' : 'default'}>{p.status}</Badge>
                          </td>
                          <td
                            className={`py-2.5 pr-4 font-mono-num font-medium ${
                              Number(livePnl ?? 0) >= 0 ? 'text-emerald-400' : 'text-rose-400'
                            }`}
                          >
                            {livePnl != null ? fmtInr(livePnl) : '—'}
                          </td>
                          <td
                            className={`py-2.5 pr-4 font-mono-num ${
                              Number(p.roePct ?? 0) >= 0 ? 'text-emerald-400' : 'text-rose-400'
                            }`}
                          >
                            {p.status === 'OPEN' && p.roePct != null
                              ? `${p.roePct >= 0 ? '+' : ''}${Number(p.roePct).toFixed(2)}%`
                              : '—'}
                          </td>
                          <td className="whitespace-nowrap py-2.5 font-mono-num text-xs text-slate-500">
                            {formatDateTime(p.status === 'CLOSED' ? p.closedAt : p.openedAt)}
                          </td>
                        </motion.tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </Card>

        <Card delay={0.14} hover={false}>
          <div className="mb-4 flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-center sm:justify-between">
            <h2 className="font-[family-name:var(--font-display)] text-lg font-semibold text-slate-100">
              LIVE Orders
            </h2>
            <div className="inline-flex flex-wrap gap-1 rounded-xl border border-edge bg-surface/80 p-1">
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
                  className={`relative cursor-pointer rounded-lg px-2.5 py-1 text-xs font-medium transition-colors ${
                    orderFilter === key ? 'text-amber-200' : 'text-slate-400 hover:text-slate-200'
                  }`}
                >
                  {orderFilter === key && (
                    <motion.span
                      layoutId="dashboard-order-filter"
                      className="absolute inset-0 rounded-lg border border-amber-500/35 bg-amber-500/15"
                      transition={{ type: 'spring', stiffness: 420, damping: 32 }}
                    />
                  )}
                  <span className="relative z-10 font-mono-num">
                    {label} {orders === null ? '—' : count}
                  </span>
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
                      [
                        o.sizeInr != null ? `Size ${fmtInr(o.sizeInr, false)}` : null,
                        o.avgPrice != null ? `Avg ${o.avgPrice}` : null,
                        o.pnl != null ? `PnL ${fmtInr(o.pnl)}` : null,
                      ]
                        .filter(Boolean)
                        .join(' · ')
                    }
                    onClick={() =>
                      navigate(`/futures/chart/${encodeURIComponent(o.pair)}?mode=live&timeframe=5m`)
                    }
                  />
                ))}
              </div>
              <div className="hidden overflow-x-auto md:block">
                <table className="w-full min-w-[660px] text-left text-sm">
                  <thead>
                    <tr className="border-b border-edge text-[11px] uppercase tracking-wider text-slate-500">
                      <th className="pb-2.5 pr-4">Instrument</th>
                      <th className="pb-2.5 pr-4">Side</th>
                      <th className="pb-2.5 pr-4">Status</th>
                      <th className="pb-2.5 pr-4">Avg Fill</th>
                      <th className="pb-2.5 pr-4">Size (INR)</th>
                      <th className="pb-2.5 pr-4">Qty</th>
                      <th className="pb-2.5">Order Time</th>
                    </tr>
                  </thead>
                  <tbody>
                    {filteredOrders.slice(0, 40).map((o, i) => (
                      <motion.tr
                        key={o.id}
                        initial={{ opacity: 0, x: -4 }}
                        animate={{ opacity: 1, x: 0 }}
                        transition={{ delay: Math.min(i * 0.025, 0.25) }}
                        className="data-row cursor-pointer border-b border-edge/50 text-slate-300"
                        onClick={() =>
                          navigate(`/futures/chart/${encodeURIComponent(o.pair)}?mode=live&timeframe=5m`)
                        }
                      >
                        <td className="py-2.5 pr-4 font-medium text-slate-100">{o.pair}</td>
                        <td
                          className={`py-2.5 pr-4 font-mono-num text-xs font-semibold ${
                            o.side === 'BUY' ? 'text-emerald-400' : 'text-rose-400'
                          }`}
                        >
                          {o.side}
                        </td>
                        <td className="py-2.5 pr-4">
                          <Badge
                            tone={
                              isFailedOrder(o) ? 'danger' : isSuccessOrder(o) ? 'success' : 'warn'
                            }
                          >
                            {o.status}
                          </Badge>
                        </td>
                        <td className="py-2.5 pr-4 font-mono-num">{o.avgPrice ?? o.price ?? '—'}</td>
                        <td className="py-2.5 pr-4 font-mono-num">
                          {o.sizeInr != null ? fmtInr(o.sizeInr, false) : '—'}
                        </td>
                        <td className="py-2.5 pr-4 font-mono-num text-slate-400">{o.quantity}</td>
                        <td className="whitespace-nowrap py-2.5 font-mono-num text-xs text-slate-400">
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
        </>
      )}
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
