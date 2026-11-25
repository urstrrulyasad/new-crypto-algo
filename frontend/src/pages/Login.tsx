import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { motion } from 'motion/react'
import { login } from '@/lib/api'
import { useToast } from '@/lib/toast'
import { TickerStrip } from '@/components/TickerStrip'
import { IconEye } from '@/components/Icons'
import { Button, Input, Label } from '@/components/ui'

const ease = [0.16, 1, 0.3, 1] as const

export default function Login() {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const navigate = useNavigate()
  const { pushToast } = useToast()

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setLoading(true)
    setError('')
    try {
      const user = await login(email, password)
      pushToast({
        title: `Signed in as ${user.displayName || user.email}`,
        detail: `Role: ${user.role}`,
        tone: 'success',
      })
      navigate('/')
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Login failed'
      setError(msg)
      pushToast({ title: 'Sign-in failed', detail: msg, tone: 'error' })
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="relative flex min-h-dvh flex-col items-center justify-center overflow-hidden px-4 py-10">
      <div className="grid-bg pointer-events-none absolute inset-0" />
      <div className="aurora pointer-events-none absolute inset-0" />

      <motion.div
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.4, ease }}
        className="relative z-10 w-full max-w-md"
      >
        <div className="mb-7 text-center">
          <div className="shimmer mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-2xl bg-gradient-to-br from-amber-300 via-amber-400 to-emerald-400 font-[family-name:var(--font-display)] text-2xl font-bold text-zinc-950 shadow-lg shadow-amber-500/20">
            Q
          </div>
          <h1 className="font-[family-name:var(--font-display)] text-3xl font-bold tracking-tight text-slate-100 sm:text-4xl">
            Quant<span className="gradient-text">DCX</span>
          </h1>
          <p className="mx-auto mt-2 max-w-sm text-sm text-slate-400">
            INR Futures · Strategy Backtesting · Live Execution Desk
          </p>
        </div>

        <TickerStrip interactive={false} />

        <div className="glass rounded-2xl p-6 shadow-2xl shadow-black/50 sm:p-8">
          <form onSubmit={submit} className="space-y-4">
            <div>
              <Label>Email</Label>
              <Input
                type="email"
                required
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="admin@platform.local"
                autoComplete="email"
              />
            </div>
            <div>
              <Label>Password</Label>
              <div className="relative">
                <Input
                  type={showPassword ? 'text' : 'password'}
                  required
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="••••••••"
                  autoComplete="current-password"
                  className="pr-10"
                />
                <button
                  type="button"
                  onClick={() => setShowPassword((v) => !v)}
                  aria-label={showPassword ? 'Hide password' : 'Show password'}
                  className="absolute right-3 top-1/2 -translate-y-1/2 cursor-pointer text-slate-400 transition-colors hover:text-slate-200"
                >
                  <IconEye off={showPassword} className="h-4 w-4" />
                </button>
              </div>
            </div>
            {error && (
              <motion.p
                initial={{ opacity: 0, y: -4 }}
                animate={{ opacity: 1, y: 0 }}
                className="rounded-xl border border-rose-500/35 bg-rose-500/10 px-3 py-2 text-sm text-rose-300"
              >
                {error}
              </motion.p>
            )}
            <Button type="submit" disabled={loading} className="w-full py-2.5 text-sm">
              {loading ? 'Signing in…' : 'Sign in'}
            </Button>
          </form>

          <div className="mt-5 border-t border-edge pt-4 text-center text-xs text-slate-500">
            Encrypted session · CoinDCX INR Futures Desk
          </div>
        </div>
      </motion.div>
    </div>
  )
}
