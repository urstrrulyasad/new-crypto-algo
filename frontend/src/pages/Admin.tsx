import { useEffect, useState } from 'react'
import { api, currentUser } from '@/lib/api'
import { Badge, Button, Card, Empty, Input, Label, PageTitle, Spinner } from '@/components/ui'

interface Provider {
  id: string
  providerType: string
  name: string
  baseUrl: string
  model: string
  enabled: boolean
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

const PROVIDER_PRESETS: Record<string, { baseUrl: string; model: string }> = {
  ANTHROPIC: { baseUrl: 'https://api.anthropic.com', model: 'claude-sonnet-4-5' },
  GEMINI: { baseUrl: 'https://generativelanguage.googleapis.com', model: 'gemini-2.5-pro' },
  GROK: { baseUrl: 'https://api.x.ai', model: 'grok-4' },
  OPENAI_COMPATIBLE: { baseUrl: 'https://api.openai.com', model: 'gpt-5' },
}

export default function Admin() {
  const me = currentUser()
  return (
    <div>
      <PageTitle title="Admin" subtitle="AI provider configuration, users and tenants" />
      <div className="grid gap-6 xl:grid-cols-2">
        <Providers />
        <Users />
        {me?.role === 'SUPER_ADMIN' && <Tenants />}
      </div>
    </div>
  )
}

function Providers() {
  const [providers, setProviders] = useState<Provider[] | null>(null)
  const [type, setType] = useState('ANTHROPIC')
  const [name, setName] = useState('')
  const [baseUrl, setBaseUrl] = useState(PROVIDER_PRESETS.ANTHROPIC.baseUrl)
  const [model, setModel] = useState(PROVIDER_PRESETS.ANTHROPIC.model)
  const [apiKey, setApiKey] = useState('')
  const [error, setError] = useState('')

  const load = () => api.get<Provider[]>('/api/v1/ai/providers').then(setProviders).catch(() => setProviders([]))
  useEffect(() => { load() }, [])

  function pickType(t: string) {
    setType(t)
    setBaseUrl(PROVIDER_PRESETS[t].baseUrl)
    setModel(PROVIDER_PRESETS[t].model)
  }

  async function add() {
    setError('')
    try {
      await api.post('/api/v1/ai/providers', { providerType: type, name, baseUrl, model, apiKey })
      setName(''); setApiKey('')
      load()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed')
    }
  }

  return (
    <Card>
      <h2 className="mb-4 font-[family-name:var(--font-display)] text-lg font-semibold text-slate-100">✦ AI Providers</h2>
      <div className="grid gap-3 sm:grid-cols-2">
        <div>
          <Label>Provider</Label>
          <select value={type} onChange={(e) => pickType(e.target.value)} className="w-full rounded-xl border border-edge bg-surface px-4 py-2.5 text-sm text-slate-200">
            <option value="ANTHROPIC">Claude (Anthropic)</option>
            <option value="GEMINI">Gemini (Google)</option>
            <option value="GROK">Grok (xAI)</option>
            <option value="OPENAI_COMPATIBLE">OpenAI-compatible</option>
          </select>
        </div>
        <div>
          <Label>Display name</Label>
          <Input value={name} onChange={(e) => setName(e.target.value)} placeholder="Claude prod" />
        </div>
        <div>
          <Label>Base URL</Label>
          <Input value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} />
        </div>
        <div>
          <Label>Model</Label>
          <Input value={model} onChange={(e) => setModel(e.target.value)} />
        </div>
        <div className="sm:col-span-2">
          <Label>API key (stored encrypted, write-only)</Label>
          <Input type="password" value={apiKey} onChange={(e) => setApiKey(e.target.value)} placeholder="sk-…" />
        </div>
      </div>
      {error && <p className="mt-2 text-sm text-rose-400">{error}</p>}
      <Button className="mt-4" onClick={add} disabled={!name || !apiKey}>Add provider</Button>

      <div className="mt-6 space-y-2">
        {providers === null ? <Spinner /> : providers.length === 0 ? (
          <Empty message="No providers configured yet." />
        ) : (
          providers.map((p) => (
            <div key={p.id} className="flex items-center justify-between rounded-xl border border-edge/60 bg-surface/50 px-4 py-3">
              <div>
                <div className="text-sm font-medium text-slate-200">{p.name}</div>
                <div className="text-xs text-slate-500">{p.providerType} · {p.model}</div>
              </div>
              <div className="flex items-center gap-2">
                <Badge tone={p.enabled ? 'success' : 'default'}>{p.enabled ? 'ENABLED' : 'DISABLED'}</Badge>
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
