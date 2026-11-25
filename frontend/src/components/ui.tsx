import { motion, type HTMLMotionProps } from 'motion/react'
import type { ReactNode, InputHTMLAttributes } from 'react'

const ease = [0.16, 1, 0.3, 1] as const

export function PageShell({ children, className = '' }: { children: ReactNode; className?: string }) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.28, ease }}
      className={`pb-8 ${className}`}
    >
      {children}
    </motion.div>
  )
}

export function Card({
  children,
  className = '',
  delay = 0,
  hover = true,
}: {
  children: ReactNode
  className?: string
  delay?: number
  hover?: boolean
}) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.32, delay, ease }}
      className={`glass rounded-2xl p-5 ${hover ? 'glass-hover' : ''} ${className}`}
    >
      {children}
    </motion.div>
  )
}

export function Button({
  children,
  variant = 'primary',
  className = '',
  disabled,
  ...props
}: HTMLMotionProps<'button'> & { variant?: 'primary' | 'ghost' | 'danger' | 'subtle' }) {
  const styles = {
    primary:
      'bg-gradient-to-r from-amber-400 to-amber-500 text-zinc-950 font-semibold shadow-sm shadow-amber-500/20 hover:from-amber-300 hover:to-amber-400',
    ghost: 'border border-edge bg-surface/70 text-slate-300 hover:border-amber-500/45 hover:text-amber-200',
    subtle: 'bg-white/[0.04] border border-white/10 text-slate-200 hover:border-amber-500/30 hover:bg-white/[0.07]',
    danger: 'bg-rose-500/12 border border-rose-500/35 text-rose-300 hover:bg-rose-500/20 hover:border-rose-500/50',
  }
  return (
    <motion.button
      whileTap={{ scale: disabled ? 1 : 0.98 }}
      transition={{ duration: 0.12 }}
      disabled={disabled}
      className={`inline-flex cursor-pointer items-center justify-center gap-2 whitespace-nowrap rounded-xl px-4 py-2 text-sm transition-colors duration-150 disabled:pointer-events-none disabled:opacity-40 ${styles[variant]} ${className}`}
      {...props}
    >
      {children}
    </motion.button>
  )
}

export function Input(props: InputHTMLAttributes<HTMLInputElement>) {
  return (
    <input
      {...props}
      className={`w-full rounded-xl border border-edge bg-surface/90 px-3.5 py-2.5 text-sm text-slate-100 placeholder-slate-500 outline-none transition-colors duration-150 focus:border-amber-500/60 focus:bg-surface focus:ring-2 focus:ring-amber-500/15 ${props.className ?? ''}`}
    />
  )
}

export function Label({ children }: { children: ReactNode }) {
  return (
    <label className="mb-1.5 block text-[11px] font-medium uppercase tracking-[0.14em] text-slate-400">
      {children}
    </label>
  )
}

export function Stat({
  label,
  value,
  accent,
  delay = 0,
  sub,
}: {
  label: string
  value: ReactNode
  accent?: 'up' | 'down' | 'gold' | 'neutral'
  delay?: number
  sub?: string
}) {
  const color =
    accent === 'up'
      ? 'text-emerald-400'
      : accent === 'down'
        ? 'text-rose-400'
        : accent === 'gold'
          ? 'text-amber-300'
          : 'text-slate-100'
  return (
    <Card delay={delay} hover={false} className="min-w-0 p-4">
      <div className="text-[11px] font-medium uppercase tracking-[0.12em] text-slate-400">{label}</div>
      <motion.div
        key={String(value)}
        initial={{ opacity: 0.5, y: 4 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.2, ease }}
        className={`mt-1.5 truncate font-mono-num text-xl font-semibold tracking-tight sm:text-2xl ${color}`}
      >
        {value}
      </motion.div>
      {sub && <div className="mt-1 truncate text-[11px] text-slate-500">{sub}</div>}
    </Card>
  )
}

export function Badge({
  children,
  tone = 'default',
}: {
  children: ReactNode
  tone?: 'default' | 'success' | 'warn' | 'danger' | 'info'
}) {
  const tones = {
    default: 'bg-slate-500/10 text-slate-300 border-slate-500/25',
    success: 'bg-emerald-500/12 text-emerald-300 border-emerald-500/30',
    warn: 'bg-amber-500/12 text-amber-300 border-amber-500/30',
    danger: 'bg-rose-500/12 text-rose-300 border-rose-500/30',
    info: 'bg-amber-400/12 text-amber-200 border-amber-400/30',
  }
  return (
    <span
      className={`inline-flex items-center whitespace-nowrap rounded-md border px-2 py-0.5 font-mono-num text-[11px] font-medium uppercase tracking-wide ${tones[tone]}`}
    >
      {children}
    </span>
  )
}

export function PageTitle({ title, subtitle }: { title: string; subtitle?: string }) {
  return (
    <div className="mb-6">
      <h1 className="font-[family-name:var(--font-display)] text-2xl font-bold tracking-tight text-slate-100 sm:text-3xl">
        {title}
      </h1>
      {subtitle && (
        <p className="mt-1 max-w-2xl text-sm leading-relaxed text-slate-400">{subtitle}</p>
      )}
    </div>
  )
}

export function Callout({
  children,
  tone = 'info',
}: {
  children: ReactNode
  tone?: 'info' | 'warn' | 'success'
}) {
  const tones = {
    info: 'border-amber-500/25 bg-amber-500/[0.04] text-amber-100/90',
    warn: 'border-amber-500/30 bg-amber-500/[0.06] text-amber-100',
    success: 'border-emerald-500/25 bg-emerald-500/[0.05] text-emerald-100/90',
  }
  return (
    <div className={`mb-5 rounded-xl border px-4 py-3 text-sm leading-relaxed ${tones[tone]}`}>
      {children}
    </div>
  )
}

export function Spinner() {
  return (
    <div className="flex justify-center py-12">
      <motion.div
        animate={{ rotate: 360 }}
        transition={{ repeat: Infinity, duration: 0.8, ease: 'linear' }}
        className="h-7 w-7 rounded-full border-2 border-amber-400/20 border-t-amber-400"
      />
    </div>
  )
}

export function Empty({ message }: { message: string }) {
  return (
    <div className="py-12 text-center">
      <div className="mx-auto mb-3 flex h-10 w-10 items-center justify-center rounded-xl border border-edge bg-surface/60 text-slate-600">
        —
      </div>
      <p className="text-sm text-slate-500">{message}</p>
    </div>
  )
}
