import { useCallback, useEffect, useState } from 'react'
import { api, currentUser } from '@/lib/api'
import { useToast } from '@/lib/toast'
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
      <PageTitle title="Administration" subtitle="AI provider failover chain, tenant users, and multi-tenant isolation" />
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
      <p className="mt-1 font-mono-num text-xs text-amber-200/70">
        {health.recentRateLimitEvents} event(s) in the last 2h
        {health.lastAt ? ` · last ${new Date(health.lastAt).toLocaleString()}` : ''}
      </p>
    </Callout>
  )
}

function Providers() {
  const { pushToast } = useToast()
  const [providers, setProviders] = useState<Provider[] | null>(null)
  const [catalog, setCatalog] = useState<CatalogEntry[]>([])
  const [type, setType] = useState('')
  const [apiKey, setApiKey] = useState('')
  const [error, setError] = useState('')
  const [health, setHealth] = useState<AiHealth | null>(null)

  const load = useCallback(() => {
    api.get<Provider[]>('/api/v1/ai/providers').then(setProviders).catch(() => setProviders([]))
    api.get<AiHealth>('/api/v1/ai/health').then(setHealth).catch(() => setHealth(null))
  }, [])

  useEffect(() => {
    load()
    api
      .get<CatalogEntry[]>('/api/v1/ai/providers/catalog')
      .then((c) => {
        setCatalog(c)
        setType((prev) => prev || c[0]?.type || '')
      })
      .catch(() => setCatalog([]))
    const t = setInterval(load, 20_000)
    return () => clearInterval(t)
  }, [load])

  async function add() {
    setError('')
    try {
      await api.post('/api/v1/ai/providers', { providerType: type, apiKey })
      pushToast({ title: `Saved ${type} provider key`, tone: 'success' })
      setApiKey('')
      load()
    } catch (e) {
      const msg = e instanceof Error ? e.message : 'Failed'
      setError(msg)
      pushToast({ title: 'Failed to save provider', detail: msg, tone: 'error' })
    }
  }

  const selected = catalog.find((c) => c.type === type)

  return (
    <Card hover={false}>
      <h2 className="mb-1 font-[family-name:var(--font-display)] text-lg font-semibold text-slate-100">
        AI Providers
      </h2>
      <p className="mb-4 text-xs leading-relaxed text-slate-400">
        Configure provider keys for automatic model fallback and strategy synthesis.
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
          <select
            value={type}
            onChange={(e) => setType(e.target.value)}
            className="w-full rounded-xl border border-edge bg-surface px-3.5 py-2.5 text-sm text-slate-200 outline-none focus:border-amber-500/60"
          >
            {catalog.map((c) => (
              <option key={c.type} value={c.type}>
                {c.displayName}
              </option>
            ))}
          </select>
        </div>
        <div>
          <Label>API key (write-only)</Label>
          <Input
            type="password"
            autoComplete="off"
            autoCorrect="off"
            spellCheck={false}
            value={apiKey}
            onChange={(e) => setApiKey(e.target.value)}
            placeholder="sk-…"
          />
        </div>
      </div>
      {selected && (
        <p className="mt-2 font-mono-num text-xs text-slate-500">
          Chain: {selected.models.join(' → ')}
        </p>
      )}
      {error && <p className="mt-2 text-sm text-rose-400">{error}</p>}
      <Button className="mt-4" onClick={add} disabled={!type || !apiKey}>
        Save provider key
      </Button>

      <div className="mt-6 space-y-2">
        {providers === null ? (
          <Spinner />
        ) : providers.length === 0 ? (
          <Empty message="No providers configured yet." />
        ) : (
          providers.map((p) => (
            <div
              key={p.id}
              className="flex items-center justify-between gap-3 rounded-xl border border-edge bg-surface/65 px-4 py-3"
            >
              <div className="min-w-0">
                <div className="text-sm font-medium text-slate-100">{p.displayName}</div>
                <div className="truncate font-mono-num text-xs text-slate-500">{p.models.join(' → ')}</div>
              </div>
              <div className="flex shrink-0 items-center gap-2">
                <button
                  type="button"
                  onClick={() =>
                    api
                      .put(`/api/v1/ai/providers/${p.id}`, {
                        providerType: p.providerType,
                        enabled: !p.enabled,
                      })
                      .then(() => {
                        pushToast({
                          title: `${p.displayName} ${!p.enabled ? 'enabled' : 'disabled'}`,
                          tone: 'info',
                        })
                        load()
                      })
                  }
                  className="cursor-pointer"
                  title="Toggle enabled"
                >
                  <Badge tone={p.enabled ? 'success' : 'default'}>
                    {p.enabled ? 'ENABLED' : 'DISABLED'}
                  </Badge>
                </button>
                <Button
                  variant="danger"
                  onClick={() =>
                    api.del(`/api/v1/ai/providers/${p.id}`).then(() => {
                      pushToast({ title: `Removed ${p.displayName}`, tone: 'info' })
                      load()
                    })
                  }
                  className="px-3 py-1 text-xs"
                >
                  Delete
                </Button>
              </div>
            </div>
          ))
        )}
      </div>
    </Card>
  )
}

