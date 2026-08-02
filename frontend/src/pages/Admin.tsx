import { useEffect, useState } from 'react'
import { api, currentUser } from '@/lib/api'
import { Badge, Button, Callout, Card, Empty, Input, Label, PageShell, PageTitle, Spinner } from '@/components/ui'

interface AiHealth {
  rateLimited: boolean
  message: string
  lastAt?: string | null
  recentRateLimitEvents: number
}

interface Provider {
  id: string
  providerType: string
  displayName: string
  models: string[]
  priority: number
  enabled: boolean
}

interface CatalogEntry {
  type: string
  displayName: string
  models: string[]
}

interface Tenant {
  id: string
  name: string
  slug: string
  status: string
}

interface User {
  id: string
  email: string
  displayName: string
  role: string
  status: string
}

export default function Admin() {
  const me = currentUser()
  return (
    <PageShell>
      <PageTitle title="Admin" subtitle="AI provider configuration, users and tenants" />
      <AiRateLimitBanner />
      <div className="grid gap-6 xl:grid-cols-2">
        <Providers />
        <Users />
        {me?.role === 'SUPER_ADMIN' && <Tenants />}
      </div>
    </PageShell>
  )
}

function AiRateLimitBanner() {
  const [health, setHealth] = useState<AiHealth | null>(null)
  useEffect(() => {
    const load = () =>
      api.get<AiHealth>('/api/v1/ai/health').then(setHealth).catch(() => setHealth(null))
    load()
    const t = setInterval(load, 15_000)
    return () => clearInterval(t)
  }, [])
  if (!health?.rateLimited) return null
  return (
    <Callout tone="warn">
      <div className="font-semibold text-amber-100">AI provider rate limited — rotate the key below</div>
      <p className="mt-1 text-amber-100/90">{health.message}</p>
      <p className="mt-1 text-xs text-amber-200/70">
        {health.recentRateLimitEvents} event(s) in the last 2h
        {health.lastAt ? ` · last ${new Date(health.lastAt).toLocaleString()}` : ''}
      </p>
    </Callout>
  )
}

function Providers() {
  const [providers, setProviders] = useState<Provider[] | null>(null)
  const [catalog, setCatalog] = useState<CatalogEntry[]>([])
  const [type, setType] = useState('')
  const [apiKey, setApiKey] = useState('')
  const [error, setError] = useState('')
  const [health, setHealth] = useState<AiHealth | null>(null)

  const load = () => {
    api.get<Provider[]>('/api/v1/ai/providers').then(setProviders).catch(() => setProviders([]))
    api.get<AiHealth>('/api/v1/ai/health').then(setHealth).catch(() => setHealth(null))
  }
  useEffect(() => {
    load()
    api.get<CatalogEntry[]>('/api/v1/ai/providers/catalog').then((c) => {
      setCatalog(c)
      setType((prev) => prev || c[0]?.type || '')
    }).catch(() => setCatalog([]))
    const t = setInterval(load, 20_000)
    return () => clearInterval(t)
  }, [])

  async function add() {
    setError('')
    try {
      await api.post('/api/v1/ai/providers', { providerType: type, apiKey })
      setApiKey('')
      load()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed')
    }
  }

  const selected = catalog.find((c) => c.type === type)

  return (
    <Card>
      <h2 className="mb-1 font-[family-name:var(--font-display)] text-lg font-semibold text-slate-100">✦ AI Providers</h2>
      <p className="mb-4 text-xs text-slate-500">
        Pick a provider and paste its API key — models, endpoints and rate-limit failover are built in.
        On a rate limit the platform switches models, then falls through to the next provider.
        When all keys are exhausted you will see a rate-limit warning here — paste a fresh key to rotate.
        OpenAI needs its own key (sk-…) under “OpenAI”; an OpenAI key will not work when saved as OpenRouter.
      </p>
      {health?.rateLimited && (
        <div className="mb-4 rounded-xl border border-amber-500/40 bg-amber-500/10 px-4 py-3 text-sm text-amber-100">
          <div className="font-semibold">Rotate key now</div>
          <div className="mt-1 text-xs text-amber-200/80">{health.message}</div>
        </div>
      )}
      <div className="grid gap-3 sm:grid-cols-2">
        <div>
          <Label>Provider</Label>
          <select value={type} onChange={(e) => setType(e.target.value)} className="w-full rounded-xl border border-edge bg-surface px-4 py-2.5 text-sm text-slate-200">
            {catalog.map((c) => <option key={c.type} value={c.type}>{c.displayName}</option>)}
          </select>
        </div>
        <div>
          <Label>API key (stored encrypted, write-only)</Label>
          <Input type="password" autoComplete="off" autoCorrect="off" spellCheck={false} value={apiKey} onChange={(e) => setApiKey(e.target.value)} placeholder="sk-…" />
        </div>
      </div>
      {selected && (
        <p className="mt-2 text-xs text-slate-500">Model chain: {selected.models.join(' → ')}</p>
      )}
      {error && <p className="mt-2 text-sm text-rose-400">{error}</p>}
      <Button className="mt-4" onClick={add} disabled={!type || !apiKey}>Save provider key</Button>

      <div className="mt-6 space-y-2">
        {providers === null ? <Spinner /> : providers.length === 0 ? (
          <Empty message="No providers configured yet. Add at least one API key to enable AI strategy generation." />
        ) : (
          providers.map((p) => (
            <div key={p.id} className="flex items-center justify-between rounded-xl border border-edge/60 bg-surface/50 px-4 py-3">
              <div>
                <div className="text-sm font-medium text-slate-200">{p.displayName}</div>
                <div className="text-xs text-slate-500">{p.models.join(' → ')}</div>
              </div>
              <div className="flex items-center gap-2">
                <button
                  onClick={() => api.put(`/api/v1/ai/providers/${p.id}`, { providerType: p.providerType, enabled: !p.enabled }).then(load)}
                  title="Toggle enabled"
                >
                  <Badge tone={p.enabled ? 'success' : 'default'}>{p.enabled ? 'ENABLED' : 'DISABLED'}</Badge>
                </button>
                <Button variant="danger" onClick={() => api.del(`/api/v1/ai/providers/${p.id}`).then(load)}>Delete</Button>
              </div>
            </div>
          ))
        )}
      </div>
    </Card>
  )
}

