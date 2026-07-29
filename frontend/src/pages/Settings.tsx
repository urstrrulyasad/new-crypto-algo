import { useEffect, useState } from 'react'
import { api } from '@/lib/api'
import { Badge, Button, Card, Empty, Input, Label, PageTitle, Spinner } from '@/components/ui'

interface Key {
  id: string
  exchange: string
  label: string
  keyLast4: string
  status: string
  createdAt: string
}

export default function Settings() {
  const [keys, setKeys] = useState<Key[] | null>(null)
  const [label, setLabel] = useState('')
  const [apiKey, setApiKey] = useState('')
  const [apiSecret, setApiSecret] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const load = () => api.get<Key[]>('/api/v1/keys').then(setKeys).catch(() => setKeys([]))
  useEffect(() => { load() }, [])

  async function add() {
    setBusy(true)
    setError('')
    try {
      await api.post('/api/v1/keys', { label, apiKey, apiSecret })
      setLabel(''); setApiKey(''); setApiSecret('')
      load()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div>
      <PageTitle title="API Keys" subtitle="Your CoinDCX credentials — encrypted at rest, never displayed again" />
      <div className="grid gap-6 lg:grid-cols-2">
        <Card>
          <h2 className="mb-4 font-[family-name:var(--font-display)] text-lg font-semibold text-slate-100">Add CoinDCX key</h2>
          <div className="space-y-4">
            <div>
              <Label>Label</Label>
              <Input value={label} onChange={(e) => setLabel(e.target.value)} placeholder="Main account" />
            </div>
            <div>
              <Label>API Key</Label>
              <Input autoComplete="off" spellCheck={false} value={apiKey} onChange={(e) => setApiKey(e.target.value)} placeholder="From CoinDCX API dashboard" />
            </div>
            <div>
              <Label>API Secret</Label>
              <Input type="password" autoComplete="new-password" spellCheck={false} value={apiSecret} onChange={(e) => setApiSecret(e.target.value)} placeholder="Stored encrypted (AES-256-GCM)" />
            </div>
            {error && <p className="text-sm text-rose-400">{error}</p>}
            <Button onClick={add} disabled={busy || !label || !apiKey || !apiSecret}>
              {busy ? 'Saving…' : 'Save key'}
            </Button>
            <p className="text-xs text-slate-500">
              Generate keys at CoinDCX → Profile → API dashboard. If you bind the key to an IP, bind it to this
              platform's server IP.
            </p>
          </div>
        </Card>

        <Card delay={0.1}>
          <h2 className="mb-4 font-[family-name:var(--font-display)] text-lg font-semibold text-slate-100">Your keys</h2>
          {keys === null ? (
            <Spinner />
          ) : keys.length === 0 ? (
            <Empty message="No keys yet. Paper trading works without keys; live trading needs one." />
          ) : (
            <div className="space-y-2">
              {keys.map((k) => (
                <div key={k.id} className="flex items-center justify-between rounded-xl border border-edge/60 bg-surface/50 px-4 py-3">
                  <div>
                    <div className="text-sm font-medium text-slate-200">{k.label}</div>
                    <div className="text-xs text-slate-500">{k.exchange} · ••••{k.keyLast4}</div>
                  </div>
                  <div className="flex items-center gap-2">
                    <Badge tone={k.status === 'ACTIVE' ? 'success' : 'default'}>{k.status}</Badge>
                    <Button variant="danger" onClick={() => api.del(`/api/v1/keys/${k.id}`).then(load)}>Delete</Button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </Card>
      </div>
    </div>
  )
}
