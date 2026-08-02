import { motion } from 'motion/react'
import { PageShell, PageTitle } from '@/components/ui'

export default function ComingSoon({ title }: { title: string }) {
  return (
    <PageShell>
      <PageTitle title={title} subtitle="Options vertical — coming soon" />
      <motion.div
        initial={{ opacity: 0, y: 20, scale: 0.98 }}
        animate={{ opacity: 1, y: 0, scale: 1 }}
        transition={{ duration: 0.55, ease: [0.22, 1, 0.36, 1] }}
        className="glass relative mx-auto mt-10 max-w-lg overflow-hidden rounded-3xl px-8 py-16 text-center"
      >
        <div className="pointer-events-none absolute inset-0 aurora opacity-70" />
        <motion.div
          animate={{ scale: [1, 1.06, 1], opacity: [0.5, 0.85, 0.5] }}
          transition={{ repeat: Infinity, duration: 3.2, ease: 'easeInOut' }}
          className="mx-auto mb-5 h-14 w-14 rounded-2xl bg-gradient-to-br from-cyan-500/40 to-emerald-500/30 ring-1 ring-cyan-400/30"
        />
        <div className="relative font-[family-name:var(--font-display)] text-3xl font-semibold text-slate-100">
          Coming Soon
        </div>
        <p className="relative mt-3 text-sm leading-relaxed text-slate-400">
          Options strategies and paper trading are not available yet. Futures INR is fully live.
        </p>
      </motion.div>
    </PageShell>
  )
}
