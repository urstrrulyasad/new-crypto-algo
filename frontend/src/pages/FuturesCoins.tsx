import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '@/lib/api'
import { Card, Empty, Input, PageTitle, Spinner } from '@/components/ui'

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
    <div>
      <PageTitle title="Coins" subtitle="INR futures instruments — clean live charts, no strategy overlays" />

      <Card className="mt-4">
        <Input
          placeholder="Filter pair (e.g. BTC)"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          className="mb-4 max-w-md"
        />
        {err && <p className="mb-3 text-sm text-rose-400">{err}</p>}
        {pairs === null ? (
          <Spinner />
        ) : filtered.length === 0 ? (
          <Empty message={pairs.length === 0 ? 'No instruments returned.' : 'No match.'} />
        ) : (
          <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
            {filtered.map((pair) => (
              <Link
                key={pair}
                to={`/futures/chart/${encodeURIComponent(pair)}?mode=clean&timeframe=5m`}
                className="rounded-xl border border-edge bg-surface/40 px-4 py-3 text-sm font-medium text-slate-200 transition-colors hover:border-cyan-500/50 hover:text-cyan-200"
              >
                {pair}
              </Link>
            ))}
          </div>
        )}
        {pairs && pairs.length > 0 && (
          <p className="mt-4 text-xs text-slate-500">
            Showing {filtered.length} of {pairs.length}
          </p>
        )}
      </Card>
    </div>
  )
}
