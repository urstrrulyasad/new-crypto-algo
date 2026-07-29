import { useEffect, useRef } from 'react'
import {
  createChart,
  ColorType,
  CandlestickSeries,
  createSeriesMarkers,
  LineStyle,
  type IChartApi,
  type ISeriesApi,
  type IPriceLine,
  type UTCTimestamp,
  type SeriesMarker,
  type Time,
} from 'lightweight-charts'

export interface Candle {
  ts: string
  open: number
  high: number
  low: number
  close: number
  volume: number
}

export interface TradeMarker {
  time: string
  type: 'entry' | 'exit'
  text?: string
}

export interface PriceLineSpec {
  price: number
  label: string
  color: string
  style?: 'solid' | 'dashed'
}

export function CandleChart({
  candles,
  markers = [],
  priceLines = [],
  height = 420,
}: {
  candles: Candle[]
  markers?: TradeMarker[]
  priceLines?: PriceLineSpec[]
  height?: number
}) {
  const ref = useRef<HTMLDivElement>(null)
  const chartRef = useRef<IChartApi | null>(null)
  const seriesRef = useRef<ISeriesApi<'Candlestick'> | null>(null)
  const linesRef = useRef<IPriceLine[]>([])

  useEffect(() => {
    if (!ref.current) return
    const chart = createChart(ref.current, {
      height,
      layout: {
        background: { type: ColorType.Solid, color: 'transparent' },
        textColor: '#64748b',
        fontFamily: 'Inter, sans-serif',
      },
      grid: {
        vertLines: { color: 'rgba(148,163,184,0.06)' },
        horzLines: { color: 'rgba(148,163,184,0.06)' },
      },
      rightPriceScale: { borderColor: 'rgba(148,163,184,0.15)' },
      timeScale: { borderColor: 'rgba(148,163,184,0.15)', timeVisible: true },
      crosshair: {
        vertLine: { color: 'rgba(34,211,238,0.4)', labelBackgroundColor: '#0e7490' },
        horzLine: { color: 'rgba(34,211,238,0.4)', labelBackgroundColor: '#0e7490' },
      },
    })
    chartRef.current = chart

    const series = chart.addSeries(CandlestickSeries, {
      upColor: '#34d399',
      downColor: '#fb7185',
      borderUpColor: '#34d399',
      borderDownColor: '#fb7185',
      wickUpColor: '#34d39988',
      wickDownColor: '#fb718588',
    })
    seriesRef.current = series

    const onResize = () => {
      if (ref.current) chart.applyOptions({ width: ref.current.clientWidth })
    }
    onResize()
    window.addEventListener('resize', onResize)
    return () => {
      window.removeEventListener('resize', onResize)
      chart.remove()
      chartRef.current = null
      seriesRef.current = null
      linesRef.current = []
    }
  }, [height])

  useEffect(() => {
    const series = seriesRef.current
    const chart = chartRef.current
    if (!series || !chart) return

    series.setData(
      candles.map((c) => ({
        time: (new Date(c.ts).getTime() / 1000) as UTCTimestamp,
        open: c.open,
        high: c.high,
        low: c.low,
        close: c.close,
      })),
    )

    const seriesMarkers: SeriesMarker<Time>[] = markers.map((m) => ({
      time: (new Date(m.time).getTime() / 1000) as UTCTimestamp,
      position: m.type === 'entry' ? 'belowBar' : 'aboveBar',
      color: m.type === 'entry' ? '#34d399' : '#fb7185',
      shape: m.type === 'entry' ? 'arrowUp' : 'arrowDown',
      text: m.text,
    }))
    createSeriesMarkers(series, seriesMarkers)

    for (const pl of linesRef.current) series.removePriceLine(pl)
    linesRef.current = priceLines
      .filter((p) => Number.isFinite(p.price) && p.price > 0)
      .map((p) =>
        series.createPriceLine({
          price: p.price,
          color: p.color,
          lineWidth: 2,
          lineStyle: p.style === 'dashed' ? LineStyle.Dashed : LineStyle.Solid,
          axisLabelVisible: true,
          title: p.label,
        }),
      )

    if (candles.length) chart.timeScale().fitContent()
  }, [candles, markers, priceLines])

  return <div ref={ref} className="w-full" />
}
