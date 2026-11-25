import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { motion } from 'motion/react'
import { api } from '@/lib/api'
import { useToast } from '@/lib/toast'
import { Badge, Button, Card, Empty, PageShell, PageTitle, Spinner } from '@/components/ui'
import {
  CandleChart,
  type Candle,
  type PriceLineSpec,
  type TradeMarker,
} from '@/components/CandleChart'

const TIMEFRAMES = ['1m', '5m', '15m', '1h'] as const
type Mode = 'clean' | 'strategy' | 'live' | 'paper'

interface StrategyTrade {
  id: string
  botId?: string
  mode: string
  pair: string
  side: string
  entryPrice: number
  exitPrice?: number | null
  status: string
  openedAt: string
  closedAt?: string | null
}

interface PortfolioPosition {
  id: string
  botId?: string
  pair: string
  side: string
  quantity?: number
  entryPrice: number
  exitPrice?: number | null
  slPrice?: number | null
  targetPrice?: number | null
  status: string
  leverage?: number | null
  realizedPnl?: number | null
  unrealizedPnl?: number | null
  pnl?: number | null
  markPrice?: number | null
  marginInr?: number | null
  sizeInr?: number | null
  roePct?: number | null
  openedAt: string
  closedAt?: string | null
}

function isUuid(v: string | null): boolean {
  if (!v) return false
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(v)
}

function candleRange(timeframe: string): { from: string; to: string } {
  const to = new Date()
  const from = new Date(to)
  const days = timeframe === '1m' ? 2 : timeframe === '5m' ? 5 : timeframe === '15m' ? 10 : 30
  from.setUTCDate(from.getUTCDate() - days)
  return { from: from.toISOString(), to: to.toISOString() }
}

