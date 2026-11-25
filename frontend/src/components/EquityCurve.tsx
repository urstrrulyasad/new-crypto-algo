import { useId, useMemo } from 'react'

export function EquityCurve({
  points,
  height = 64,
}: {
  points: number[]
  height?: number
}) {
  const gradId = useId()

  const geom = useMemo(() => {
    if (points.length < 2) return null
    const w = 240
    const h = height
    const min = Math.min(0, ...points)
    const max = Math.max(0.0001, ...points)
    const span = Math.max(1e-6, max - min)
    const padY = 6
    const innerH = h - padY * 2
    const coords = points.map((v, i) => {
      const x = (i / (points.length - 1)) * w
      const y = padY + innerH - ((v - min) / span) * innerH
      return [Number(x.toFixed(2)), Number(y.toFixed(2))] as const
    })
    const line = coords
      .map(([x, y], i) => `${i === 0 ? 'M' : 'L'}${x},${y}`)
      .join(' ')
    const area = `${line} L${w},${h} L0,${h} Z`
    const zeroY = Number((padY + innerH - ((0 - min) / span) * innerH).toFixed(2))
    const positive = points[points.length - 1] >= points[0]
    return { w, h, line, area, zeroY, positive }
  }, [points, height])

  if (!geom) return null

  const stroke = geom.positive ? '#10b981' : '#f43f5e'
  const fill = geom.positive ? '#10b981' : '#f43f5e'

  return (
    <svg
      viewBox={`0 0 ${geom.w} ${geom.h}`}
      className="h-16 w-full overflow-visible"
      preserveAspectRatio="none"
      aria-label="Cumulative equity trajectory"
    >
      <defs>
        <linearGradient id={gradId} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={fill} stopOpacity="0.28" />
          <stop offset="100%" stopColor={fill} stopOpacity="0.0" />
        </linearGradient>
      </defs>
      <line
        x1={0}
        y1={geom.zeroY}
        x2={geom.w}
        y2={geom.zeroY}
        stroke="#262a3b"
        strokeDasharray="3 3"
        strokeWidth="1"
      />
      <path d={geom.area} fill={`url(#${gradId})`} />
      <path
        d={geom.line}
        fill="none"
        stroke={stroke}
        strokeWidth="1.75"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}
