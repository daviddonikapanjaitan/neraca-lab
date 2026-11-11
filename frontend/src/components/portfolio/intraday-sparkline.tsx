"use client"

import { useState } from "react"

import { formatNumber } from "@/lib/format"
import type { IntradaySession } from "@/lib/portfolio"

const WIDTH = 112
const HEIGHT = 32
const PAD = 3

/** Epoch seconds of a WIB time on a session date ("2026-11-12", 9, 0). WIB is UTC+7, no daylight saving. */
function wib(date: string, hour: number, minute: number): number {
  const [y, m, d] = date.split("-").map(Number)
  return Date.UTC(y, m - 1, d, hour - 7, minute) / 1000
}

/** "14:35" in WIB */
export function wibTime(epochSeconds: number): string {
  return new Date(epochSeconds * 1000).toLocaleTimeString("en-GB", {
    timeZone: "Asia/Jakarta",
    hour: "2-digit",
    minute: "2-digit",
  })
}

/**
 * The day of a stock from the open to the close of the IDX session: its 5-minute closes across 09:00 - 16:15 WIB (the
 * part of the day still to come stays empty), the previous close dashed. Green above the previous close, red below.
 * Hovering reads the price at that time.
 */
export function IntradaySparkline({ session, last }: { session: IntradaySession; last: number | null }) {
  const [hover, setHover] = useState<number | null>(null)
  const points = session.points
  if (points.length === 0) {
    return <span className="text-[11px] text-muted-foreground">no trades yet</span>
  }
  const start = wib(session.sessionDate, 9, 0)
  const end = wib(session.sessionDate, 16, 15)
  const closes = points.map((p) => p[1])
  const base = session.previousClose
  const lo = Math.min(...closes, base ?? Infinity)
  const hi = Math.max(...closes, base ?? -Infinity)
  const span = hi - lo || 1
  const x = (t: number) => PAD + ((Math.min(Math.max(t, start), end) - start) / (end - start)) * (WIDTH - 2 * PAD)
  const y = (v: number) => PAD + (1 - (v - lo) / span) * (HEIGHT - 2 * PAD)
  const now = last ?? closes[closes.length - 1]
  const up = base === null ? null : now > base ? true : now < base ? false : null
  const color = up === null ? "var(--muted-foreground)" : up ? "rgb(5 150 105)" : "rgb(225 29 72)"
  // a gap of more than 20 minutes (the lunch break, a halt) breaks the line instead of bridging it
  const path = points
    .map((p, i) => `${i === 0 || p[0] - points[i - 1][0] > 1200 ? "M" : "L"}${x(p[0]).toFixed(1)},${y(p[1]).toFixed(1)}`)
    .join(" ")
  const lastPoint = points[points.length - 1]
  const shown = hover === null ? null : points[hover]

  function onMove(e: React.MouseEvent<SVGSVGElement>) {
    const rect = e.currentTarget.getBoundingClientRect()
    const t = start + ((e.clientX - rect.left) / rect.width) * (end - start)
    let best = 0
    for (let i = 1; i < points.length; i++) {
      if (Math.abs(points[i][0] - t) < Math.abs(points[best][0] - t)) best = i
    }
    setHover(best)
  }

  return (
    <span className="inline-flex flex-col items-end gap-0.5">
      <svg
        width={WIDTH}
        height={HEIGHT}
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        role="img"
        aria-label={`Today: open ${formatNumber(session.open, 2)}, low ${formatNumber(session.low, 2)}, high ${formatNumber(session.high, 2)}, last ${formatNumber(now, 2)}`}
        className="cursor-crosshair overflow-visible"
        onMouseMove={onMove}
        onMouseLeave={() => setHover(null)}
      >
        {base !== null && (
          <line
            x1={PAD}
            x2={WIDTH - PAD}
            y1={y(base)}
            y2={y(base)}
            stroke="currentColor"
            strokeOpacity={0.35}
            strokeDasharray="2 2"
            strokeWidth={1}
          />
        )}
        <path d={path} fill="none" stroke={color} strokeWidth={1.5} strokeLinejoin="round" strokeLinecap="round" />
        <circle cx={x(lastPoint[0])} cy={y(lastPoint[1])} r={2.5} fill={color} />
        {shown && (
          <>
            <line
              x1={x(shown[0])}
              x2={x(shown[0])}
              y1={0}
              y2={HEIGHT}
              stroke="currentColor"
              strokeOpacity={0.4}
              strokeWidth={1}
            />
            <circle cx={x(shown[0])} cy={y(shown[1])} r={3} fill={color} stroke="var(--card)" strokeWidth={1.5} />
          </>
        )}
      </svg>
      <span className="font-mono text-[10px] text-muted-foreground tabular-nums">
        {shown
          ? `${wibTime(shown[0])} · ${formatNumber(shown[1], 2)}`
          : `L ${formatNumber(session.low, 0)} · H ${formatNumber(session.high, 0)}`}
      </span>
    </span>
  )
}
