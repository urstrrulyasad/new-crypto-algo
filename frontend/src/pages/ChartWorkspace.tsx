import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { api } from '@/lib/api'
import { Badge, Button, Card, Empty, PageTitle, Spinner } from '@/components/ui'
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
  entryPrice: number
  exitPrice?: number | null
  slPrice?: number | null
  targetPrice?: number | null
  status: string
  openedAt: string
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

export default function ChartWorkspace() {
  const { pair: pairParam } = useParams()
  const [search, setSearch] = useSearchParams()
  const navigate = useNavigate()

  const pair = decodeURIComponent(pairParam ?? '')
  const mode = (search.get('mode') as Mode) || 'clean'
  const timeframe = search.get('timeframe') || '5m'
  const strategyId = search.get('strategyId')
  const positionId = search.get('positionId')

  const [candles, setCandles] = useState<Candle[]>([])
  const [markers, setMarkers] = useState<TradeMarker[]>([])
  const [priceLines, setPriceLines] = useState<PriceLineSpec[]>([])
  const [banner, setBanner] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [modeBadge, setModeBadge] = useState('')

  const abortRef = useRef<AbortController | null>(null)
  const failCount = useRef(0)
  const backoffMs = useRef(10_000)

  // Invalid strategy mode → clean + toast
  useEffect(() => {
    if (mode !== 'strategy') return
    if (isUuid(strategyId)) return
    setBanner('Pick a strategy — opened clean chart.')
    const next = new URLSearchParams(search)
    next.set('mode', 'clean')
    next.delete('strategyId')
    navigate(`/futures/chart/${encodeURIComponent(pair)}?${next.toString()}`, { replace: true })
  }, [mode, strategyId, pair, navigate, search])

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
      failCount.current = 0
      backoffMs.current = 10_000
    } catch (e) {
      if (ac.signal.aborted) return
      failCount.current += 1
      backoffMs.current = Math.min(60_000, 10_000 * 2 ** Math.min(failCount.current - 1, 2))
      if (failCount.current >= 5) setError('Market data unavailable')
      else setError(e instanceof Error ? e.message : 'Candle fetch failed')
    } finally {
      if (!ac.signal.aborted) setLoading(false)
    }
  }, [pair, timeframe])

  const loadOverlays = useCallback(async () => {
    if (mode === 'clean' || !pair) {
      setMarkers([])
      setPriceLines([])
      setModeBadge('')
      return
    }

    if (mode === 'strategy' && isUuid(strategyId)) {
      try {
        const trades = await api.get<StrategyTrade[]>(
          `/api/v1/strategies/${strategyId}/trades`,
        )
        const forPair = (trades ?? []).filter((t) => t.pair === pair)
        const sorted = [...forPair].sort(
          (a, b) => new Date(b.openedAt).getTime() - new Date(a.openedAt).getTime(),
        )
        const top = sorted.slice(0, 50)
        const m: TradeMarker[] = []
        for (const t of top) {
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
            (p) => p.status === 'OPEN' && p.pair === pair && (!p.botId || botIds.has(p.botId) || botIds.size === 0),
          )
          return { pos: open[0], preferMode }
        }
        let found = matchPos(positionsLive, 'LIVE')
        if (!found.pos) found = matchPos(positionsPaper, 'PAPER')

        const lines: PriceLineSpec[] = []
        if (found.pos) {
          setModeBadge(found.preferMode)
          lines.push({
            price: Number(found.pos.entryPrice),
            label: `Entry ${found.preferMode}`,
            color: '#22d3ee',
            style: 'solid',
          })
          if (found.pos.slPrice != null && Number(found.pos.slPrice) > 0) {
            lines.push({
              price: Number(found.pos.slPrice),
              label: 'SL',
              color: '#fb7185',
              style: 'dashed',
            })
          }
          if (found.pos.targetPrice != null && Number(found.pos.targetPrice) > 0) {
            lines.push({
              price: Number(found.pos.targetPrice),
              label: 'TP',
              color: '#34d399',
              style: 'dashed',
            })
          }
        } else if (openTrade) {
          setModeBadge(openTrade.mode)
          lines.push({
            price: Number(openTrade.entryPrice),
            label: `Entry ${openTrade.mode}`,
            color: '#22d3ee',
            style: 'solid',
          })
        } else {
          setModeBadge('')
        }
        setPriceLines(lines)
        setBanner('')
      } catch {
        setBanner('Strategy overlay unavailable')
        setMarkers([])
        setPriceLines([])
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
          setModeBadge('')
          setBanner(`No ${portfolioMode} position`)
          return
        }
        setBanner('')
        setModeBadge(portfolioMode)
        const lines: PriceLineSpec[] = [
          {
            price: Number(pos.entryPrice),
            label: `Entry ${portfolioMode}`,
            color: '#22d3ee',
            style: 'solid',
          },
        ]
        if (pos.exitPrice != null && Number(pos.exitPrice) > 0) {
          lines.push({
            price: Number(pos.exitPrice),
            label: `Exit ${portfolioMode}`,
            color: '#a78bfa',
            style: 'dashed',
          })
        }
        if (pos.slPrice != null && Number(pos.slPrice) > 0) {
          lines.push({ price: Number(pos.slPrice), label: 'SL', color: '#fb7185', style: 'dashed' })
        }
        if (pos.targetPrice != null && Number(pos.targetPrice) > 0) {
          lines.push({
            price: Number(pos.targetPrice),
            label: 'TP',
            color: '#34d399',
            style: 'dashed',
          })
        }
        setPriceLines(lines)
      } catch {
        setBanner(`${portfolioMode} overlay unavailable`)
        setPriceLines([])
      }
    }
  }, [mode, pair, strategyId, positionId])

  useEffect(() => {
    setLoading(true)
    void loadCandles()
    void loadOverlays()
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
      timer = setTimeout(tick, backoffMs.current)
    }
    timer = setTimeout(tick, backoffMs.current)
    return () => clearTimeout(timer)
  }, [loadCandles, loadOverlays])

  const title = useMemo(() => pair || 'Chart', [pair])

  if (!pair) {
    return (
      <div>
        <PageTitle title="Chart" subtitle="Missing pair" />
        <Empty message="No pair in URL." />
      </div>
    )
  }

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <PageTitle
          title={title}
          subtitle={`${mode} · FUTURES INR${modeBadge ? ` · ${modeBadge}` : ''}`}
        />
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

      <Card>
        <div className="mb-4 flex flex-wrap items-center gap-2">
          {TIMEFRAMES.map((tf) => (
            <button
              key={tf}
              type="button"
              onClick={() => setTf(tf)}
              className={`rounded-lg border px-3 py-1.5 text-xs font-medium transition-colors ${
                timeframe === tf
                  ? 'border-cyan-500/60 bg-cyan-500/15 text-cyan-200'
                  : 'border-edge text-slate-400 hover:border-cyan-500/40 hover:text-cyan-300'
              }`}
            >
              {tf}
            </button>
          ))}
          <Badge tone="info">{mode}</Badge>
          {modeBadge && <Badge tone={modeBadge === 'LIVE' ? 'success' : 'warn'}>{modeBadge}</Badge>}
        </div>

        {banner && <p className="mb-3 text-sm text-amber-300">{banner}</p>}
        {error && <p className="mb-3 text-sm text-rose-400">{error}</p>}

        {loading && candles.length === 0 ? (
          <Spinner />
        ) : candles.length === 0 ? (
          <Empty message="No candle data" />
        ) : (
          <CandleChart candles={candles} markers={markers} priceLines={priceLines} height={480} />
        )}
      </Card>
    </div>
  )
}