function fmtInr(v?: number | null, signed = true) {
  if (v == null || Number.isNaN(Number(v))) return '—'
  const n = Number(v)
  const abs = Math.abs(n).toLocaleString('en-IN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })
  if (!signed) return `₹${abs}`
  if (n > 0) return `+₹${abs}`
  if (n < 0) return `−₹${abs}`
  return `₹${abs}`
}

function fmtPrice(v?: number | null) {
  if (v == null || !Number.isFinite(Number(v)) || Number(v) <= 0) return '—'
  const n = Number(v)
  if (n >= 1000) return n.toFixed(2)
  if (n >= 1) return n.toFixed(4)
  if (n >= 0.01) return n.toFixed(6)
  return n.toFixed(8)
}

function entryLineLabel(pos: PortfolioPosition, modeTag: string) {
  const pnl = pos.pnl ?? pos.unrealizedPnl
  const qty = pos.quantity != null ? Number(pos.quantity).toFixed(2) : ''
  if (pnl != null && Number.isFinite(Number(pnl))) {
    const sign = Number(pnl) >= 0 ? '+' : ''
    return `${qty ? `${qty} ` : ''}${sign}${Number(pnl).toFixed(2)} INR`
  }
  return `Entry ${modeTag}`
}

export default function ChartWorkspace() {
  const { pair: pairParam } = useParams()
  const [search, setSearch] = useSearchParams()
  const navigate = useNavigate()
  const { pushToast } = useToast()

  const pair = decodeURIComponent(pairParam ?? '')
  const mode = (search.get('mode') as Mode) || 'clean'
  const timeframe = search.get('timeframe') || '5m'
  const strategyId = search.get('strategyId')
  const positionId = search.get('positionId')

  const [candles, setCandles] = useState<Candle[]>([])
  const [markers, setMarkers] = useState<TradeMarker[]>([])
  const [priceLines, setPriceLines] = useState<PriceLineSpec[]>([])
  const [activePos, setActivePos] = useState<PortfolioPosition | null>(null)
  const [banner, setBanner] = useState('')
  const [error, setError] = useState('')
  const [loadedKey, setLoadedKey] = useState('')
  const [modeBadge, setModeBadge] = useState('')

  const abortRef = useRef<AbortController | null>(null)
  const failCount = useRef(0)
  const backoffMs = useRef(10_000)
  const currentKey = `${pair}|${timeframe}|${mode}`
  const loading = loadedKey !== currentKey && candles.length === 0

  useEffect(() => {
    if (mode !== 'strategy' || isUuid(strategyId)) return
    pushToast({ title: 'Opened clean chart', detail: 'Select a strategy to view trade overlays.', tone: 'info' })
    const next = new URLSearchParams(search)
    next.set('mode', 'clean')
    next.delete('strategyId')
    navigate(`/futures/chart/${encodeURIComponent(pair)}?${next.toString()}`, { replace: true })
  }, [mode, strategyId, pair, navigate, search, pushToast])

  const setTf = (tf: string) => {
    const next = new URLSearchParams(search)
    next.set('timeframe', tf)
    if (!next.get('mode')) next.set('mode', mode)
    setSearch(next, { replace: true })
  }

  const loadCandles = useCallback(async () => {
    if (!pair) return
    abortRef.current?.abort()
    const ac = new AbortController()
    abortRef.current = ac
    const { from, to } = candleRange(timeframe)
    try {
      const data = await api.get<Candle[]>(
        `/api/v1/market/candles?pair=${encodeURIComponent(pair)}&timeframe=${encodeURIComponent(timeframe)}&from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}&marketType=FUTURES&limit=500`,
      )
      if (ac.signal.aborted) return
      setCandles(data ?? [])
      setError(data?.length ? '' : 'No candle data')
      setLoadedKey(currentKey)
      failCount.current = 0
      backoffMs.current = mode === 'live' || mode === 'paper' ? 3_000 : 10_000
    } catch (e) {
      if (ac.signal.aborted) return
      failCount.current += 1
      backoffMs.current = Math.min(60_000, 10_000 * 2 ** Math.min(failCount.current - 1, 2))
      setError(failCount.current >= 5 ? 'Market data unavailable' : e instanceof Error ? e.message : 'Candle fetch failed')
      setLoadedKey(currentKey)
    }
  }, [pair, timeframe, mode, currentKey])

  const loadOverlays = useCallback(async () => {
    if (mode === 'clean' || !pair) {
      await Promise.resolve()
      setMarkers([])
      setPriceLines([])
      setActivePos(null)
      setModeBadge('')
      return
    }

    if (mode === 'strategy' && isUuid(strategyId)) {
      try {
        const trades = await api.get<StrategyTrade[]>(`/api/v1/strategies/${strategyId}/trades`)
        const forPair = (trades ?? []).filter((t) => t.pair === pair)
        const sorted = [...forPair].sort(
          (a, b) => new Date(b.openedAt).getTime() - new Date(a.openedAt).getTime(),
        )
        const m: TradeMarker[] = []
        for (const t of sorted.slice(0, 50)) {
          if (t.openedAt) m.push({ time: t.openedAt, type: 'entry' })
          if (t.status === 'CLOSED' && t.closedAt) m.push({ time: t.closedAt, type: 'exit' })
        }
        setMarkers(m)

        const openTrade = sorted.find((t) => t.status === 'OPEN')
        const positionsLive = await api
          .get<PortfolioPosition[]>('/api/v1/portfolio/positions?mode=LIVE')
          .catch(() => [] as PortfolioPosition[])
        const positionsPaper = await api
          .get<PortfolioPosition[]>('/api/v1/portfolio/positions?mode=PAPER')
          .catch(() => [] as PortfolioPosition[])

        const botIds = new Set(forPair.map((t) => t.botId).filter(Boolean))
        const matchPos = (list: PortfolioPosition[], preferMode: string) => {
          const open = list.filter(
            (p) =>
              p.status === 'OPEN' &&
              p.pair === pair &&
              (!p.botId || botIds.has(p.botId) || botIds.size === 0),
          )
          return { pos: open[0], preferMode }
        }
        let found = matchPos(positionsLive, 'LIVE')
        if (!found.pos) found = matchPos(positionsPaper, 'PAPER')

        const lines: PriceLineSpec[] = []
        if (found.pos) {
          setModeBadge(found.preferMode)
          setActivePos(found.pos)
          lines.push({
            price: Number(found.pos.entryPrice),
            label: entryLineLabel(found.pos, found.preferMode),
            color: '#f59e0b',
            style: 'solid',
          })
          if (found.pos.markPrice != null && Number(found.pos.markPrice) > 0) {
            lines.push({ price: Number(found.pos.markPrice), label: 'Mark', color: '#94a3b8', style: 'dashed' })
          }
          if (found.pos.slPrice != null && Number(found.pos.slPrice) > 0) {
            lines.push({ price: Number(found.pos.slPrice), label: 'SL', color: '#f43f5e', style: 'dashed' })
          }
          if (found.pos.targetPrice != null && Number(found.pos.targetPrice) > 0) {
            lines.push({ price: Number(found.pos.targetPrice), label: 'TP', color: '#10b981', style: 'dashed' })
          }
        } else if (openTrade) {
          setActivePos(null)
          setModeBadge(openTrade.mode)
          lines.push({
            price: Number(openTrade.entryPrice),
            label: `Entry ${openTrade.mode}`,
            color: '#f59e0b',
            style: 'solid',
          })
        } else {
          setActivePos(null)
          setModeBadge('')
        }
        setPriceLines(lines)
        setBanner('')
      } catch {
        setBanner('Strategy overlay unavailable')
        setMarkers([])
        setPriceLines([])
        setActivePos(null)
      }
      return
    }

    if (mode === 'live' || mode === 'paper') {
      const portfolioMode = mode === 'paper' ? 'PAPER' : 'LIVE'
      try {
        const positions = await api.get<PortfolioPosition[]>(
          `/api/v1/portfolio/positions?mode=${portfolioMode}`,
        )
        let pos: PortfolioPosition | undefined
        if (positionId && isUuid(positionId)) {
          pos = (positions ?? []).find((p) => p.id === positionId)
        }
        if (!pos) {
          const open = (positions ?? []).filter((p) => p.status === 'OPEN' && p.pair === pair)
          pos = [...open].sort(
            (a, b) => new Date(b.openedAt).getTime() - new Date(a.openedAt).getTime(),
          )[0]
        }
        setMarkers([])
        if (!pos) {
          setPriceLines([])
          setActivePos(null)
          setModeBadge('')
          setBanner(`No ${portfolioMode} position`)
          return
        }
        setBanner('')
        setModeBadge(portfolioMode)
        setActivePos(pos)
        const lines: PriceLineSpec[] = [
          {
            price: Number(pos.entryPrice),
            label: entryLineLabel(pos, portfolioMode),
            color: '#f59e0b',
            style: 'solid',
          },
        ]
        if (pos.markPrice != null && Number(pos.markPrice) > 0) {
          lines.push({ price: Number(pos.markPrice), label: 'Mark', color: '#94a3b8', style: 'dashed' })
        }
        if (pos.exitPrice != null && Number(pos.exitPrice) > 0) {
          lines.push({ price: Number(pos.exitPrice), label: `Exit ${portfolioMode}`, color: '#a78bfa', style: 'dashed' })
        }
        if (pos.slPrice != null && Number(pos.slPrice) > 0) {
          lines.push({ price: Number(pos.slPrice), label: 'SL', color: '#f43f5e', style: 'dashed' })
        }
        if (pos.targetPrice != null && Number(pos.targetPrice) > 0) {
          lines.push({ price: Number(pos.targetPrice), label: 'TP', color: '#10b981', style: 'dashed' })
        }
        setPriceLines(lines)
      } catch {
        setBanner(`${portfolioMode} overlay unavailable`)
        setPriceLines([])
        setActivePos(null)
      }
    }
  }, [mode, pair, strategyId, positionId])

  useEffect(() => {
    const id = setTimeout(() => {
      void loadCandles()
      void loadOverlays()
    }, 0)
    return () => clearTimeout(id)
  }, [loadCandles, loadOverlays])

  useEffect(() => {
    let timer: ReturnType<typeof setTimeout>
    const tick = () => {
      if (document.hidden) {
        timer = setTimeout(tick, 10_000)
        return
      }
      void loadCandles()
      void loadOverlays()
      const ms = mode === 'live' || mode === 'paper' ? 3_000 : backoffMs.current
      timer = setTimeout(tick, ms)
    }
    timer = setTimeout(tick, mode === 'live' || mode === 'paper' ? 3_000 : backoffMs.current)
    return () => clearTimeout(timer)
  }, [loadCandles, loadOverlays, mode])

  const title = useMemo(() => pair || 'Chart', [pair])
  const lastCandle = candles[candles.length - 1]
  const activePnl = activePos?.pnl ?? activePos?.unrealizedPnl ?? null
  const showPosPanel =
    activePos != null &&
    (mode === 'live' || mode === 'paper' || (mode === 'strategy' && activePos.status === 'OPEN'))

  if (!pair) {
    return (
      <PageShell>
        <PageTitle title="Chart" subtitle="Missing pair" />
        <Empty message="No pair in URL." />
      </PageShell>
    )
  }

  return (
    <PageShell>
      <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="font-[family-name:var(--font-display)] text-2xl font-bold tracking-tight text-slate-100 sm:text-3xl">
            {title}
            {lastCandle && (
              <span className="ml-3 font-mono-num text-xl font-semibold text-amber-300">
                {fmtPrice(lastCandle.close)}
              </span>
            )}
          </h1>
          <p className="mt-1 font-mono-num text-xs uppercase tracking-wider text-slate-400">
            {mode} · FUTURES INR{modeBadge ? ` · ${modeBadge}` : ''}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Link to="/futures/coins">
            <Button variant="ghost">Coins</Button>
          </Link>
          {mode === 'strategy' && strategyId && (
            <Link to="/futures/strategies">
              <Button variant="ghost">Strategies</Button>
            </Link>
          )}
          <Link to="/">
            <Button variant="ghost">Dashboard</Button>
          </Link>
        </div>
      </div>

      <Card hover={false}>
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <div className="inline-flex gap-1 rounded-xl border border-edge bg-surface/80 p-1">
            {TIMEFRAMES.map((tf) => (
              <button
                key={tf}
                type="button"
                onClick={() => setTf(tf)}
                className={`relative cursor-pointer rounded-lg px-3 py-1 font-mono-num text-xs font-medium transition-colors ${
                  timeframe === tf ? 'text-amber-200' : 'text-slate-400 hover:text-slate-200'
                }`}
              >
                {timeframe === tf && (
                  <motion.span
                    layoutId="chart-tf-pill"
                    className="absolute inset-0 rounded-lg border border-amber-500/35 bg-amber-500/15"
                    transition={{ type: 'spring', stiffness: 420, damping: 32 }}
                  />
                )}
                <span className="relative z-10">{tf}</span>
              </button>
            ))}
          </div>
          <div className="flex items-center gap-2">
            <Badge tone="info">{mode}</Badge>
            {modeBadge && <Badge tone={modeBadge === 'LIVE' ? 'success' : 'warn'}>{modeBadge}</Badge>}
          </div>
        </div>

        {banner && <p className="mb-3 text-sm text-amber-300">{banner}</p>}
        {error && <p className="mb-3 text-sm text-rose-400">{error}</p>}

        {loading ? (
          <Spinner />
        ) : candles.length === 0 ? (
          <Empty message="No candle data" />
        ) : (
          <CandleChart candles={candles} markers={markers} priceLines={priceLines} height={480} />
        )}
      </Card>

      {showPosPanel && (
        <Card className="mt-4" hover={false}>
          <div className="mb-3 flex flex-wrap items-baseline justify-between gap-2">
            <div>
              <p className="text-sm font-semibold text-slate-100">
                Active Position · {activePos.pair.replace(/^B-/, '').replace('_', '-')}{' '}
                <span className={activePos.side === 'SHORT' ? 'text-rose-400' : 'text-emerald-400'}>
                  {activePos.side}
                </span>
                {activePos.leverage != null ? ` · ${activePos.leverage}x` : ''}
              </p>
              <p className="font-mono-num text-xs text-slate-500">{activePos.status}</p>
            </div>
            <div className="text-right">
              <p
                className={`font-mono-num text-lg font-semibold ${
                  Number(activePnl ?? 0) >= 0 ? 'text-emerald-400' : 'text-rose-400'
                }`}
              >
                {fmtInr(activePnl)}
                {activePos.roePct != null
                  ? ` (${Number(activePos.roePct) >= 0 ? '+' : ''}${Number(activePos.roePct).toFixed(2)}%)`
                  : ''}
              </p>
              <p className="text-xs text-slate-500">Active PnL</p>
            </div>
          </div>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
            <PosStat label="Margin" value={fmtInr(activePos.marginInr, false)} />
            <PosStat
              label="Size"
              value={
                activePos.sizeInr != null
                  ? fmtInr(activePos.sizeInr, false)
                  : activePos.quantity != null
                    ? String(activePos.quantity)
                    : '—'
              }
            />
            <PosStat label="Avg. entry" value={fmtPrice(activePos.entryPrice)} />
            <PosStat label="Mark" value={fmtPrice(activePos.markPrice)} />
            <PosStat label="SL" value={fmtPrice(activePos.slPrice)} />
            <PosStat label="TP" value={fmtPrice(activePos.targetPrice)} />
          </div>
        </Card>
      )}
    </PageShell>
  )
}

function PosStat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-xl border border-edge bg-surface/65 px-3 py-2">
      <p className="text-[11px] uppercase tracking-wider text-slate-500">{label}</p>
      <p className="mt-0.5 font-mono-num text-sm font-medium text-slate-100">{value}</p>
    </div>
  )
}
