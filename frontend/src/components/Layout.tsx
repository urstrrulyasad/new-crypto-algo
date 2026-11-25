import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { AnimatePresence, motion } from 'motion/react'
import { useEffect, useState, type ReactNode } from 'react'
import { currentUser, logout } from '@/lib/api'
import {
  IconCoins,
  IconDashboard,
  IconKey,
  IconPulseChart,
  IconShield,
  IconStrategy,
} from '@/components/Icons'

type NavItem = { to: string; label: string; icon: ReactNode; end?: boolean }
type NavGroup = { label: string; items: NavItem[] }

const groups: NavGroup[] = [
  {
    label: 'Real Trades (Live)',
    items: [
      { to: '/', label: 'Live Trades', icon: <IconDashboard />, end: true },
    ],
  },
  {
    label: 'Paper Trading',
    items: [
      { to: '/futures/paper', label: 'Paper Trades', icon: <IconPulseChart /> },
      { to: '/options/paper', label: 'Options Paper', icon: <IconPulseChart /> },
    ],
  },
  {
    label: 'Market & Engine',
    items: [
      { to: '/futures/strategies', label: 'Strategies', icon: <IconStrategy /> },
      { to: '/futures/coins', label: 'Coins', icon: <IconCoins /> },
      { to: '/options/strategies', label: 'Options Strategies', icon: <IconStrategy /> },
    ],
  },
]

const bottom: NavItem[] = [{ to: '/settings', label: 'API Keys', icon: <IconKey /> }]
const adminNav: NavItem[] = [{ to: '/admin', label: 'Admin', icon: <IconShield /> }]

const ease = [0.16, 1, 0.3, 1] as const

