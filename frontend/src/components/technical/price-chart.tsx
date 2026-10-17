"use client"

// The price chart of the Technicals page: a small wrapper around TradingView Lightweight Charts v5 (no third-party
// React wrapper). Candlesticks, or a plain line of the close for palm oil and the monthly series (chartsAsLine). The
// chart is rebuilt when its data, indicators or theme change, and removed on unmount.
import { useEffect, useImperativeHandle, useRef, type Ref } from "react"
import {
  CandlestickSeries,
  ColorType,
  createChart,
  createSeriesMarkers,
  CrosshairMode,
  HistogramSeries,
  LineSeries,
  LineStyle,
  type IChartApi,
  type ISeriesApi,
  type SeriesMarker,
  type Time,
  type UTCTimestamp,
} from "lightweight-charts"

import { BandPrimitive, type PriceBand, type PriceBox } from "@/components/technical/band-primitive"
import { chartsAsLine, type Events, type IndicatorId, type IndicatorSeries, type Levels, type Ohlcv } from "@/lib/technical"

export interface PriceChartHandle {
  /** PNG of the chart as shown (all panes), or null before the chart exists */
  screenshot(): HTMLCanvasElement | null
}

export interface PriceChartProps {
  ohlcv: Ohlcv
  indicators: IndicatorSeries | undefined
  levels: Levels | undefined
  events: Events | undefined
  selected: IndicatorId[]
  dark: boolean
  ref?: Ref<PriceChartHandle>
}

/** Lower panes in display order: indicator id, series, label. */
const PANES: { id: IndicatorId; label: string }[] = [
  { id: "volume", label: "Volume" },
  { id: "rsi", label: "RSI 14" },
  { id: "macd", label: "MACD 12 26 9" },
  { id: "stoch", label: "Stochastic 14 3 3" },
  { id: "obv", label: "OBV" },
  { id: "adx", label: "ADX 14" },
  { id: "atrPct", label: "ATR %" },
  { id: "rs", label: "RS vs IHSG (100 = start)" },
  { id: "mom", label: "12-1 month momentum %" },
]

interface Palette {
  text: string
  muted: string
  grid: string
  border: string
  background: string
  up: string
  down: string
  upFill: string
  downFill: string
  lines: Record<string, string>
  support: { fill: string; border: string }
  resistance: { fill: string; border: string }
  pivot: { fill: string; border: string }
  base: { fill: string; border: string }
}

/** A CSS color (oklch, var(...)) as the rgb() the chart's canvas understands. */
function resolveColor(css: string, fallback: string): string {
  if (typeof document === "undefined") return fallback
  const probe = document.createElement("span")
  probe.style.color = css
  probe.style.display = "none"
  document.body.appendChild(probe)
  const computed = getComputedStyle(probe).color
  probe.remove()
  const canvas = document.createElement("canvas")
  canvas.width = canvas.height = 1
  const ctx = canvas.getContext("2d")
  if (!ctx || !computed) return fallback
  ctx.fillStyle = fallback
  ctx.fillStyle = computed
  ctx.fillRect(0, 0, 1, 1)
  const [r, g, b, a] = ctx.getImageData(0, 0, 1, 1).data
  return a === 0 ? fallback : `rgb(${r}, ${g}, ${b})`
}

