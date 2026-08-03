import { useEffect, useState } from 'react'
import { AnimatePresence, motion } from 'motion/react'
import { api } from '@/lib/api'
import { Badge, Button, Card, Empty, Input, Label, PageTitle, Spinner } from '@/components/ui'

interface Bot {
  id: string
  name: string
  mode: string
  marketType: string
  status: string
  killSwitch: boolean
  stakeAmount: number
  stakeCurrency: string
  maxOpenTrades: number
}

interface Strategy {
  id: string
  name: string
  status: string
}

interface Key {
  id: string
  label: string
}

export default function Bots() {
  const [bots, setBots] = useState<Bot[] | null>(null)
  const [strategies, setStrategies] = useState<Strategy[]>([])
  const [keys, setKeys] = useState<Key[]>([])
  const [showCreate, setShowCreate] = useState(false)

  const load = () => api.get<Bot[]>('/api/v1/bots').then(setBots).catch(() => setBots([]))

  useEffect(() => {
    load()
    api.get<Strategy[]>('/api/v1/strategies').then((s) => setStrategies(s.filter((x) => x.status !== 'REJECTED')))
    api.get<Key[]>('/api/v1/keys').then(setKeys).catch(() => setKeys([]))
  }, [])

  async function action(id: string, act: 'start' | 'stop') {
    await api.post(`/api/v1/bots/${id}/${act}`)
    load()
  }

  async function kill(id: string, enabled: boolean) {
    await api.post(`/api/v1/bots/${id}/kill-switch?enabled=${enabled}`)
    load()
  }

  return (
    <div>
      <PageTitle title="Trading Bots" subtitle="Paper-trade first; go live only with an approved, backtested strategy" />
      <div className="mb-5">
        <Button onClick={() => setShowCreate(true)}>+ New bot</Button>
      </div>

      <AnimatePresence>
        {showCreate && (
          <CreateBot
            strategies={strategies}
            keys={keys}
            onClose={() => setShowCreate(false)}
            onCreated={() => {
              setShowCreate(false)
              load()
            }}
          />
        )}
      </AnimatePresence>

      {bots === null ? (
        <Spinner />
      ) : bots.length === 0 ? (
        <Card><Empty message="No bots yet. Approve a strategy in Strategy Lab, then create a paper bot here." /></Card>
      ) : (
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {bots.map((b, i) => (
            <Card key={b.id} delay={i * 0.05}>
              <div className="flex items-start justify-between gap-2">
                <div>
                  <div className="font-medium text-slate-100">{b.name}</div>
                  <div className="mt-1 flex flex-wrap gap-1.5">
                    <Badge tone={b.mode === 'LIVE' ? 'danger' : 'info'}>{b.mode}</Badge>
                    <Badge>{b.marketType}</Badge>
                    <Badge tone={b.status === 'RUNNING' ? 'success' : 'default'}>{b.status}</Badge>
                    {b.killSwitch && <Badge tone="danger">KILL SWITCH</Badge>}
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
                Stake {b.stakeAmount} {b.stakeCurrency} · max {b.maxOpenTrades} open trades
              </div>
              <div className="mt-4 flex gap-2">
                {b.status === 'RUNNING' ? (
                  <Button variant="ghost" onClick={() => action(b.id, 'stop')}>■ Stop</Button>
                ) : b.marketType === 'FUTURES' ? null : (
                  <Button onClick={() => action(b.id, 'start')}>▶ Start</Button>
                )}
                <Button variant="danger" onClick={() => kill(b.id, !b.killSwitch)}>
                  {b.killSwitch ? 'Release kill switch' : 'Kill switch'}
                </Button>
              </div>
            </Card>
          ))}
        </div>
      )}
    </div>
  )
}

function CreateBot({ strategies, keys, onClose, onCreated }: {
  strategies: Strategy[]; keys: Key[]; onClose: () => void; onCreated: () => void
}) {
  const [name, setName] = useState('')
  const [strategyId, setStrategyId] = useState(strategies[0]?.id ?? '')
  const [mode, setMode] = useState('PAPER')
  const [keyId, setKeyId] = useState(keys[0]?.id ?? '')
  const [stake, setStake] = useState('100')
  const [pairs, setPairs] = useState('B-BTC_USDT')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  async function create() {
    setBusy(true)
    setError('')
    try {
      await api.post('/api/v1/bots', {
        name,
        strategyId,
        mode,
        exchangeKeyId: mode === 'LIVE' ? keyId : null,
        marketType: 'SPOT',
        pairs: pairs.split(',').map((p) => p.trim()),
        stakeAmount: Number(stake),
      })
      onCreated()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed')
    } finally {
      setBusy(false)
    }
  }

  return (
    <motion.div
      initial={{ opacity: 0, height: 0 }}
      animate={{ opacity: 1, height: 'auto' }}
      exit={{ opacity: 0, height: 0 }}
      className="mb-6 overflow-hidden"
    >
      <div className="glass rounded-2xl p-6">
        <div className="mb-4 flex items-center justify-between">
          <h3 className="font-[family-name:var(--font-display)] text-lg font-semibold text-slate-100">New Bot</h3>
          <button onClick={onClose} className="text-slate-500 hover:text-slate-300">✕</button>
        </div>
        {strategies.length === 0 ? (
          <p className="text-sm text-amber-300">No strategies yet — generate one in the AI Strategy Pipeline. Bots are normally created automatically.</p>
        ) : (
          <div className="grid gap-4 md:grid-cols-2">
            <div>
              <Label>Name</Label>
              <Input value={name} onChange={(e) => setName(e.target.value)} placeholder="BTC momentum bot" />
            </div>
            <div>
              <Label>Strategy (approved)</Label>
              <select value={strategyId} onChange={(e) => setStrategyId(e.target.value)} className="w-full rounded-xl border border-edge bg-surface px-4 py-2.5 text-sm text-slate-200">
                {strategies.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
              </select>
            </div>
            <div>
              <Label>Mode</Label>
              <select value={mode} onChange={(e) => setMode(e.target.value)} className="w-full rounded-xl border border-edge bg-surface px-4 py-2.5 text-sm text-slate-200">
                <option value="PAPER">Paper (simulated)</option>
                <option value="LIVE">Live (real money)</option>
              </select>
            </div>
            {mode === 'LIVE' && (
              <div>
                <Label>CoinDCX API key</Label>
                {keys.length === 0 ? (
                  <p className="text-xs text-amber-300">Add a CoinDCX key in API Keys first.</p>
                ) : (
                  <select value={keyId} onChange={(e) => setKeyId(e.target.value)} className="w-full rounded-xl border border-edge bg-surface px-4 py-2.5 text-sm text-slate-200">
                    {keys.map((k) => <option key={k.id} value={k.id}>{k.label}</option>)}
                  </select>
                )}
              </div>
            )}
            <div>
              <Label>Stake per trade (USDT)</Label>
              <Input type="number" value={stake} onChange={(e) => setStake(e.target.value)} />
            </div>
            <div>
              <Label>Pairs (comma separated)</Label>
              <Input value={pairs} onChange={(e) => setPairs(e.target.value)} />
            </div>
            {error && <p className="text-sm text-rose-400 md:col-span-2">{error}</p>}
            <div className="md:col-span-2">
              <Button onClick={create} disabled={busy || !name}>{busy ? 'Creating…' : 'Create bot'}</Button>
            </div>
          </div>
        )}
      </div>
    </motion.div>
  )
}
