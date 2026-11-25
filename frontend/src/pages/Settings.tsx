import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '@/lib/api'
import { useToast } from '@/lib/toast'
import { IconEye } from '@/components/Icons'
import { Badge, Button, Card, Empty, Input, Label, PageShell, PageTitle, Spinner } from '@/components/ui'

interface Key {
  id: string
  exchange: string
  label: string
  keyLast4: string
  status: string
  createdAt: string
}

export default function Settings() {
  const { pushToast } = useToast()
  const [keys, setKeys] = useState<Key[] | null>(null)
  const [label, setLabel] = useState('')
  const [apiKey, setApiKey] = useState('')
  const [apiSecret, setApiSecret] = useState('')
  const [showSecret, setShowSecret] = useState(false)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const load = useCallback(() => {
    api.get<Key[]>('/api/v1/keys').then(setKeys).catch(() => setKeys([]))
  }, [])

  useEffect(() => {
    load()
  }, [load])

  async function add(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError('')
    try {
      await api.post('/api/v1/keys', { label, apiKey, apiSecret })
      pushToast({ title: `Exchange key "${label}" saved`, tone: 'success' })
      setLabel('')
      setApiKey('')
      setApiSecret('')
      load()
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to save key'
      setError(msg)
      pushToast({ title: 'Unable to save key', detail: msg, tone: 'error' })
    } finally {
      setBusy(false)
    }
  }

  async function remove(k: Key) {
    try {
      await api.del(`/api/v1/keys/${k.id}`)
      pushToast({ title: `Removed key "${k.label}"`, tone: 'info' })
      load()
    } catch (err) {
      pushToast({
        title: 'Failed to remove key',
        detail: err instanceof Error ? err.message : 'Error',
        tone: 'error',
      })
    }
  }

  const hasActiveKey = (keys ?? []).some((k) => k.status === 'ACTIVE')

  return (
    <PageShell>
      <PageTitle
        title="Exchange API Keys"
        subtitle="CoinDCX futures credentials — encrypted at rest, never displayed after creation"
      />
      <div className="grid gap-6 lg:grid-cols-2">
        <Card hover={false}>
          <h2 className="mb-4 font-[family-name:var(--font-display)] text-lg font-semibold text-slate-100">
            Add CoinDCX Key
          </h2>
          <form onSubmit={add} className="space-y-4">
            <div>
              <Label>Label</Label>
              <Input value={label} onChange={(e) => setLabel(e.target.value)} placeholder="Main account" />
            </div>
            <div>
              <Label>API Key</Label>
              <Input
                autoComplete="off"
                spellCheck={false}
                value={apiKey}
                onChange={(e) => setApiKey(e.target.value)}
                placeholder="From CoinDCX API dashboard"
              />
            </div>
            <div>
              <Label>API Secret</Label>
              <div className="relative">
                <Input
                  type={showSecret ? 'text' : 'password'}
                  autoComplete="new-password"
                  spellCheck={false}
                  value={apiSecret}
                  onChange={(e) => setApiSecret(e.target.value)}
                  placeholder="Stored encrypted (AES-256-GCM)"
                  className="pr-10"
                />
                <button
                  type="button"
                  onClick={() => setShowSecret((v) => !v)}
                  aria-label={showSecret ? 'Hide secret' : 'Show secret'}
                  className="absolute right-3 top-1/2 -translate-y-1/2 cursor-pointer text-slate-400 hover:text-slate-200"
                >
                  <IconEye off={showSecret} className="h-4 w-4" />
                </button>
              </div>
            </div>
            {error && <p className="text-sm text-rose-400">{error}</p>}
            <Button type="submit" disabled={busy || !label || !apiKey || !apiSecret}>
              {busy ? 'Saving…' : 'Save key'}
            </Button>
            <p className="text-xs leading-relaxed text-slate-500">
              Generate API keys in CoinDCX → Profile → API Dashboard. Paper trading works without keys; Real Trades (LIVE) requires an active key.
            </p>
          </form>
        </Card>

        <Card delay={0.06} hover={false}>
          <div className="mb-4 flex items-center justify-between gap-3">
            <h2 className="font-[family-name:var(--font-display)] text-lg font-semibold text-slate-100">
              Configured Keys
            </h2>
            {hasActiveKey && (
              <Link
                to="/"
                className="rounded-xl border border-emerald-500/35 bg-emerald-500/12 px-3 py-1.5 text-xs font-medium text-emerald-300 transition hover:bg-emerald-500/20"
              >
                View Real Trades →
              </Link>
            )}
          </div>
          {keys === null ? (
            <Spinner />
          ) : keys.length === 0 ? (
            <Empty message="No exchange keys configured yet. Add your CoinDCX API key & secret on the left to unlock Real Trades (LIVE)." />
          ) : (
            <div className="space-y-2">
              {keys.map((k) => (
                <div
                  key={k.id}
                  className="flex items-center justify-between rounded-xl border border-edge bg-surface/65 px-4 py-3"
                >
                  <div>
                    <div className="text-sm font-medium text-slate-100">{k.label}</div>
                    <div className="mt-0.5 font-mono-num text-xs text-slate-500">
                      {k.exchange} · ••••{k.keyLast4}
                    </div>
                  </div>
                  <div className="flex items-center gap-2">
                    <Badge tone={k.status === 'ACTIVE' ? 'success' : 'default'}>{k.status}</Badge>
                    <Button variant="danger" onClick={() => remove(k)} className="px-3 py-1 text-xs">
                      Delete
                    </Button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </Card>
      </div>
    </PageShell>
  )
}