function palette(dark: boolean): Palette {
  const text = resolveColor("var(--foreground)", dark ? "#fafafa" : "#0a0a0a")
  return {
    text,
    muted: resolveColor("var(--muted-foreground)", "#737373"),
    grid: resolveColor("var(--border)", dark ? "#262626" : "#e5e5e5"),
    border: resolveColor("var(--border)", dark ? "#262626" : "#e5e5e5"),
    background: resolveColor("var(--card)", dark ? "#0a0a0a" : "#ffffff"),
    up: dark ? "#34d399" : "#059669",
    down: dark ? "#fb7185" : "#e11d48",
    upFill: dark ? "rgba(52, 211, 153, 0.45)" : "rgba(5, 150, 105, 0.45)",
    downFill: dark ? "rgba(251, 113, 133, 0.45)" : "rgba(225, 29, 72, 0.45)",
    lines: {
      sma20: dark ? "#fbbf24" : "#d97706",
      sma50: dark ? "#60a5fa" : "#2563eb",
      sma100: dark ? "#c084fc" : "#9333ea",
      sma200: dark ? "#f87171" : "#dc2626",
      ema20: dark ? "#2dd4bf" : "#0d9488",
      ema50: dark ? "#a3e635" : "#65a30d",
      bb: dark ? "#94a3b8" : "#64748b",
      channel: dark ? "#fdba74" : "#ea580c",
      atrBands: dark ? "#f0abfc" : "#c026d3",
      ihsg: dark ? "#e5e5e5" : "#525252",
      primary: dark ? "#60a5fa" : "#2563eb",
      secondary: dark ? "#fbbf24" : "#d97706",
      tertiary: dark ? "#f87171" : "#dc2626",
    },
    support: { fill: dark ? "rgba(52, 211, 153, 0.12)" : "rgba(5, 150, 105, 0.10)", border: dark ? "rgba(52, 211, 153, 0.55)" : "rgba(5, 150, 105, 0.5)" },
    resistance: { fill: dark ? "rgba(251, 113, 133, 0.12)" : "rgba(225, 29, 72, 0.09)", border: dark ? "rgba(251, 113, 133, 0.55)" : "rgba(225, 29, 72, 0.5)" },
    pivot: { fill: dark ? "rgba(148, 163, 184, 0.14)" : "rgba(100, 116, 139, 0.12)", border: dark ? "rgba(148, 163, 184, 0.6)" : "rgba(100, 116, 139, 0.55)" },
    base: { fill: dark ? "rgba(96, 165, 250, 0.10)" : "rgba(37, 99, 235, 0.07)", border: dark ? "rgba(96, 165, 250, 0.8)" : "rgba(37, 99, 235, 0.7)" },
  }
}

type Point = [number, number]

function line(points: Point[] | undefined) {
  return (points ?? []).map(([t, v]) => ({ time: t as UTCTimestamp, value: v }))
}

/** The time of the bar an event belongs to: daily = the first bar on or after it, weekly / monthly = its period's bar. */
function barTimeOf(times: number[], eventTime: number, interval: Ohlcv["interval"]): number | null {
  if (times.length === 0 || eventTime > times[times.length - 1] + (interval === "D" ? 0 : 40 * 86400)) return null
  if (interval === "D") {
    const t = times.find((x) => x >= eventTime)
    return t ?? null
  }
  let found: number | null = null
  for (const t of times) {
    if (t <= eventTime) found = t
    else break
  }
  return found
}

const EVENT_STYLE: Record<string, { text: string; color: (p: Palette) => string }> = {
  DIVIDEND: { text: "D", color: (p) => p.lines.primary },
  STOCK_SPLIT: { text: "S", color: (p) => p.lines.secondary },
  REVERSE_SPLIT: { text: "RS", color: (p) => p.lines.secondary },
  RIGHTS_ISSUE: { text: "R", color: (p) => p.lines.sma100 },
  PRIVATE_PLACEMENT: { text: "PP", color: (p) => p.lines.sma100 },
  EARNINGS: { text: "E", color: (p) => p.text },
  RUPS: { text: "AGM", color: (p) => p.muted },
  RUPSLB: { text: "EGM", color: (p) => p.muted },
}

