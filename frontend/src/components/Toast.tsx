import { useCallback, useState, type ReactNode } from 'react'
import { AnimatePresence, motion } from 'motion/react'
import { IconAlert, IconCheck, IconSpark, IconX } from '@/components/Icons'
import { ToastContext, type ToastInput, type ToastTone } from '@/lib/toast'

interface ToastItem {
  id: string
  title: string
  detail?: string
  tone: ToastTone
}

const TONE_STYLES: Record<ToastTone, { border: string; badge: string; icon: typeof IconCheck }> = {
  success: {
    border: 'border-emerald-500/35 bg-[#0b1817]/95 text-emerald-100',
    badge: 'text-emerald-400',
    icon: IconCheck,
  },
  error: {
    border: 'border-rose-500/40 bg-[#1a0d13]/95 text-rose-100',
    badge: 'text-rose-400',
    icon: IconAlert,
  },
  warn: {
    border: 'border-amber-500/35 bg-[#18140b]/95 text-amber-100',
    badge: 'text-amber-400',
    icon: IconAlert,
  },
  info: {
    border: 'border-amber-500/35 bg-[#15130d]/95 text-amber-100',
    badge: 'text-amber-400',
    icon: IconSpark,
  },
}

export default function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([])

  const dismiss = useCallback((id: string) => {
    setItems((prev) => prev.filter((t) => t.id !== id))
  }, [])

  const pushToast = useCallback(({ title, detail, tone = 'info' }: ToastInput) => {
    const id = `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`
    setItems((prev) => [{ id, title, detail, tone }, ...prev].slice(0, 4))
    setTimeout(() => {
      setItems((prev) => prev.filter((t) => t.id !== id))
    }, 4500)
  }, [])

  return (
    <ToastContext.Provider value={{ pushToast }}>
      {children}
      <div
        aria-live="polite"
        className="pointer-events-none fixed bottom-4 right-4 z-[70] flex w-[min(24rem,calc(100vw-2rem))] flex-col gap-2"
      >
        <AnimatePresence initial={false}>
          {items.map((t) => {
            const style = TONE_STYLES[t.tone]
            const Icon = style.icon
            return (
              <motion.div
                key={t.id}
                layout
                initial={{ opacity: 0, y: 16, scale: 0.96 }}
                animate={{ opacity: 1, y: 0, scale: 1 }}
                exit={{ opacity: 0, y: 8, scale: 0.96 }}
                transition={{ duration: 0.2, ease: [0.16, 1, 0.3, 1] }}
                className={`pointer-events-auto flex items-start gap-3 rounded-xl border px-3.5 py-3 shadow-2xl shadow-black/50 backdrop-blur-md ${style.border}`}
              >
                <span className={`mt-0.5 shrink-0 ${style.badge}`}>
                  <Icon className="h-4 w-4" />
                </span>
                <div className="min-w-0 flex-1">
                  <div className="text-sm font-medium leading-snug">{t.title}</div>
                  {t.detail && (
                    <div className="mt-0.5 text-xs leading-relaxed text-slate-300/80">{t.detail}</div>
                  )}
                </div>
                <button
                  type="button"
                  onClick={() => dismiss(t.id)}
                  aria-label="Dismiss notification"
                  className="shrink-0 rounded-md p-1 text-slate-400 transition-colors hover:bg-white/5 hover:text-slate-200"
                >
                  <IconX className="h-3.5 w-3.5" />
                </button>
              </motion.div>
            )
          })}
        </AnimatePresence>
      </div>
    </ToastContext.Provider>
  )
}
