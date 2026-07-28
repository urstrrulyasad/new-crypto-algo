import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { motion } from 'motion/react'
import { useState } from 'react'
import { currentUser, logout } from '@/lib/api'

const nav = [
  { to: '/', label: 'Dashboard', icon: '◈' },
  { to: '/strategies', label: 'Strategy Lab', icon: '⌬' },
  { to: '/bots', label: 'Bots', icon: '⚙' },
  { to: '/settings', label: 'API Keys', icon: '🔑' },
]

const adminNav = [{ to: '/admin', label: 'Admin', icon: '⛨' }]

export default function Layout() {
  const user = currentUser()
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)
  const isAdmin = user?.role === 'SUPER_ADMIN' || user?.role === 'TENANT_ADMIN'
  const items = isAdmin ? [...nav, ...adminNav] : nav

  return (
    <div className="relative min-h-screen">
      <div className="grid-bg pointer-events-none fixed inset-0" />
      <div className="orb animate-float left-[-10%] top-[-10%] h-96 w-96 bg-cyan-600/50" />
      <div className="orb animate-float-delay right-[-5%] top-[30%] h-80 w-80 bg-emerald-600/40" />

      {/* mobile top bar */}
      <div className="glass sticky top-0 z-40 flex items-center justify-between px-4 py-3 lg:hidden">
        <Brand />
        <button onClick={() => setOpen(!open)} className="rounded-lg border border-edge p-2 text-slate-300">
          <span className="block h-0.5 w-5 bg-current" />
          <span className="mt-1 block h-0.5 w-5 bg-current" />
          <span className="mt-1 block h-0.5 w-5 bg-current" />
        </button>
      </div>

      <div className="relative z-10 flex">
        {/* sidebar */}
        <motion.aside
          initial={{ x: -40, opacity: 0 }}
          animate={{ x: 0, opacity: 1 }}
          transition={{ duration: 0.5, ease: [0.22, 1, 0.36, 1] }}
          className={`glass fixed inset-y-0 left-0 z-50 w-64 flex-col p-5 transition-transform duration-300 lg:sticky lg:top-0 lg:flex lg:h-screen lg:translate-x-0 ${open ? 'flex translate-x-0' : 'hidden -translate-x-full lg:flex'}`}
        >
          <div className="hidden lg:block">
            <Brand />
          </div>
          <nav className="mt-8 flex flex-1 flex-col gap-1.5">
            {items.map((item, i) => (
              <motion.div
                key={item.to}
                initial={{ opacity: 0, x: -12 }}
                animate={{ opacity: 1, x: 0 }}
                transition={{ delay: 0.1 + i * 0.06 }}
              >
                <NavLink
                  to={item.to}
                  end={item.to === '/'}
                  onClick={() => setOpen(false)}
                  className={({ isActive }) =>
                    `flex items-center gap-3 rounded-xl px-4 py-2.5 text-sm font-medium transition-all duration-200 ${
                      isActive
                        ? 'bg-gradient-to-r from-cyan-500/20 to-emerald-500/10 text-cyan-300 shadow-inner'
                        : 'text-slate-400 hover:bg-white/5 hover:text-slate-200'
                    }`
                  }
                >
                  <span className="text-base">{item.icon}</span>
                  {item.label}
                </NavLink>
              </motion.div>
            ))}
          </nav>
          <div className="mt-auto border-t border-edge pt-4">
            <div className="truncate text-sm font-medium text-slate-200">{user?.displayName ?? user?.email}</div>
            <div className="text-xs text-slate-500">{user?.role}</div>
            <button
              onClick={() => {
                logout()
                navigate('/login')
              }}
              className="mt-3 w-full rounded-xl border border-edge py-2 text-xs text-slate-400 transition-colors hover:border-rose-500/40 hover:text-rose-300"
            >
              Sign out
            </button>
          </div>
        </motion.aside>

        {open && <div className="fixed inset-0 z-40 bg-black/60 lg:hidden" onClick={() => setOpen(false)} />}

        <main className="min-w-0 flex-1 px-4 py-6 md:px-8 lg:px-10">
          <Outlet />
        </main>
      </div>
    </div>
  )
}

function Brand() {
  return (
    <div className="flex items-center gap-2.5">
      <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-gradient-to-br from-cyan-500 to-emerald-500 font-bold text-slate-950">
        Q
      </div>
      <div>
        <div className="font-[family-name:var(--font-display)] text-lg font-bold leading-none text-slate-100">
          Quant<span className="gradient-text">DCX</span>
        </div>
        <div className="text-[10px] uppercase tracking-widest text-slate-500">AI Trading</div>
      </div>
    </div>
  )
}
