import type { ReactNode } from 'react'
import { formatDateTime } from '@/lib/datetime'
import { Badge } from '@/components/ui'

/** Compact meta row — always visible on narrow screens. */
export function DateMeta({
  openedAt,
  closedAt,
  orderAt,
}: {
  openedAt?: string | null
  closedAt?: string | null
  orderAt?: string | null
}) {
  return (
    <div className="mt-2 space-y-0.5 font-mono-num text-[11px] leading-relaxed text-slate-400">
      {orderAt != null && (
        <div>
          <span className="text-slate-500">Order · </span>
          <span className="text-slate-300">{formatDateTime(orderAt)}</span>
        </div>
      )}
      {openedAt != null && (
        <div>
          <span className="text-slate-500">Opened · </span>
          <span className="text-slate-300">{formatDateTime(openedAt)}</span>
        </div>
      )}
      {closedAt !== undefined && (
        <div>
          <span className="text-slate-500">Closed · </span>
          <span className="text-slate-300">{formatDateTime(closedAt)}</span>
        </div>
      )}
    </div>
  )
}

export function OrderCard({
  pair,
  side,
  status,
  quantity,
  detail,
  createdAt,
  tone,
  onClick,
}: {
  pair: string
  side: string
  status: string
  quantity: number | string
  detail: ReactNode
  createdAt?: string | null
  tone: 'danger' | 'success' | 'warn' | 'default' | 'info'
  onClick?: () => void
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="w-full cursor-pointer rounded-xl border border-edge bg-surface/65 p-3.5 text-left transition-colors hover:border-amber-500/40 hover:bg-surface"
    >
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="truncate font-medium text-slate-100">
            {pair} <span className="text-slate-500">·</span>{' '}
            <span className={side === 'BUY' ? 'text-emerald-400' : 'text-rose-400'}>{side}</span>
          </div>
          <div className="mt-0.5 font-mono-num text-xs text-slate-400">Qty {quantity}</div>
        </div>
        <Badge tone={tone}>{status}</Badge>
      </div>
      <div className="mt-2 break-words font-mono-num text-xs text-slate-400">{detail}</div>
      <DateMeta orderAt={createdAt} />
    </button>
  )
}

export function PositionCard({
  pair,
  side,
  status,
  quantity,
  entryPrice,
  exitPrice,
  pnl,
  openedAt,
  closedAt,
  onClick,
}: {
  pair: string
  side: string
  status: string
  quantity: number | string
  entryPrice: number
  exitPrice?: number | null
  pnl?: number | null
  openedAt?: string | null
  closedAt?: string | null
  onClick?: () => void
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="w-full cursor-pointer rounded-xl border border-edge bg-surface/65 p-3.5 text-left transition-colors hover:border-amber-500/40 hover:bg-surface"
    >
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="truncate font-medium text-slate-100">
            {pair} <span className="text-slate-500">·</span>{' '}
            <span className={side === 'LONG' ? 'text-emerald-400' : 'text-rose-400'}>{side}</span>
          </div>
          <div className="mt-0.5 font-mono-num text-xs text-slate-400">Qty {quantity}</div>
        </div>
        <Badge tone={status === 'OPEN' ? 'info' : 'default'}>{status}</Badge>
      </div>
      <div className="mt-2 grid grid-cols-2 gap-x-3 gap-y-1 font-mono-num text-xs text-slate-400">
        <span>Entry ₹{Number(entryPrice).toLocaleString()}</span>
        <span>{exitPrice != null ? `Exit ₹${Number(exitPrice).toLocaleString()}` : 'Exit —'}</span>
        {pnl != null && (
          <span className={`col-span-2 font-medium ${pnl >= 0 ? 'text-emerald-400' : 'text-rose-400'}`}>
            PnL {pnl >= 0 ? '+' : '−'}₹{Math.abs(Number(pnl)).toFixed(2)}
          </span>
        )}
      </div>
      <DateMeta openedAt={openedAt} closedAt={closedAt ?? null} />
    </button>
  )
}
