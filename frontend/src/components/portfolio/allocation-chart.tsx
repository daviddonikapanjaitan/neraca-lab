"use client"

import { useState } from "react"
import { Cell, Pie, PieChart, Tooltip } from "recharts"

import { EmptyState } from "@/components/empty-state"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { formatNumber, formatPercent } from "@/lib/format"
import type { ColoredSlice } from "@/lib/portfolio"
import { cn } from "@/lib/utils"

/** 269,340,500 -> "269.34M" */
function compactIdr(value: number): string {
  return value.toLocaleString("en-US", { notation: "compact", maximumFractionDigits: 2 })
}

const SIZE = 240

/**
 * Portfolio > Position: the portfolio's market value by sector and the cash, as a share of the whole (market total +
 * cash). Beside the pie, every slice with all its stocks - each ticker with its own weight - so nothing is read from
 * colour alone and no ticker is cut off.
 */
export function AllocationChart({
  slices,
  total,
  weights,
}: {
  slices: ColoredSlice[]
  /** market total + cash */
  total: number
  /** ticker -> its market value / (market total + cash) */
  weights: Map<string, number | null>
}) {
  const [active, setActive] = useState<string | null>(null)

  return (
    <Card>
      <CardHeader>
        <CardTitle>Allocation by sector</CardTitle>
        <CardDescription>
          Each sector&apos;s market value and the cash, as a share of the portfolio (market total + cash).
        </CardDescription>
      </CardHeader>
      <CardContent>
        {slices.length === 0 ? (
          <EmptyState
            variant="chart"
            title="Nothing to show yet"
            description="Add a position or the cash to see the allocation."
          />
        ) : (
          <div className="grid items-start gap-6 lg:grid-cols-[auto_minmax(0,1fr)]">
            <div
              className="relative mx-auto"
              style={{ width: SIZE, height: SIZE }}
              role="img"
              aria-label={`Allocation: ${slices.map((s) => `${s.name} ${formatPercent(s.share, 1)}`).join(", ")}`}
            >
              <PieChart width={SIZE} height={SIZE}>
                <Pie
                  data={slices}
                  dataKey="value"
                  nameKey="name"
                  innerRadius={74}
                  outerRadius={SIZE / 2 - 2}
                  stroke="var(--card)"
                  strokeWidth={2}
                  isAnimationActive={false}
                  onMouseEnter={(_, i) => setActive(slices[i]?.name ?? null)}
                  onMouseLeave={() => setActive(null)}
                >
                  {slices.map((s) => (
                    <Cell key={s.name} fill={s.color} opacity={active === null || active === s.name ? 1 : 0.3} />
                  ))}
                </Pie>
                <Tooltip
                  cursor={false}
                  wrapperStyle={{ zIndex: 20 }}
                  content={({ active: shown, payload }) => {
                    const s = shown && payload?.[0] ? (payload[0].payload as ColoredSlice) : null
                    if (!s) return null
                    return (
                      <div className="max-w-[260px] rounded-lg border bg-popover px-3 py-2 text-xs text-popover-foreground shadow-md">
                        <div className="flex items-center gap-2 font-medium">
                          <span className="size-2.5 shrink-0 rounded-full" style={{ background: s.color }} />
                          {s.name}
                        </div>
                        <div className="mt-1 font-mono tabular-nums">
                          {formatPercent(s.share, 2)} · Rp {formatNumber(s.value, 0)}
                        </div>
                        {s.tickers.length > 0 && <div className="mt-0.5 text-muted-foreground">{s.tickers.join(", ")}</div>}
                      </div>
                    )
                  }}
                />
              </PieChart>
              <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center text-center">
                <span className="text-xs text-muted-foreground">Portfolio</span>
                <span className="font-mono text-lg font-semibold tabular-nums">Rp {compactIdr(total)}</span>
                <span className="text-[11px] text-muted-foreground">market + cash</span>
              </div>
            </div>

            <div className="min-w-0 overflow-x-auto">
              <table className="w-full text-sm" aria-label="Allocation by sector">
                <thead>
                  <tr className="border-b text-left text-xs text-muted-foreground">
                    <th className="py-2 pr-3 font-medium">Sector</th>
                    <th className="py-2 pr-3 font-medium">Stocks (weight in the portfolio)</th>
                    <th className="py-2 pr-3 text-right font-medium">Value (Rp)</th>
                    <th className="w-[150px] py-2 text-right font-medium">Share</th>
                  </tr>
                </thead>
                <tbody>
                  {slices.map((s) => (
                    <tr
                      key={s.name}
                      className={cn(
                        "border-b align-top transition-opacity last:border-b-0",
                        active !== null && active !== s.name && "opacity-45"
                      )}
                      onMouseEnter={() => setActive(s.name)}
                      onMouseLeave={() => setActive(null)}
                    >
                      <td className="py-2.5 pr-3">
                        <span className="flex items-center gap-2 font-medium whitespace-nowrap">
                          <span className="size-3 shrink-0 rounded-sm" style={{ background: s.color }} aria-hidden />
                          {s.name}
                        </span>
                      </td>
                      <td className="py-2 pr-3">
                        {s.cash ? (
                          <span className="text-xs text-muted-foreground">Uninvested cash</span>
                        ) : (
                          <div className="flex flex-wrap gap-1">
                            {s.tickers.map((t) => (
                              <span
                                key={t}
                                className="inline-flex items-center gap-1 rounded-md border bg-muted/40 px-1.5 py-0.5 text-xs"
                              >
                                <span className="font-mono font-medium">{t}</span>
                                <span className="font-mono text-muted-foreground tabular-nums">
                                  {formatPercent(weights.get(t) ?? null, 1)}
                                </span>
                              </span>
                            ))}
                          </div>
                        )}
                      </td>
                      <td className="py-2.5 pr-3 text-right font-mono whitespace-nowrap tabular-nums">
                        {formatNumber(s.value, 0)}
                      </td>
                      <td className="py-2.5">
                        <div className="flex items-center justify-end gap-2">
                          <div className="h-1.5 w-20 overflow-hidden rounded-full bg-muted" aria-hidden>
                            <div
                              className="h-full rounded-full"
                              style={{ width: `${Math.min(100, (s.share ?? 0) * 100)}%`, background: s.color }}
                            />
                          </div>
                          <span className="w-14 text-right font-mono font-medium tabular-nums">
                            {formatPercent(s.share, 1)}
                          </span>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  )
}
