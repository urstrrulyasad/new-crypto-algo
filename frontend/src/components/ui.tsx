import { motion } from 'motion/react'
import type { ReactNode, ButtonHTMLAttributes, InputHTMLAttributes } from 'react'

export function Card({ children, className = '', delay = 0 }: { children: ReactNode; className?: string; delay?: number }) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 24 }}
      whileInView={{ opacity: 1, y: 0 }}
      viewport={{ once: true, margin: '-40px' }}
      transition={{ duration: 0.55, delay, ease: [0.22, 1, 0.36, 1] }}
      className={`glass rounded-2xl p-5 ${className}`}
    >
      {children}
    </motion.div>
  )
}

export function Button({
  children,
  variant = 'primary',
  className = '',
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary' | 'ghost' | 'danger' }) {
  const styles = {
    primary:
      'bg-gradient-to-r from-cyan-500 to-emerald-500 text-slate-950 font-semibold shadow-lg shadow-cyan-500/20 hover:shadow-cyan-400/40 hover:brightness-110',
    ghost: 'border border-edge text-slate-300 hover:border-cyan-500/50 hover:text-cyan-300',
    danger: 'bg-rose-500/15 border border-rose-500/40 text-rose-300 hover:bg-rose-500/25',
  }
  return (
    <button
      className={`rounded-xl px-4 py-2 text-sm transition-all duration-200 active:scale-[0.97] disabled:opacity-40 disabled:pointer-events-none cursor-pointer ${styles[variant]} ${className}`}
      {...props}
    >
      {children}
    </button>
  )
}

export function Input(props: InputHTMLAttributes<HTMLInputElement>) {
  return (
    <input
      {...props}
      className={`w-full rounded-xl bg-surface border border-edge px-4 py-2.5 text-sm text-slate-200 placeholder-slate-500 outline-none transition-all duration-200 focus:border-cyan-500/60 focus:ring-2 focus:ring-cyan-500/15 ${props.className ?? ''}`}
    />
  )
}

export function Label({ children }: { children: ReactNode }) {
  return <label className="mb-1.5 block text-xs font-medium uppercase tracking-wider text-slate-400">{children}</label>
}

export function Stat({ label, value, accent, delay = 0 }: { label: string; value: ReactNode; accent?: 'up' | 'down' | 'neutral'; delay?: number }) {
  const color = accent === 'up' ? 'text-emerald-400' : accent === 'down' ? 'text-rose-400' : 'text-slate-100'
  return (
    <Card delay={delay} className="min-w-0">
      <div className="text-xs uppercase tracking-widest text-slate-400">{label}</div>
      <div className={`mt-2 truncate font-[family-name:var(--font-display)] text-2xl font-semibold ${color}`}>{value}</div>
    </Card>
  )
}

export function Badge({ children, tone = 'default' }: { children: ReactNode; tone?: 'default' | 'success' | 'warn' | 'danger' | 'info' }) {
  const tones = {
    default: 'bg-slate-500/15 text-slate-300 border-slate-500/30',
    success: 'bg-emerald-500/15 text-emerald-300 border-emerald-500/30',
    warn: 'bg-amber-500/15 text-amber-300 border-amber-500/30',
    danger: 'bg-rose-500/15 text-rose-300 border-rose-500/30',
    info: 'bg-cyan-500/15 text-cyan-300 border-cyan-500/30',
  }
  return (
    <span className={`inline-flex items-center rounded-full border px-2.5 py-0.5 text-[11px] font-medium ${tones[tone]}`}>
      {children}
    </span>
  )
}

export function PageTitle({ title, subtitle }: { title: string; subtitle?: string }) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 16 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.5, ease: [0.22, 1, 0.36, 1] }}
      className="mb-6"
    >
      <h1 className="font-[family-name:var(--font-display)] text-2xl font-bold text-slate-100 md:text-3xl">{title}</h1>
      {subtitle && <p className="mt-1 text-sm text-slate-400">{subtitle}</p>}
    </motion.div>
  )
}

export function Spinner() {
  return (
    <div className="flex justify-center py-10">
      <div className="h-8 w-8 animate-spin rounded-full border-2 border-cyan-400/30 border-t-cyan-400" />
    </div>
  )
}

export function Empty({ message }: { message: string }) {
  return (
    <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} className="py-12 text-center text-sm text-slate-500">
      {message}
    </motion.div>
  )
}