export default function PriceChart({ ohlcv, indicators, levels, events, selected, dark, ref }: PriceChartProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const chartRef = useRef<IChartApi | null>(null)

  useImperativeHandle(ref, () => ({
    screenshot: () => chartRef.current?.takeScreenshot(true, false) ?? null,
  }))

  useEffect(() => {
    const container = containerRef.current
    if (!container) return
    const p = palette(dark)
    const has = (id: IndicatorId) => selected.includes(id)
    const series = indicators?.series ?? {}
    const integerPrices = ohlcv.bars.every((b) => Number.isInteger(b[4]))
    const priceFormat = { type: "price" as const, precision: integerPrices ? 0 : 2, minMove: integerPrices ? 1 : 0.01 }

    const chart = createChart(container, {
      autoSize: true,
      layout: {
        background: { type: ColorType.Solid, color: "transparent" },
        textColor: p.muted,
        fontSize: 11,
        attributionLogo: true,
        panes: { separatorColor: p.border, separatorHoverColor: p.grid, enableResize: true },
      },
      grid: { vertLines: { color: p.grid, style: LineStyle.Dotted }, horzLines: { color: p.grid, style: LineStyle.Dotted } },
      rightPriceScale: { borderColor: p.border },
      timeScale: { borderColor: p.border, timeVisible: false, rightOffset: 3 },
      crosshair: { mode: CrosshairMode.Normal },
      localization: { locale: "en-US" },
    })
    chartRef.current = chart

    // ---- price pane: candlesticks, or a plain line of the close (coal, palm oil)
    let candles: ISeriesApi<"Candlestick"> | ISeriesApi<"Line">
    if (chartsAsLine(ohlcv.type, ohlcv.ticker, ohlcv.frequency)) {
      const closeLine = chart.addSeries(LineSeries, {
        color: p.lines.primary,
        lineWidth: 2,
        priceLineVisible: true,
        lastValueVisible: true,
        priceFormat,
        title: ohlcv.ticker,
      })
      closeLine.setData(ohlcv.bars.map(([t, , , , c]) => ({ time: t as UTCTimestamp, value: c })))
      candles = closeLine
    } else {
      const candleSeries = chart.addSeries(CandlestickSeries, {
        upColor: p.up,
        downColor: p.down,
        wickUpColor: p.up,
        wickDownColor: p.down,
        borderVisible: false,
        priceFormat,
        title: ohlcv.ticker,
      })
      candleSeries.setData(
        ohlcv.bars.map(([t, o, h, l, c]) => ({ time: t as UTCTimestamp, open: o, high: h, low: l, close: c }))
      )
      candles = candleSeries
    }

    const overlay = (points: Point[] | undefined, color: string, title: string, style: LineStyle = LineStyle.Solid, width = 1) => {
      if (!points || points.length === 0) return
      const s = chart.addSeries(LineSeries, {
        color,
        lineWidth: width as 1 | 2 | 3 | 4,
        lineStyle: style,
        priceLineVisible: false,
        lastValueVisible: false,
        crosshairMarkerVisible: false,
        title,
        priceFormat,
      })
      s.setData(line(points))
    }
    for (const id of ["sma20", "sma50", "sma100", "sma200", "ema20", "ema50"] as const) {
      if (has(id)) overlay(series[id], p.lines[id], id.toUpperCase(), LineStyle.Solid, id === "sma200" || id === "sma50" ? 2 : 1)
    }
    if (has("bb")) {
      overlay(series["bb.upper"], p.lines.bb, "BB up", LineStyle.Dashed)
      overlay(series["bb.middle"], p.lines.bb, "BB mid", LineStyle.Dotted)
      overlay(series["bb.lower"], p.lines.bb, "BB low", LineStyle.Dashed)
    }
    for (const id of ["channel20", "channel50"] as const) {
      if (has(id)) {
        const n = id === "channel20" ? "20" : "50"
        overlay(series[`${id}.high`], p.lines.channel, `${n}H`, n === "20" ? LineStyle.Dotted : LineStyle.Dashed)
        overlay(series[`${id}.low`], p.lines.channel, `${n}L`, n === "20" ? LineStyle.Dotted : LineStyle.Dashed)
      }
    }
    if (has("atrBands")) {
      overlay(series["atrBands.upper"], p.lines.atrBands, "ATR+", LineStyle.Dashed)
      overlay(series["atrBands.lower"], p.lines.atrBands, "ATR−", LineStyle.Dashed)
    }
    if (has("ihsg") && series.index && series.index.length > 0 && ohlcv.bars.length > 0) {
      // the index rebased onto the stock's first close of the range
      const first = series.index[0]
      const stockFirst = ohlcv.bars.find((b) => b[0] >= first[0])?.[4]
      if (stockFirst) {
        overlay(series.index.map(([t, v]) => [t, (v / first[1]) * stockFirst] as Point), p.lines.ihsg, "IHSG", LineStyle.Solid, 1)
      }
    }

    // ---- levels (split-adjusted, tradable prices: hidden on the total-return basis)
    const showLevels = ohlcv.adjustment === "split" && levels
    if (showLevels) {
      const bands: PriceBand[] = []
      if (has("zones")) {
        for (const z of levels.zones) {
          const style = z.type === "SUPPORT" ? p.support : z.type === "RESISTANCE" ? p.resistance : p.pivot
          bands.push({ low: z.low, high: z.high, fill: style.fill, border: style.border })
        }
      }
      const boxes: PriceBox[] = []
      if (has("base") && levels.base.detected && levels.base.startDate && levels.base.endDate && levels.base.high && levels.base.low) {
        const from = Date.parse(`${levels.base.startDate}T00:00:00Z`) / 1000
        const to = Date.parse(`${levels.base.endDate}T00:00:00Z`) / 1000
        const times = ohlcv.bars.map((b) => b[0])
        const fromBar = barTimeOf(times, from, ohlcv.interval) ?? times[0]
        const toBar = barTimeOf(times, to, ohlcv.interval) ?? times[times.length - 1]
        boxes.push({ low: levels.base.low, high: levels.base.high, from: fromBar, to: toBar, fill: p.base.fill, border: p.base.border,
          label: `Base ${levels.base.days} days` })
      }
      if (bands.length > 0 || boxes.length > 0) candles.attachPrimitive(new BandPrimitive(bands, boxes))
      if (has("retracements") && levels.retracement) {
        for (const l of levels.retracement.levels) {
          candles.createPriceLine({ price: l.price, color: p.lines.secondary, lineWidth: 1, lineStyle: LineStyle.Dashed,
            axisLabelVisible: true, title: `${(l.ratio * 100).toFixed(l.ratio * 100 % 1 === 0 ? 0 : 1)}%` })
        }
      }
      if (has("extremes")) {
        candles.createPriceLine({ price: levels.week52.high, color: p.up, lineWidth: 1, lineStyle: LineStyle.Dotted, axisLabelVisible: true, title: "52W H" })
        candles.createPriceLine({ price: levels.week52.low, color: p.down, lineWidth: 1, lineStyle: LineStyle.Dotted, axisLabelVisible: true, title: "52W L" })
        candles.createPriceLine({ price: levels.allTimeHigh.price, color: p.text, lineWidth: 1, lineStyle: LineStyle.SparseDotted, axisLabelVisible: true, title: "ATH" })
      }
    }

    // ---- markers: corporate events above the bar, ARA / ARB days below / above
    const markers: SeriesMarker<Time>[] = []
    const times = ohlcv.bars.map((b) => b[0])
    if (has("events") && events) {
      const firstTime = times[0] ?? 0
      for (const e of events.events) {
        const t = barTimeOf(times, e.time, ohlcv.interval)
        if (t === null || t < firstTime) continue
        const style = EVENT_STYLE[e.type] ?? { text: e.type.slice(0, 2), color: (pp: Palette) => pp.muted }
        markers.push({ time: t as UTCTimestamp, position: "aboveBar", shape: "circle", color: style.color(p), text: style.text, size: 0.8 })
      }
    }
    if (has("autoRejection")) {
      for (const [t, type] of ohlcv.autoRejection) {
        markers.push(type === "ARA"
          ? { time: t as UTCTimestamp, position: "aboveBar", shape: "arrowUp", color: p.up, text: "ARA", size: 0.9 }
          : { time: t as UTCTimestamp, position: "belowBar", shape: "arrowDown", color: p.down, text: "ARB", size: 0.9 })
      }
    }
    markers.sort((a, b) => (a.time as number) - (b.time as number))
    if (markers.length > 0) createSeriesMarkers(candles, markers)

    // ---- lower panes
    let pane = 1
    const paneLine = (index: number, points: Point[] | undefined, color: string, title: string, style: LineStyle = LineStyle.Solid) => {
      const s = chart.addSeries(LineSeries, { color, lineWidth: 1, lineStyle: style, priceLineVisible: false, lastValueVisible: true, title }, index)
      s.setData(line(points))
      return s
    }
    const guide = (s: ISeriesApi<"Line">, price: number) =>
      s.createPriceLine({ price, color: p.muted, lineWidth: 1, lineStyle: LineStyle.Dotted, axisLabelVisible: false, title: "" })
    for (const def of PANES) {
      if (!has(def.id)) continue
      const index = pane++
      switch (def.id) {
        case "volume": {
          const vol = chart.addSeries(HistogramSeries, { priceFormat: { type: "volume" }, priceLineVisible: false, lastValueVisible: false, title: "Volume" }, index)
          vol.setData(ohlcv.bars.map((b, i) => ({
            time: b[0] as UTCTimestamp,
            value: b[5],
            color: i > 0 && b[4] < ohlcv.bars[i - 1][4] ? p.downFill : p.upFill,
          })))
          const avg = chart.addSeries(LineSeries, { color: p.lines.primary, lineWidth: 1, priceFormat: { type: "volume" }, priceLineVisible: false, lastValueVisible: false, title: "Avg 50" }, index)
          avg.setData(line(series.volSma50))
          break
        }
        case "rsi": {
          const s = paneLine(index, series.rsi14, p.lines.sma100, "RSI 14")
          guide(s, 70)
          guide(s, 30)
          break
        }
        case "macd": {
          const hist = chart.addSeries(HistogramSeries, { priceLineVisible: false, lastValueVisible: false, title: "Hist" }, index)
          hist.setData(line(series["macd.histogram"]).map((d) => ({ ...d, color: d.value >= 0 ? p.upFill : p.downFill })))
          paneLine(index, series["macd.line"], p.lines.primary, "MACD")
          paneLine(index, series["macd.signal"], p.lines.secondary, "Signal")
          break
        }
        case "stoch": {
          const s = paneLine(index, series["stoch.k"], p.lines.primary, "%K")
          paneLine(index, series["stoch.d"], p.lines.secondary, "%D")
          guide(s, 80)
          guide(s, 20)
          break
        }
        case "obv":
          chart.addSeries(LineSeries, { color: p.lines.primary, lineWidth: 1, priceFormat: { type: "volume" }, priceLineVisible: false, title: "OBV" }, index)
            .setData(line(series.obv))
          break
        case "adx": {
          const s = paneLine(index, series["adx.adx"], p.text, "ADX")
          paneLine(index, series["adx.plusDi"], p.up, "+DI")
          paneLine(index, series["adx.minusDi"], p.down, "−DI")
          guide(s, 25)
          break
        }
        case "atrPct":
          paneLine(index, series.atrPct, p.lines.atrBands, "ATR %")
          break
        case "rs": {
          const s = paneLine(index, series.rs, p.lines.primary, "RS vs IHSG")
          guide(s, 100)
          break
        }
        case "mom": {
          const s = paneLine(index, series.mom12_1, p.lines.secondary, "12-1M %")
          guide(s, 0)
          break
        }
      }
    }
    const panes = chart.panes()
    if (panes.length > 1) {
      panes[0].setStretchFactor(Math.max(2, panes.length - 1) * 1.6)
      for (let i = 1; i < panes.length; i++) panes[i].setStretchFactor(1)
    }
    chart.timeScale().fitContent()

    return () => {
      chartRef.current = null
      chart.remove()
    }
  }, [ohlcv, indicators, levels, events, selected, dark])

  const paneCount = 1 + PANES.filter((d) => selected.includes(d.id)).length
  const kind = chartsAsLine(ohlcv.type, ohlcv.ticker, ohlcv.frequency) ? "line" : "candlestick"
  return (
    <div
      ref={containerRef}
      className="w-full"
      style={{ height: Math.min(1100, 420 + (paneCount - 1) * 130) }}
      role="img"
      aria-label={`${ohlcv.ticker} ${ohlcv.interval === "D" ? "daily" : ohlcv.interval === "W" ? "weekly" : "monthly"} ${kind} chart`}
    />
  )
}
