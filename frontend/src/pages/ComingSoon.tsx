import { PageTitle } from '@/components/ui'

export default function ComingSoon({ title }: { title: string }) {
  return (
    <div>
      <PageTitle title={title} subtitle="Options vertical — coming soon" />
      <div className="glass mx-auto mt-10 max-w-lg rounded-2xl px-8 py-16 text-center">
        <div className="font-[family-name:var(--font-display)] text-3xl font-semibold text-slate-100">
          Coming Soon
        </div>
        <p className="mt-3 text-sm text-slate-400">
          Options strategies and paper trading are not available yet. Futures INR is fully live.
        </p>
      </div>
    </div>
  )
}