function Users() {
  const [users, setUsers] = useState<User[] | null>(null)
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [role, setRole] = useState('TRADER')
  const [error, setError] = useState('')

  const load = () => api.get<User[]>('/api/v1/admin/users').then(setUsers).catch(() => setUsers([]))
  useEffect(() => { load() }, [])

  async function add() {
    setError('')
    try {
      await api.post('/api/v1/admin/users', { email, password, displayName, role })
      setEmail(''); setPassword(''); setDisplayName('')
      load()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed')
    }
  }

  return (
    <Card delay={0.08}>
      <h2 className="mb-4 font-[family-name:var(--font-display)] text-lg font-semibold text-slate-100">Users</h2>
      <div className="grid gap-3 sm:grid-cols-2">
        <div><Label>Email</Label><Input value={email} onChange={(e) => setEmail(e.target.value)} /></div>
        <div><Label>Name</Label><Input value={displayName} onChange={(e) => setDisplayName(e.target.value)} /></div>
        <div><Label>Password</Label><Input type="password" value={password} onChange={(e) => setPassword(e.target.value)} /></div>
        <div>
          <Label>Role</Label>
          <select value={role} onChange={(e) => setRole(e.target.value)} className="w-full rounded-xl border border-edge bg-surface px-4 py-2.5 text-sm text-slate-200">
            <option value="TRADER">Trader</option>
            <option value="TENANT_ADMIN">Tenant Admin</option>
          </select>
        </div>
      </div>
      {error && <p className="mt-2 text-sm text-rose-400">{error}</p>}
      <Button className="mt-4" onClick={add} disabled={!email || !password}>Create user</Button>

      <div className="mt-6 space-y-2">
        {users === null ? <Spinner /> : users.map((u) => (
          <div key={u.id} className="flex items-center justify-between rounded-xl border border-edge/60 bg-surface/50 px-4 py-3">
            <div>
              <div className="text-sm font-medium text-slate-200">{u.displayName}</div>
              <div className="text-xs text-slate-500">{u.email}</div>
            </div>
            <div className="flex items-center gap-2">
              <Badge tone="info">{u.role}</Badge>
              <Badge tone={u.status === 'ACTIVE' ? 'success' : 'danger'}>{u.status}</Badge>
            </div>
          </div>
        ))}
      </div>
    </Card>
  )
}

function Tenants() {
  const [tenants, setTenants] = useState<Tenant[] | null>(null)
  const [name, setName] = useState('')
  const [slug, setSlug] = useState('')
  const [adminEmail, setAdminEmail] = useState('')
  const [adminPassword, setAdminPassword] = useState('')
  const [error, setError] = useState('')

  const load = () => api.get<Tenant[]>('/api/v1/admin/tenants').then(setTenants).catch(() => setTenants([]))
  useEffect(() => { load() }, [])

  async function add() {
    setError('')
    try {
      await api.post('/api/v1/admin/tenants', { name, slug, adminEmail, adminPassword, adminName: `${name} Admin` })
      setName(''); setSlug(''); setAdminEmail(''); setAdminPassword('')
      load()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed')
    }
  }

  return (
    <Card delay={0.16} className="xl:col-span-2">
      <h2 className="mb-4 font-[family-name:var(--font-display)] text-lg font-semibold text-slate-100">Tenants (Super Admin)</h2>
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <div><Label>Company name</Label><Input value={name} onChange={(e) => setName(e.target.value)} /></div>
        <div><Label>Slug</Label><Input value={slug} onChange={(e) => setSlug(e.target.value)} placeholder="acme" /></div>
        <div><Label>Admin email</Label><Input value={adminEmail} onChange={(e) => setAdminEmail(e.target.value)} /></div>
        <div><Label>Admin password</Label><Input type="password" value={adminPassword} onChange={(e) => setAdminPassword(e.target.value)} /></div>
      </div>
      {error && <p className="mt-2 text-sm text-rose-400">{error}</p>}
      <Button className="mt-4" onClick={add} disabled={!name || !slug || !adminEmail || !adminPassword}>Create tenant</Button>

      <div className="mt-6 grid gap-2 md:grid-cols-2">
        {tenants === null ? <Spinner /> : tenants.map((t) => (
          <div key={t.id} className="flex items-center justify-between rounded-xl border border-edge/60 bg-surface/50 px-4 py-3">
            <div>
              <div className="text-sm font-medium text-slate-200">{t.name}</div>
              <div className="text-xs text-slate-500">/{t.slug}</div>
            </div>
            <Badge tone={t.status === 'ACTIVE' ? 'success' : 'danger'}>{t.status}</Badge>
          </div>
        ))}
      </div>
    </Card>
  )
}
