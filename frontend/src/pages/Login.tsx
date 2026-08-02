import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { motion } from 'motion/react'
import { login } from '@/lib/api'
import { Button, Input, Label } from '@/components/ui'

const ease = [0.22, 1, 0.36, 1] as const

export default function Login() {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const navigate = useNavigate()

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setLoading(true)
    setError('')
    try {
      await login(email, password)
      navigate('/')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Login failed')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="relative flex min-h-dvh items-center justify-center overflow-hidden px-4 py-10">
      <div className="grid-bg pointer-events-none absolute inset-0" />
      <div className="aurora pointer-events-none absolute inset-0" />
      <div className="orb animate-float left-[6%] top-[10%] h-[28rem] w-[28rem] bg-cyan-600/55" />
      <div className="orb animate-float-delay bottom-[4%] right-[8%] h-96 w-96 bg-emerald-600/45" />
      <div className="noise-overlay" />

      <motion.div
        initial={{ opacity: 0, y: 36, scale: 0.97 }}
        animate={{ opacity: 1, y: 0, scale: 1 }}
        transition={{ duration: 0.7, ease }}
        className="relative z-10 w-full max-w-md"
      >
        <motion.div
          initial="hidden"
          animate="show"
          variants={{
            hidden: {},
            show: { transition: { staggerChildren: 0.08 } },
          }}
          className="mb-8 text-center"
        >
          <motion.div
            variants={{
              hidden: { opacity: 0, scale: 0.7, y: 12 },
              show: { opacity: 1, scale: 1, y: 0 },
            }}
            transition={{ type: 'spring', stiffness: 320, damping: 22 }}
            className="shimmer mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-2xl bg-gradient-to-br from-cyan-500 to-emerald-500 text-2xl font-bold text-slate-950 shadow-2xl shadow-cyan-500/35"
          >
            Q
          </motion.div>
          <motion.h1
            variants={{
              hidden: { opacity: 0, y: 12 },
              show: { opacity: 1, y: 0 },
            }}
            transition={{ duration: 0.5, ease }}
            className="font-[family-name:var(--font-display)] text-4xl font-bold tracking-tight text-slate-100 sm:text-5xl"
          >
            Quant<span className="gradient-text">DCX</span>
          </motion.h1>
          <motion.p
            variants={{
              hidden: { opacity: 0, y: 8 },
              show: { opacity: 1, y: 0 },
            }}
            className="mx-auto mt-3 max-w-sm text-sm leading-relaxed text-slate-400"
          >
            AI-driven crypto strategies · CoinDCX execution · Backtested first
          </motion.p>
        </motion.div>

        <motion.div
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.2, duration: 0.55, ease }}
          className="glass rounded-3xl p-7 shadow-2xl shadow-black/50 sm:p-8"
        >
          <form onSubmit={submit} className="space-y-5">
            <div>
              <Label>Email</Label>
              <Input
                type="email"
                required
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="you@company.com"
                autoComplete="email"
              />
            </div>
            <div>
              <Label>Password</Label>
              <Input
                type="password"
                required
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="••••••••"
                autoComplete="current-password"
              />
            </div>
            {error && (
              <motion.p
                initial={{ opacity: 0, y: -6 }}
                animate={{ opacity: 1, y: 0 }}
                className="rounded-xl border border-rose-500/30 bg-rose-500/10 px-3 py-2 text-sm text-rose-300"
              >
                {error}
              </motion.p>
            )}
            <Button type="submit" disabled={loading} className="w-full py-3 text-base">
              {loading ? 'Signing in…' : 'Sign in'}
            </Button>
          </form>
        </motion.div>

        <motion.p
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          transition={{ delay: 0.55 }}
          className="mt-6 text-center text-xs text-slate-500"
        >
          Multi-tenant platform — ask your admin for an account.
        </motion.p>
      </motion.div>
    </div>
  )
}
