import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { motion } from 'motion/react'
import { api } from '@/lib/api'
import { IconChevronRight, IconSearch } from '@/components/Icons'
import { Card, Empty, Input, PageShell, PageTitle, Spinner } from '@/components/ui'

interface InstrumentsResp {
  marginCurrency: string
  count: number
  instruments: string[]
}

export default function FuturesCoins() {
  const [pairs, setPairs] = useState<string[] | null>(null)
  const [err, setErr] = useState('')
  const [q, setQ] = useState('')

  useEffect(() => {
    api
      .get<InstrumentsResp>('/api/v1/market/futures/instruments?marginCurrency=INR')
      .then((r) => setPairs(r.instruments ?? []))
      .catch((e) => {
        setPairs([])
        setErr(e instanceof Error ? e.message : 'Failed to load instruments')
      })
  }, [])

  const filtered = useMemo(() => {
    if (!pairs) return []
    const needle = q.trim().toUpperCase()
    if (!needle) return pairs
    return pairs.filter((p) => p.toUpperCase().includes(needle))
  }, [pairs, q])

  return (
    <PageShell>
      <PageTitle
        title="Futures Coins"
        subtitle="CoinDCX INR-margined futures instruments — open any pair for live candlestick charts"
      />

      <Card hover={false}>
        <div className="mb-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div className="relative w-full max-w-md">
            <IconSearch className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-500" />
            <Input
              placeholder="Filter pair (e.g. BTC, ETH, SOL)"
              value={q}
              onChange={(e) => setQ(e.target.value)}
              className="pl-10"
            />
          </div>
          {pairs && (
            <span className="font-mono-num text-xs text-slate-400">
              {filtered.length} of {pairs.length} instruments
            </span>
          )}
        </div>

        {err && <p className="mb-3 text-sm text-rose-400">{err}</p>}
        {pairs === null ? (
          <Spinner />
        ) : filtered.length === 0 ? (
          <Empty message={pairs.length === 0 ? 'No instruments returned.' : 'No matching pairs.'} />
        ) : (
          <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
            {filtered.slice(0, 120).map((pair, i) => {
              const base = pair.replace(/^B-/, '').replace(/_USDT$/, '').replace(/_INR$/, '')
              return (
                <motion.div
                  key={pair}
                  initial={i < 36 ? { opacity: 0, y: 6 } : false}
                  animate={{ opacity: 1, y: 0 }}
                  transition={{ delay: Math.min(i * 0.008, 0.2), duration: 0.22 }}
                >
                  <Link
                    to={`/futures/chart/${encodeURIComponent(pair)}?mode=clean&timeframe=5m`}
                    className="glass-hover flex items-center justify-between rounded-xl border border-edge bg-surface/60 px-3.5 py-2.5 text-sm transition-colors hover:text-amber-200"
                  >
                    <div>
                      <div className="font-medium text-slate-100">{base}</div>
                      <div className="font-mono-num text-[11px] text-slate-500">{pair}</div>
                    </div>
                    <IconChevronRight className="h-4 w-4 text-slate-500" />
                  </Link>
                </motion.div>
              )
            })}
          </div>
        )}
      </Card>
    </PageShell>
  )
}