export default function Layout() {
  const user = currentUser()
  const navigate = useNavigate()
  const location = useLocation()
  const [openForPath, setOpenForPath] = useState<string | null>(null)
  const open = openForPath === location.pathname
  const isAdmin = user?.role === 'SUPER_ADMIN' || user?.role === 'TENANT_ADMIN'

  useEffect(() => {
    document.body.style.overflow = open ? 'hidden' : ''
    return () => {
      document.body.style.overflow = ''
    }
  }, [open])

  const closeDrawer = () => setOpenForPath(null)

  const renderNav = (navId: string, showBrand: boolean) => (
    <>
      {showBrand && (
        <div className="hidden lg:block">
          <Brand />
        </div>
      )}
      <nav className="mt-7 flex flex-1 flex-col gap-5 overflow-y-auto pr-1">
        {groups.map((g) => (
          <div key={g.label}>
            <div className="mb-1.5 px-3 text-[10px] font-semibold uppercase tracking-[0.16em] text-slate-500">
              {g.label}
            </div>
            <div className="flex flex-col gap-1">
              {g.items.map((item) => (
                <SideLink key={item.to} item={item} navId={navId} onClick={closeDrawer} />
              ))}
            </div>
          </div>
        ))}
        <div className="flex flex-col gap-1">
          <div className="mb-1.5 px-3 text-[10px] font-semibold uppercase tracking-[0.16em] text-slate-500">
            Configuration
          </div>
          {bottom.map((item) => (
            <SideLink key={item.to} item={item} navId={navId} onClick={closeDrawer} />
          ))}
          {isAdmin &&
            adminNav.map((item) => (
              <SideLink key={item.to} item={item} navId={navId} onClick={closeDrawer} />
            ))}
        </div>
      </nav>
      <div className="mt-auto border-t border-edge pt-4">
        <div className="flex items-center gap-3">
          <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl border border-amber-500/35 bg-amber-500/15 font-[family-name:var(--font-display)] text-sm font-semibold text-amber-200">
            {(user?.displayName ?? user?.email ?? '?').slice(0, 1).toUpperCase()}
          </div>
          <div className="min-w-0">
            <div className="truncate text-sm font-medium text-slate-200">
              {user?.displayName ?? user?.email}
            </div>
            <div className="truncate font-mono-num text-[10px] uppercase tracking-wider text-slate-500">
              {user?.role}
            </div>
          </div>
        </div>
        <button
          type="button"
          onClick={() => {
            logout()
            navigate('/login')
          }}
          className="mt-3 w-full cursor-pointer rounded-xl border border-edge bg-surface/60 py-2 text-xs font-medium text-slate-400 transition-colors hover:border-rose-500/40 hover:text-rose-300"
        >
          Sign out
        </button>
      </div>
    </>
  )

  return (
    <div className="relative min-h-dvh overflow-x-hidden">
      <div className="grid-bg pointer-events-none fixed inset-0" />
      <div className="aurora pointer-events-none fixed inset-0" />

      <div className="glass sticky top-0 z-40 flex items-center justify-between border-x-0 border-t-0 px-4 py-3 lg:hidden">
        <Brand compact />
        <button
          type="button"
          onClick={() => setOpenForPath((prev) => (prev === location.pathname ? null : location.pathname))}
          aria-label="Toggle menu"
          className="relative flex h-9 w-9 items-center justify-center rounded-xl border border-edge text-slate-300"
        >
          <span className="sr-only">Menu</span>
          <div className="flex w-4 flex-col gap-1">
            <motion.span
              animate={open ? { rotate: 45, y: 6 } : { rotate: 0, y: 0 }}
              className="block h-0.5 w-4 origin-center bg-current"
            />
            <motion.span
              animate={open ? { opacity: 0, scaleX: 0 } : { opacity: 1, scaleX: 1 }}
              className="block h-0.5 w-4 bg-current"
            />
            <motion.span
              animate={open ? { rotate: -45, y: -6 } : { rotate: 0, y: 0 }}
              className="block h-0.5 w-4 origin-center bg-current"
            />
          </div>
        </button>
      </div>

      <div className="relative z-10 flex">
        <aside className="glass sticky top-0 z-30 hidden h-dvh w-64 shrink-0 flex-col border-y-0 border-l-0 p-5 lg:flex">
          {renderNav('desktop', true)}
        </aside>

        <AnimatePresence>
          {open && (
            <>
              <motion.div
                key="scrim"
                initial={{ opacity: 0 }}
                animate={{ opacity: 1 }}
                exit={{ opacity: 0 }}
                transition={{ duration: 0.2 }}
                className="fixed inset-0 z-40 bg-black/75 backdrop-blur-sm lg:hidden"
                onClick={closeDrawer}
              />
              <motion.aside
                key="drawer"
                initial={{ x: '-100%' }}
                animate={{ x: 0 }}
                exit={{ x: '-100%' }}
                transition={{ type: 'spring', stiffness: 400, damping: 36 }}
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

        <main className="safe-pad min-w-0 flex-1 overflow-x-hidden py-5 md:py-7 lg:py-8">
          <AnimatePresence mode="wait">
            <motion.div
              key={location.pathname}
              initial={{ opacity: 0, y: 6 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -4 }}
              transition={{ duration: 0.18, ease }}
              className="mx-auto w-full max-w-[1400px]"
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
          className={`relative z-10 flex items-center gap-2.5 rounded-xl px-3 py-2 text-sm font-medium transition-colors duration-150 ${
            isActive ? 'text-amber-200' : 'text-slate-400 hover:text-slate-100'
          }`}
        >
          {isActive && (
            <motion.span
              layoutId={`nav-active-${navId}`}
              className="absolute inset-0 -z-10 rounded-xl border border-amber-500/35 bg-amber-500/12"
              transition={{ type: 'spring', stiffness: 420, damping: 34 }}
            />
          )}
          <span className={isActive ? 'text-amber-400' : 'text-slate-500'}>{item.icon}</span>
          {item.label}
        </span>
      )}
    </NavLink>
  )
}

function Brand({ compact = false }: { compact?: boolean }) {
  return (
    <div className="flex items-center gap-2.5">
      <div
        className={`flex items-center justify-center rounded-xl bg-gradient-to-br from-amber-300 via-amber-400 to-emerald-400 font-[family-name:var(--font-display)] font-bold text-zinc-950 shadow-sm shadow-amber-500/20 ${
          compact ? 'h-8 w-8 text-sm' : 'h-9 w-9'
        }`}
      >
        Q
      </div>
      <div>
        <div className="font-[family-name:var(--font-display)] text-lg font-bold leading-none text-slate-100">
          Quant<span className="gradient-text">DCX</span>
        </div>
        {!compact && (
          <div className="mt-1 font-mono-num text-[10px] uppercase tracking-[0.16em] text-slate-500">
            INR Futures Desk
          </div>
        )}
      </div>
    </div>
  )
}
