import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { hasSession } from '@/lib/api'
import Layout from '@/components/Layout'
import Login from '@/pages/Login'
import Dashboard from '@/pages/Dashboard'
import FuturesStrategies from '@/pages/FuturesStrategies'
import FuturesPaper from '@/pages/FuturesPaper'
import ComingSoon from '@/pages/ComingSoon'
import Settings from '@/pages/Settings'
import Admin from '@/pages/Admin'

function Protected({ children }: { children: React.ReactNode }) {
  return hasSession() ? <>{children}</> : <Navigate to="/login" replace />
}

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route
          element={
            <Protected>
              <Layout />
            </Protected>
          }
        >
          <Route path="/" element={<Dashboard />} />
          <Route path="/futures/strategies" element={<FuturesStrategies />} />
          <Route path="/futures/paper" element={<FuturesPaper />} />
          <Route path="/options/strategies" element={<ComingSoon title="Options Strategies" />} />
          <Route path="/options/paper" element={<ComingSoon title="Options Paper Trade" />} />
          <Route path="/settings" element={<Settings />} />
          <Route path="/admin" element={<Admin />} />
          <Route path="/strategies" element={<Navigate to="/futures/strategies" replace />} />
          <Route path="/bots" element={<Navigate to="/futures/paper" replace />} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  )
}
