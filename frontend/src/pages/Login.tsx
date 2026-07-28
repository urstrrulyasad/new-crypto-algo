import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { motion } from 'motion/react'
import { login } from '@/lib/api'
import { Button, Input, Label } from '@/components/ui'

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
    <div className="relative flex min-h-screen items-center justify-center overflow-hidden px-4">
      <div className="grid-bg pointer-events-none absolute inset-0" />
      <div className="orb animate-float left-[8%] top-[12%] h-[28rem] w-[28rem] bg-cyan-600/60" />
      <div className="orb animate-float-delay bottom-[5%] right-[10%] h-96 w-96 bg-emerald-600/50" />
      <div className="orb animate-float right-[30%] top-[-10%] h-72 w-72 bg-violet-600/40" />

      <motion.div
        initial={{ opacity: 0, y: 40, scale: 0.96 }}
        animate={{ opacity: 1, y: 0, scale: 1 }}
        transition={{ duration: 0.7, ease: [0.22, 1, 0.36, 1] }}
        className="relative z-10 w-full max-w-md"
      >
        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          transition={{ delay: 0.2, duration: 0.8 }}
          className="mb-8 text-center"
        >
          <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-2xl bg-gradient-to-br from-cyan-500 to-emerald-500 text-2xl font-bold text-slate-950 shadow-2xl shadow-cyan-500/30">
            Q
          </div>
          <h1 className="font-[family-name:var(--font-display)] text-4xl font-bold text-slate-100">
            Quant<span className="gradient-text">DCX</span>
          </h1>
          <p className="mt-2 text-sm text-slate-400">AI-driven crypto strategies · CoinDCX execution · Backtested first</p>
        </motion.div>

        <div className="glass rounded-3xl p-8 shadow-2xl shadow-black/40">
          <form onSubmit={submit} className="space-y-5">
            <div>
              <Label>Email</Label>
              <Input type="email" required value={email} onChange={(e) => setEmail(e.target.value)} placeholder="you@company.com" />
            </div>
            <div>
              <Label>Password</Label>
              <Input type="password" required value={password} onChange={(e) => setPassword(e.target.value)} placeholder="••••••••" />
            </div>
            {error && (
              <motion.p initial={{ opacity: 0, y: -6 }} animate={{ opacity: 1, y: 0 }} className="text-sm text-rose-400">
                {error}
              </motion.p>
            )}
            <Button type="submit" disabled={loading} className="w-full py-3">
              {loading ? 'Signing in…' : 'Sign in'}
            </Button>
          </form>
        </div>

        <motion.p
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          transition={{ delay: 0.5 }}
          className="mt-6 text-center text-xs text-slate-500"
        >
          Multi-tenant platform — ask your admin for an account.
        </motion.p>
      </motion.div>
    </div>
  )
}
