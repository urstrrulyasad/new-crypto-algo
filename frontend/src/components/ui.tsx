import { motion, type HTMLMotionProps } from 'motion/react'
import type { ReactNode, InputHTMLAttributes } from 'react'

const ease = [0.22, 1, 0.36, 1] as const

export function PageShell({ children, className = '' }: { children: ReactNode; className?: string }) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 14 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.45, ease }}
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
      initial={{ opacity: 0, y: 22 }}
      whileInView={{ opacity: 1, y: 0 }}
      viewport={{ once: true, margin: '-40px' }}
      whileHover={hover ? { y: -2 } : undefined}
      transition={{ duration: 0.5, delay, ease }}
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
}: HTMLMotionProps<'button'> & { variant?: 'primary' | 'ghost' | 'danger' }) {
  const styles = {
    primary:
      'bg-gradient-to-r from-cyan-500 to-emerald-500 text-slate-950 font-semibold shadow-lg shadow-cyan-500/25',
    ghost: 'border border-edge text-slate-300 hover:border-cyan-500/50 hover:text-cyan-300',
    danger: 'bg-rose-500/15 border border-rose-500/40 text-rose-300 hover:bg-rose-500/25',
  }
  return (
    <motion.button
      whileHover={{ scale: disabled ? 1 : 1.02 }}
      whileTap={{ scale: disabled ? 1 : 0.97 }}
      transition={{ type: 'spring', stiffness: 420, damping: 28 }}
      disabled={disabled}
      className={`cursor-pointer rounded-xl px-4 py-2 text-sm transition-colors duration-200 disabled:pointer-events-none disabled:opacity-40 ${styles[variant]} ${className}`}
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
      className={`w-full rounded-xl border border-edge bg-surface/80 px-4 py-2.5 text-sm text-slate-200 placeholder-slate-500 outline-none transition-all duration-200 focus:border-cyan-500/60 focus:bg-surface focus:ring-2 focus:ring-cyan-500/15 ${props.className ?? ''}`}
    />
  )
}

export function Label({ children }: { children: ReactNode }) {
  return <label className="mb-1.5 block text-xs font-medium uppercase tracking-wider text-slate-400">{children}</label>
}

export function Stat({
  label,
  value,
  accent,
  delay = 0,
}: {
  label: string
  value: ReactNode
  accent?: 'up' | 'down' | 'neutral'
  delay?: number
}) {
  const color = accent === 'up' ? 'text-emerald-400' : accent === 'down' ? 'text-rose-400' : 'text-slate-100'
  return (
    <Card delay={delay} className="min-w-0 relative overflow-hidden">
      <div className="pointer-events-none absolute -right-6 -top-6 h-20 w-20 rounded-full bg-cyan-500/10 blur-2xl" />
      <div className="text-[11px] uppercase tracking-widest text-slate-400">{label}</div>
      <motion.div
        key={String(value)}
        initial={{ opacity: 0.4, y: 6 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.35, ease }}
        className={`mt-2 truncate font-[family-name:var(--font-display)] text-2xl font-semibold tabular-nums ${color}`}
      >
        {value}
      </motion.div>
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
    default: 'bg-slate-500/15 text-slate-300 border-slate-500/30',
    success: 'bg-emerald-500/15 text-emerald-300 border-emerald-500/30',
    warn: 'bg-amber-500/15 text-amber-300 border-amber-500/30',
    danger: 'bg-rose-500/15 text-rose-300 border-rose-500/30',
    info: 'bg-cyan-500/15 text-cyan-300 border-cyan-500/30',
  }
  return (
    <motion.span
      initial={{ opacity: 0, scale: 0.9 }}
      animate={{ opacity: 1, scale: 1 }}
      transition={{ duration: 0.25, ease }}
      className={`inline-flex items-center rounded-full border px-2.5 py-0.5 text-[11px] font-medium ${tones[tone]}`}
    >
      {children}
    </motion.span>
  )
}

export function PageTitle({ title, subtitle }: { title: string; subtitle?: string }) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 16 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.5, ease }}
      className="mb-6"
    >
      <h1 className="font-[family-name:var(--font-display)] text-2xl font-bold tracking-tight text-slate-100 sm:text-3xl">
        {title}
      </h1>
      {subtitle && (
        <motion.p
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          transition={{ delay: 0.12, duration: 0.45 }}
          className="mt-1.5 max-w-2xl text-sm leading-relaxed text-slate-400"
        >
          {subtitle}
        </motion.p>
      )}
    </motion.div>
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
    info: 'border-cyan-500/25 bg-cyan-500/5 text-cyan-100/90',
    warn: 'border-amber-500/30 bg-amber-500/5 text-amber-200/90',
    success: 'border-emerald-500/30 bg-emerald-500/5 text-emerald-100/90',
  }
  return (
    <motion.div
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.4, ease }}
      className={`mb-5 rounded-xl border px-4 py-3 text-sm leading-relaxed ${tones[tone]}`}
    >
      {children}
    </motion.div>
  )
}

export function Spinner() {
  return (
    <div className="flex justify-center py-12">
      <motion.div
        animate={{ rotate: 360 }}
        transition={{ repeat: Infinity, duration: 0.85, ease: 'linear' }}
        className="h-8 w-8 rounded-full border-2 border-cyan-400/25 border-t-cyan-400"
      />
    </div>
  )
}

export function Empty({ message }: { message: string }) {
  return (
    <motion.div
      initial={{ opacity: 0, scale: 0.98 }}
      animate={{ opacity: 1, scale: 1 }}
      transition={{ duration: 0.4, ease }}
      className="py-14 text-center"
    >
      <div className="mx-auto mb-3 h-10 w-10 rounded-2xl border border-edge bg-surface/60" />
      <p className="text-sm text-slate-500">{message}</p>
    </motion.div>
  )
}
