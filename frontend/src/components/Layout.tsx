import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { AnimatePresence, motion } from 'motion/react'
import { useEffect, useState } from 'react'
import { currentUser, logout } from '@/lib/api'

type NavItem = { to: string; label: string; end?: boolean }
type NavGroup = { label: string; items: NavItem[] }

const top: NavItem[] = [{ to: '/', label: 'Dashboard', end: true }]

const groups: NavGroup[] = [
  {
    label: 'Futures',
    items: [
      { to: '/futures/strategies', label: 'Strategies' },
      { to: '/futures/paper', label: 'Paper Trade' },
      { to: '/futures/coins', label: 'Coins' },
    ],
  },
  {
    label: 'Options',
    items: [
      { to: '/options/strategies', label: 'Strategies' },
      { to: '/options/paper', label: 'Paper Trade' },
    ],
  },
]

const bottom: NavItem[] = [{ to: '/settings', label: 'API Keys' }]
const adminNav: NavItem[] = [{ to: '/admin', label: 'Admin' }]

const ease = [0.22, 1, 0.36, 1] as const

export default function Layout() {
  const user = currentUser()
  const navigate = useNavigate()
  const location = useLocation()
  const [open, setOpen] = useState(false)
  const isAdmin = user?.role === 'SUPER_ADMIN' || user?.role === 'TENANT_ADMIN'

  useEffect(() => {
    setOpen(false)
  }, [location.pathname])

  useEffect(() => {
    document.body.style.overflow = open ? 'hidden' : ''
    return () => {
      document.body.style.overflow = ''
    }
  }, [open])

  const renderNav = (navId: string, showBrand: boolean) => (
    <>
      {showBrand && (
        <div className="hidden lg:block">
          <Brand />
        </div>
      )}
      <nav className="mt-8 flex flex-1 flex-col gap-5 overflow-y-auto pr-1">
        <div className="flex flex-col gap-1">
          {top.map((item) => (
            <SideLink key={item.to} item={item} navId={navId} onClick={() => setOpen(false)} />
          ))}
        </div>
        {groups.map((g) => (
          <div key={g.label}>
            <div className="mb-1.5 px-4 text-[10px] font-semibold uppercase tracking-[0.18em] text-slate-500">
              {g.label}
            </div>
            <div className="flex flex-col gap-1">
              {g.items.map((item) => (
                <SideLink key={item.to} item={item} navId={navId} onClick={() => setOpen(false)} />
              ))}
            </div>
          </div>
        ))}
        <div className="flex flex-col gap-1">
          {bottom.map((item) => (
            <SideLink key={item.to} item={item} navId={navId} onClick={() => setOpen(false)} />
          ))}
          {isAdmin &&
            adminNav.map((item) => (
              <SideLink key={item.to} item={item} navId={navId} onClick={() => setOpen(false)} />
            ))}
        </div>
      </nav>
      <div className="mt-auto border-t border-edge/80 pt-4">
        <div className="flex items-center gap-3">
          <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-cyan-500/30 to-emerald-500/20 text-sm font-semibold text-cyan-200 ring-1 ring-cyan-500/30">
            {(user?.displayName ?? user?.email ?? '?').slice(0, 1).toUpperCase()}
          </div>
          <div className="min-w-0">
            <div className="truncate text-sm font-medium text-slate-200">
              {user?.displayName ?? user?.email}
            </div>
            <div className="truncate text-[11px] uppercase tracking-wider text-slate-500">{user?.role}</div>
          </div>
        </div>
        <motion.button
          whileHover={{ scale: 1.01 }}
          whileTap={{ scale: 0.98 }}
          onClick={() => {
            logout()
            navigate('/login')
          }}
          className="mt-3 w-full rounded-xl border border-edge py-2 text-xs text-slate-400 transition-colors hover:border-rose-500/40 hover:text-rose-300"
        >
          Sign out
        </motion.button>
      </div>
    </>
  )

  return (
    <div className="relative min-h-dvh overflow-x-hidden">
      <div className="grid-bg pointer-events-none fixed inset-0" />
      <div className="aurora pointer-events-none fixed inset-0" />
      <div className="orb animate-float left-[-12%] top-[-12%] h-[26rem] w-[26rem] bg-cyan-600/45" />
      <div className="orb animate-float-delay right-[-8%] top-[28%] h-80 w-80 bg-emerald-600/35" />
      <div className="noise-overlay" />

      <div className="glass sticky top-0 z-40 flex items-center justify-between px-4 py-3 lg:hidden">
        <Brand compact />
        <motion.button
          whileTap={{ scale: 0.94 }}
          onClick={() => setOpen((v) => !v)}
          aria-label="Toggle menu"
          className="relative flex h-10 w-10 items-center justify-center rounded-xl border border-edge text-slate-300"
        >
          <span className="sr-only">Menu</span>
          <div className="flex w-5 flex-col gap-1.5">
            <motion.span
              animate={open ? { rotate: 45, y: 6 } : { rotate: 0, y: 0 }}
              className="block h-0.5 w-5 origin-center bg-current"
            />
            <motion.span
              animate={open ? { opacity: 0, scaleX: 0 } : { opacity: 1, scaleX: 1 }}
              className="block h-0.5 w-5 bg-current"
            />
            <motion.span
              animate={open ? { rotate: -45, y: -6 } : { rotate: 0, y: 0 }}
              className="block h-0.5 w-5 origin-center bg-current"
            />
          </div>
        </motion.button>
      </div>

      <div className="relative z-10 flex">
        <motion.aside
          initial={{ x: -28, opacity: 0 }}
          animate={{ x: 0, opacity: 1 }}
          transition={{ duration: 0.55, ease }}
          className="glass sticky top-0 z-30 hidden h-dvh w-64 shrink-0 flex-col p-5 lg:flex"
        >
          {renderNav('desktop', true)}
        </motion.aside>

        <AnimatePresence>
          {open && (
            <>
              <motion.div
                key="scrim"
                initial={{ opacity: 0 }}
                animate={{ opacity: 1 }}
                exit={{ opacity: 0 }}
                transition={{ duration: 0.25 }}
                className="fixed inset-0 z-40 bg-black/65 backdrop-blur-sm lg:hidden"
                onClick={() => setOpen(false)}
              />
              <motion.aside
                key="drawer"
                initial={{ x: '-100%' }}
                animate={{ x: 0 }}
                exit={{ x: '-100%' }}
                transition={{ type: 'spring', stiffness: 380, damping: 34 }}
                className="glass fixed inset-y-0 left-0 z-50 flex w-[min(18rem,88vw)] flex-col p-5 lg:hidden"
              >
                <div className="mb-2">
                  <Brand />
                </div>
                {renderNav('mobile', false)}
              </motion.aside>
            </>
          )}
        </AnimatePresence>

        <main className="safe-pad min-w-0 flex-1 py-5 md:py-7 lg:py-8">
          <AnimatePresence mode="wait">
            <motion.div
              key={location.pathname}
              initial={{ opacity: 0, y: 12, filter: 'blur(4px)' }}
              animate={{ opacity: 1, y: 0, filter: 'blur(0px)' }}
              exit={{ opacity: 0, y: -8, filter: 'blur(2px)' }}
              transition={{ duration: 0.35, ease }}
            >
              <Outlet />
            </motion.div>
          </AnimatePresence>
        </main>
      </div>
    </div>
  )
}