function Users() {
  const { pushToast } = useToast()
  const [users, setUsers] = useState<User[] | null>(null)
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [role, setRole] = useState('TRADER')
  const [error, setError] = useState('')

  const load = useCallback(() => {
    api.get<User[]>('/api/v1/admin/users').then(setUsers).catch(() => setUsers([]))
  }, [])
  useEffect(() => {
    load()
  }, [load])

  async function add() {
    setError('')
    try {
      await api.post('/api/v1/admin/users', { email, password, displayName, role })
      pushToast({ title: `User created: ${email}`, tone: 'success' })
      setEmail('')
      setPassword('')
      setDisplayName('')
      load()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed')
    }
  }

  return (
    <Card delay={0.06} hover={false}>
      <h2 className="mb-4 font-[family-name:var(--font-display)] text-lg font-semibold text-slate-100">
        Users
      </h2>
      <div className="grid gap-3 sm:grid-cols-2">
        <div>
          <Label>Email</Label>
          <Input value={email} onChange={(e) => setEmail(e.target.value)} />
        </div>
        <div>
          <Label>Name</Label>
          <Input value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
        </div>
        <div>
          <Label>Password</Label>
          <Input type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
        </div>
        <div>
          <Label>Role</Label>
          <select
            value={role}
            onChange={(e) => setRole(e.target.value)}
            className="w-full rounded-xl border border-edge bg-surface px-3.5 py-2.5 text-sm text-slate-200 outline-none focus:border-amber-500/60"
          >
            <option value="TRADER">Trader</option>
            <option value="TENANT_ADMIN">Tenant Admin</option>
          </select>
        </div>
      </div>
      {error && <p className="mt-2 text-sm text-rose-400">{error}</p>}
      <Button className="mt-4" onClick={add} disabled={!email || !password}>
        Create user
      </Button>

      <div className="mt-6 space-y-2">
        {users === null ? (
          <Spinner />
        ) : (
          users.map((u) => (
            <div
              key={u.id}
              className="flex items-center justify-between gap-2 rounded-xl border border-edge bg-surface/65 px-4 py-3"
            >
              <div className="min-w-0">
                <div className="truncate text-sm font-medium text-slate-100">{u.displayName}</div>
                <div className="truncate font-mono-num text-xs text-slate-500">{u.email}</div>
              </div>
              <div className="flex shrink-0 items-center gap-2">
                <Badge tone="info">{u.role}</Badge>
                <Badge tone={u.status === 'ACTIVE' ? 'success' : 'danger'}>{u.status}</Badge>
              </div>
            </div>
          ))
        )}
      </div>
    </Card>
  )
}

function Tenants() {
  const { pushToast } = useToast()
  const [tenants, setTenants] = useState<Tenant[] | null>(null)
  const [name, setName] = useState('')
  const [slug, setSlug] = useState('')
  const [adminEmail, setAdminEmail] = useState('')
  const [adminPassword, setAdminPassword] = useState('')
  const [error, setError] = useState('')

  const load = useCallback(() => {
    api.get<Tenant[]>('/api/v1/admin/tenants').then(setTenants).catch(() => setTenants([]))
  }, [])
  useEffect(() => {
    load()
  }, [load])

  async function add() {
    setError('')
    try {
      await api.post('/api/v1/admin/tenants', {
        name,
        slug,
        adminEmail,
        adminPassword,
        adminName: `${name} Admin`,
      })
      pushToast({ title: `Tenant "${name}" provisioned`, tone: 'success' })
      setName('')
      setSlug('')
      setAdminEmail('')
      setAdminPassword('')
      load()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed')
    }
  }

  return (
    <Card delay={0.12} hover={false} className="xl:col-span-2">
      <h2 className="mb-4 font-[family-name:var(--font-display)] text-lg font-semibold text-slate-100">
        Tenants (Super Admin)
      </h2>
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <div>
          <Label>Company name</Label>
          <Input value={name} onChange={(e) => setName(e.target.value)} />
        </div>
        <div>
          <Label>Slug</Label>
          <Input value={slug} onChange={(e) => setSlug(e.target.value)} placeholder="acme" />
        </div>
        <div>
          <Label>Admin email</Label>
          <Input value={adminEmail} onChange={(e) => setAdminEmail(e.target.value)} />
        </div>
        <div>
          <Label>Admin password</Label>
          <Input type="password" value={adminPassword} onChange={(e) => setAdminPassword(e.target.value)} />
        </div>
      </div>
      {error && <p className="mt-2 text-sm text-rose-400">{error}</p>}
      <Button className="mt-4" onClick={add} disabled={!name || !slug || !adminEmail || !adminPassword}>
        Create tenant
      </Button>

      <div className="mt-6 grid gap-2 md:grid-cols-2">
        {tenants === null ? (
          <Spinner />
        ) : (
          tenants.map((t) => (
            <div
              key={t.id}
              className="flex items-center justify-between rounded-xl border border-edge bg-surface/65 px-4 py-3"
            >
              <div>
                <div className="text-sm font-medium text-slate-100">{t.name}</div>
                <div className="font-mono-num text-xs text-slate-500">/{t.slug}</div>
              </div>
              <Badge tone={t.status === 'ACTIVE' ? 'success' : 'danger'}>{t.status}</Badge>
            </div>
          ))
        )}
      </div>
    </Card>
  )
}
