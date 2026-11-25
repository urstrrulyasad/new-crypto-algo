import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '@/lib/api'

export interface TickerItem {
  pair: string
  symbol: string
  lastPrice: number
  changePct: number
  high24h: number
  low24h: number
  volume: number
}

function fmtPrice(v: number) {
  if (v >= 1000) return v.toLocaleString(undefined, { maximumFractionDigits: 2 })
  if (v >= 1) return v.toFixed(3)
  return v.toFixed(5)
}

export function TickerStrip({ interactive = true }: { interactive?: boolean }) {
  const [tickers, setTickers] = useState<TickerItem[]>([])
  const navigate = useNavigate()

  useEffect(() => {
    let active = true
    const load = () => {
      api
        .get<TickerItem[]>('/api/v1/market/tickers')
        .then((rows) => {
          if (active && Array.isArray(rows)) setTickers(rows)
        })
        .catch(() => {})
    }
    load()
    const timer = setInterval(load, 12_000)
    return () => {
      active = false
      clearInterval(timer)
    }
  }, [])

  if (tickers.length === 0) return null

  return (
    <div className="mb-5 overflow-x-auto rounded-xl border border-edge bg-surface/85">
      <div className="flex min-w-max divide-x divide-edge">
        {tickers.map((t) => {
          const up = t.changePct >= 0
          return (
            <button
              key={t.pair}
              type="button"
              disabled={!interactive}
              onClick={() => {
                if (interactive) {
                  navigate(`/futures/chart/${encodeURIComponent(t.pair)}?mode=clean&timeframe=5m`)
                }
              }}
              className={`flex items-center gap-3 px-4 py-2 text-left transition-colors ${
                interactive ? 'cursor-pointer hover:bg-card/80' : 'cursor-default'
              }`}
            >
              <span className="font-[family-name:var(--font-display)] text-xs font-semibold tracking-wide text-slate-200">
                {t.symbol}
              </span>
              <span className="font-mono-num text-xs font-medium text-slate-100">
                ${fmtPrice(t.lastPrice)}
              </span>
              <span
                className={`font-mono-num text-[11px] font-medium ${
                  up ? 'text-emerald-400' : 'text-rose-400'
                }`}
              >
                {up ? '+' : ''}
                {t.changePct.toFixed(2)}%
              </span>
            </button>
          )
        })}
      </div>
    </div>
  )
}