function SideLink({
  item,
  onClick,
  navId,
}: {
  item: NavItem
  onClick: () => void
  navId: string
}) {
  return (
    <NavLink to={item.to} end={item.end} onClick={onClick} className="relative block">
      {({ isActive }) => (
        <span
          className={`relative z-10 flex items-center rounded-xl px-4 py-2.5 text-sm font-medium transition-colors duration-200 ${
            isActive ? 'text-cyan-200' : 'text-slate-400 hover:text-slate-200'
          }`}
        >
          {isActive && (
            <motion.span
              layoutId={`nav-active-${navId}`}
              className="absolute inset-0 -z-10 rounded-xl bg-gradient-to-r from-cyan-500/20 to-emerald-500/10 shadow-inner ring-1 ring-cyan-500/25"
              transition={{ type: 'spring', stiffness: 380, damping: 32 }}
            />
          )}
          {item.label}
        </span>
      )}
    </NavLink>
  )
}

function Brand({ compact = false }: { compact?: boolean }) {
  return (
    <div className="flex items-center gap-2.5">
      <motion.div
        whileHover={{ rotate: [0, -6, 6, 0], scale: 1.05 }}
        transition={{ duration: 0.45 }}
        className={`flex items-center justify-center rounded-xl bg-gradient-to-br from-cyan-500 to-emerald-500 font-bold text-slate-950 shadow-lg shadow-cyan-500/25 ${
          compact ? 'h-8 w-8 text-sm' : 'h-9 w-9'
        }`}
      >
        Q
      </motion.div>
      <div>
        <div className="font-[family-name:var(--font-display)] text-lg font-bold leading-none text-slate-100">
          Quant<span className="gradient-text">DCX</span>
        </div>
        {!compact && <div className="mt-0.5 text-[10px] uppercase tracking-[0.18em] text-slate-500">INR Futures</div>}
      </div>
    </div>
  )
}
