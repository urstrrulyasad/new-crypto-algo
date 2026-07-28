const BASE = import.meta.env.VITE_API_URL ?? ''

let accessToken: string | null = localStorage.getItem('accessToken')
let refreshToken: string | null = localStorage.getItem('refreshToken')

export interface UserInfo {
  id: string
  tenantId: string
  email: string
  displayName?: string
  role: string
}

export function setTokens(access: string | null, refresh: string | null) {
  accessToken = access
  refreshToken = refresh
  if (access) localStorage.setItem('accessToken', access)
  else localStorage.removeItem('accessToken')
  if (refresh) localStorage.setItem('refreshToken', refresh)
  else localStorage.removeItem('refreshToken')
}

export function hasSession() {
  return !!accessToken
}

async function request<T>(path: string, options: RequestInit = {}, retry = true): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(options.headers as Record<string, string>),
  }
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`
  const res = await fetch(`${BASE}${path}`, { ...options, headers })
  if (res.status === 401 && retry && refreshToken) {
    const ok = await tryRefresh()
    if (ok) return request<T>(path, options, false)
    setTokens(null, null)
    window.location.href = '/login'
  }
  if (!res.ok) {
    const body = await res.json().catch(() => ({ message: res.statusText }))
    throw new Error(body.message ?? `Request failed (${res.status})`)
  }
  if (res.status === 204) return undefined as T
  const text = await res.text()
  return text ? (JSON.parse(text) as T) : (undefined as T)
}

async function tryRefresh(): Promise<boolean> {
  try {
    const res = await fetch(`${BASE}/api/v1/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
    })
    if (!res.ok) return false
    const data = await res.json()
    setTokens(data.accessToken, data.refreshToken)
    return true
  } catch {
    return false
  }
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) }),
  put: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'PUT', body: JSON.stringify(body) }),
  patch: <T>(path: string) => request<T>(path, { method: 'PATCH' }),
  del: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
}

export async function login(email: string, password: string): Promise<UserInfo> {
  const data = await request<{ accessToken: string; refreshToken: string; user: UserInfo }>(
    '/api/v1/auth/login',
    { method: 'POST', body: JSON.stringify({ email, password }) },
    false,
  )
  setTokens(data.accessToken, data.refreshToken)
  localStorage.setItem('user', JSON.stringify(data.user))
  return data.user
}

export function currentUser(): UserInfo | null {
  const raw = localStorage.getItem('user')
  return raw ? (JSON.parse(raw) as UserInfo) : null
}

export function logout() {
  setTokens(null, null)
  localStorage.removeItem('user')
}
