import { Card, PageShell, PageTitle } from '@/components/ui'

export default function ComingSoon({ title }: { title: string }) {
  return (
    <PageShell>
      <PageTitle title={title} subtitle="Options vertical — scheduled module" />
      <Card hover={false} className="mx-auto mt-10 max-w-lg px-8 py-14 text-center">
        <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-xl border border-amber-500/35 bg-amber-500/12 font-[family-name:var(--font-display)] text-lg font-bold text-amber-300">
          O
        </div>
        <h2 className="font-[family-name:var(--font-display)] text-2xl font-semibold text-slate-100">
          Coming Soon
        </h2>
        <p className="mt-2.5 text-sm leading-relaxed text-slate-400">
          Options strategies and paper trading are staged for a future release. INR Futures execution is live.
        </p>
      </Card>
    </PageShell>
  )
}
