import { useEffect, useState } from 'react'
import { motion } from 'motion/react'
import { api } from '@/lib/api'
import { Button, Callout, Card, PageShell, PageTitle, Spinner } from '@/components/ui'

type Settings = { enabled: boolean; mode: 'PAPER' | 'LIVE'; instruments: { asString?: () => string } | string; timeframe: string; stakeAmount: number; maxOpenTrades: number; cooldownSeconds: number; dailyLossLimit: number; killSwitch: boolean; lastDecision?: string; lastHeartbeat?: string }

export default function Scalper() {
  const [data, setData] = useState<Settings | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const load = () => api.get<Settings>('/api/v1/scalper').then(setData).catch((e) => setError(e.message))
  useEffect(() => { load(); const t = window.setInterval(load, 10000); return () => window.clearInterval(t) }, [])
  if (!data) return <PageShell><PageTitle title="Automatic Scalper" subtitle="Cost-aware, independently controlled futures automation" /><Spinner /></PageShell>
  const active = data.enabled && !data.killSwitch
  const toggle = async (enabled: boolean) => {
    setBusy(true); setError('')
    try { setData(await api.put<Settings>('/api/v1/scalper', { enabled })) } catch (e) { setError(e instanceof Error ? e.message : 'Unable to update scalper') } finally { setBusy(false) }
  }
  return <PageShell>
    <PageTitle title="Automatic Scalper" subtitle="24×7 paper-first controls for every configured futures instrument" />
    <Callout tone="warn">Live mode remains gated by the backend risk controls. The scalper fails closed when data is stale, reconciliation is unhealthy, or the kill switch is active.</Callout>
    {error && <Callout tone="danger">{error}</Callout>}
    <div className="grid gap-4 lg:grid-cols-[1.3fr_1fr]">
      <Card className="overflow-hidden">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div><p className="text-xs uppercase tracking-[0.18em] text-slate-500">Runtime status</p><h2 className="mt-2 text-2xl font-semibold text-slate-100">{active ? 'Monitoring markets' : 'Disabled'}</h2><p className="mt-1 text-sm text-slate-400">{data.mode} mode · {data.timeframe} candles</p></div>
          <motion.div animate={{ scale: active ? [1, 1.08, 1] : 1 }} transition={{ repeat: active ? Infinity : 0, duration: 2 }} className={`rounded-full px-3 py-1 text-xs font-semibold ${active ? 'bg-emerald-400/15 text-emerald-300 ring-1 ring-emerald-400/30' : 'bg-slate-400/10 text-slate-400 ring-1 ring-slate-500/30'}`}>{active ? 'ACTIVE' : 'OFF'}</motion.div>
        </div>
        <div className="mt-8 flex flex-wrap gap-3"><Button disabled={busy || active} onClick={() => toggle(true)}>Enable paper scalper</Button><Button disabled={busy || !active} variant="danger" onClick={() => toggle(false)}>Disable entries</Button><Button disabled={busy || data.killSwitch} variant="ghost" onClick={() => api.post<Settings>('/api/v1/scalper/kill-switch?enabled=true').then(setData).catch((e) => setError(e.message))}>Emergency kill switch</Button></div>
      </Card>
      <Card><p className="text-xs uppercase tracking-[0.18em] text-slate-500">Guardrails</p><div className="mt-4 grid grid-cols-2 gap-3 text-sm"><Metric label="Max positions" value={data.maxOpenTrades} /><Metric label="Cooldown" value={`${data.cooldownSeconds}s`} /><Metric label="Daily loss cap" value={data.dailyLossLimit} /><Metric label="Stake amount" value={data.stakeAmount} /></div><p className="mt-5 text-xs text-slate-500">Last decision: {data.lastDecision ?? 'No decisions yet'}</p></Card>
    </div>
  </PageShell>
}
function Metric({ label, value }: { label: string; value: string | number }) { return <div className="rounded-xl bg-surface/70 p-3"><div className="text-xs text-slate-500">{label}</div><div className="mt-1 font-semibold text-slate-200">{value}</div></div> }
