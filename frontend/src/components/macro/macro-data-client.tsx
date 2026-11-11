"use client"

import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react"
import { useRouter } from "next/navigation"
import { Bar, BarChart, Brush, CartesianGrid, Cell, Line, LineChart, ReferenceLine, XAxis, YAxis } from "recharts"
import { CircleAlertIcon, ExternalLinkIcon, LoaderIcon } from "lucide-react"

import { EmptyState } from "@/components/empty-state"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { ChartContainer, ChartTooltip, ChartTooltipContent, type ChartConfig } from "@/components/ui/chart"
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { requestJson, RequestError } from "@/lib/client-api"
import { EMPTY, formatDate, formatNumber } from "@/lib/format"
import {
  MACRO_COUNTRY_LABELS,
  MACRO_DATA_HREF,
  MACRO_RANGES,
  macroDataChartUrl,
  type MacroDataSeries,
  type MacroDataSeriesChart,
  type MacroDataSeriesList,
  type MacroRange,
  type MacroSeriesState,
} from "@/lib/macro"
import { cn } from "@/lib/utils"

const lineConfig = {
  value: { label: "Value", theme: { light: "oklch(0.371 0 0)", dark: "oklch(0.87 0 0)" } },
} satisfies ChartConfig

const POSITIVE = "var(--color-emerald-600, #059669)"
const NEGATIVE = "var(--color-rose-600, #e11d48)"

const STATE_LABEL: Record<MacroSeriesState, string> = {
  UP_TO_DATE: "Up to date",
  BEHIND: "Behind",
  NEVER_FETCHED: "Never fetched",
  FAILING: "Failing",
  MANUAL: "Manual CSV",
}

const STATE_STYLE: Record<MacroSeriesState, string> = {
  UP_TO_DATE: "border-emerald-600/30 text-emerald-700 dark:text-emerald-400",
  BEHIND: "border-amber-600/30 text-amber-700 dark:text-amber-400",
  NEVER_FETCHED: "text-muted-foreground",
  FAILING: "border-rose-600/30 text-rose-700 dark:text-rose-400",
  MANUAL: "text-muted-foreground",
}

const FREQUENCY_LABEL: Record<string, string> = {
  DAILY: "Daily",
  WEEKLY: "Weekly",
  MONTHLY: "Monthly",
  QUARTERLY: "Quarterly",
  EVENT: "At each decision",
}

/** Decimals of a series' values: rates and indexes with 2, basis points and large amounts without. */
function digitsOf(series: MacroDataSeries): number {
  if (series.unit === "%" || series.unit === "points") return 2
  if (series.unit === "bps" || series.unit === "contracts") return 0
  const v = Math.abs(series.lastValue ?? 0)
  return v >= 1000 ? 0 : v >= 10 ? 1 : 2
}

function unitSuffix(unit: string): string {
  if (unit === "%") return "%"
  return ` ${unit}`
}

/** A value with its unit: "4.25%", "182 bps", "9,512,345 IDR bn". */
function valueText(value: number | null | undefined, series: MacroDataSeries, digits = digitsOf(series)): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return EMPTY
  return `${formatNumber(value, digits)}${unitSuffix(series.unit)}`
}

/** A change in the series' terms: "+0.25 points" for a rate, "+1.2% (+12.3 index)" for a level, "+1,234 IDR bn" for a flow. */
function changeText(change: number | null, pct: number | null, series: MacroDataSeries): string {
  if (change === null || !Number.isFinite(change)) return EMPTY
  const sign = change > 0 ? "+" : ""
  const digits = digitsOf(series)
  if (series.kind === "RATE") {
    return series.unit === "bps" ? `${sign}${formatNumber(change, 0)} bps` : `${sign}${formatNumber(change, 2)} points`
  }
  const units = `${sign}${formatNumber(change, digits)}${unitSuffix(series.unit)}`
  if (series.kind === "LEVEL" && pct !== null && Number.isFinite(pct)) {
    return `${pct > 0 ? "+" : ""}${formatNumber(pct, 2)}% (${units})`
  }
  return units
}

function toneOf(change: number | null | undefined): string {
  if (change === null || change === undefined || change === 0) return "text-muted-foreground"
  return change > 0 ? "text-emerald-700 dark:text-emerald-400" : "text-rose-700 dark:text-rose-400"
}

/** Axis ticks: compact above 100,000 (M2 in IDR bn: "9.5M"). */
function axisTick(value: number, digits: number): string {
  if (Math.abs(value) >= 100_000) {
    return value.toLocaleString("en-US", { notation: "compact", maximumFractionDigits: 1 })
  }
  return formatNumber(value, Math.min(digits, 2))
}

/**
 * Macro > Data: a dropdown of every series of Ingestion > Macro (grouped by country), the range, one large chart (a
 * line; net foreign buying as bars), its statistics and changes, what the series is and its latest observations. A
 * change reloads the chart and keeps the choice in the URL.
 */
export function MacroDataClient({ list, initial }: { list: MacroDataSeriesList; initial: MacroDataSeriesChart }) {
  const router = useRouter()
  const [data, setData] = useState<MacroDataSeriesChart>(initial)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const controller = useRef<AbortController | null>(null)

  useEffect(() => () => controller.current?.abort(), [])

  const load = useCallback(
    async (code: string, range: MacroRange) => {
      controller.current?.abort()
      const abort = new AbortController()
      controller.current = abort
      setLoading(true)
      setError(null)
      try {
        const { body } = await requestJson<MacroDataSeriesChart>(macroDataChartUrl(code, range), { signal: abort.signal })
        if (abort.signal.aborted) return
        setData(body)
        router.replace(`${MACRO_DATA_HREF}?${new URLSearchParams({ series: code, range })}`, { scroll: false })
      } catch (e) {
        if (abort.signal.aborted) return
        setError(e instanceof RequestError ? e.message : "The macro series could not be loaded.")
      } finally {
        if (!abort.signal.aborted) setLoading(false)
      }
    },
    [router]
  )

  // the series grouped by country, in catalog order
  const groups = useMemo(() => {
    const out: { country: string; series: MacroDataSeries[] }[] = []
    for (const s of list.series) {
      const group = out.find((g) => g.country === s.country)
      if (group) group.series.push(s)
      else out.push({ country: s.country, series: [s] })
    }
    return out
  }, [list.series])

  const s = data.series
  const c = data.chart

  return (
    <div className="flex flex-col gap-4">
      {/* Controls */}
      <div className="flex flex-col gap-2 sm:flex-row sm:flex-wrap sm:items-center">
        <Select
          items={list.series.map((x) => ({ value: x.code, label: x.name }))}
          value={s.code}
          onValueChange={(v) => v && v !== s.code && load(v, c.range)}
        >
          <SelectTrigger aria-label="Macro series" className="w-full sm:w-[360px]">
            <SelectValue placeholder="Macro series" />
          </SelectTrigger>
          <SelectContent>
            {groups.map((g) => (
              <SelectGroup key={g.country}>
                <SelectLabel>{MACRO_COUNTRY_LABELS[g.country] ?? g.country}</SelectLabel>
                {g.series.map((x) => (
                  <SelectItem key={x.code} value={x.code} disabled={x.rows === 0}>
                    {x.name}
                    {x.rows === 0 && <span className="ml-1 text-xs text-muted-foreground">(no data)</span>}
                  </SelectItem>
                ))}
              </SelectGroup>
            ))}
          </SelectContent>
        </Select>
        <div role="group" aria-label="Range" className="flex flex-wrap gap-1">
          {MACRO_RANGES.map((r) => (
            <Button
              key={r}
              size="sm"
              variant={r === c.range ? "secondary" : "ghost"}
              aria-pressed={r === c.range}
              onClick={() => r !== c.range && load(s.code, r)}
            >
              {r}
            </Button>
          ))}
        </div>
        <span className="flex items-center gap-2 text-xs text-muted-foreground sm:ml-auto">
          {loading && <LoaderIcon className="size-3.5 animate-spin" aria-label="Loading" />}
          {list.series.length} series from Ingestion › Macro
        </span>
      </div>

      {error && (
        <p role="alert" className="rounded-lg border border-rose-600/30 bg-rose-600/5 px-3 py-2 text-sm text-rose-700 dark:text-rose-400">
          {error}
        </p>
      )}

      <div className={cn("grid items-start gap-4 transition-opacity xl:grid-cols-[minmax(0,1fr)_340px]", loading && "opacity-60")}>
        <div className="flex min-w-0 flex-col gap-4">
          <ChartCard data={data} />
          <StatsCard data={data} />
        </div>
        <aside aria-label="About the series" className="flex min-w-0 flex-col gap-4">
          <AboutCard series={s} />
          <RecentCard data={data} />
        </aside>
      </div>
    </div>
  )
}

// ------------------------------------------------------------------ chart

function ChartCard({ data }: { data: MacroDataSeriesChart }) {
  const s = data.series
  const c = data.chart
  const digits = digitsOf(s)
  const points = c.points
  const flow = c.kind === "FLOW"
  const last = c.stats?.last ?? null

  return (
    <Card>
      <CardHeader>
        <CardTitle>
          {s.name}
          <span className="ml-2 font-mono text-lg tabular-nums">{valueText(last?.value ?? s.lastValue, s)}</span>
        </CardTitle>
        <CardDescription>
          {last ? (
            <>
              Latest observation {formatDate(last.date)}
              {last.released !== last.date && <> · published {formatDate(last.released)}</>}
              {c.changes.length > 0 && " · "}
              {c.changes.map((ch, i) => (
                <span key={ch.period}>
                  {i > 0 && " · "}
                  {ch.period}{" "}
                  <span className={cn("tabular-nums", toneOf(ch.change))}>{changeText(ch.change, ch.pct, s)}</span>
                </span>
              ))}
            </>
          ) : (
            "Nothing stored yet"
          )}
        </CardDescription>
        <CardAction className="flex flex-wrap items-center gap-2">
          <Badge variant="outline">{FREQUENCY_LABEL[s.frequency] ?? s.frequency}</Badge>
          <Badge variant="outline" className={STATE_STYLE[s.state]}>
            {STATE_LABEL[s.state]}
          </Badge>
        </CardAction>
      </CardHeader>
      <CardContent className="space-y-2">
        {points.length < 2 ? (
          <EmptyState
            variant="chart"
            title={points.length === 1 ? "One observation in this range" : "No observation in this range"}
            description="Choose a longer range, or fetch the series on Ingestion › Macro."
          />
        ) : flow ? (
          <ChartContainer config={lineConfig} className="aspect-auto h-[460px] w-full">
            <BarChart data={points} margin={{ left: 4, right: 8, top: 8 }}>
              <CartesianGrid vertical={false} strokeDasharray="3 3" />
              <XAxis dataKey="date" tickLine={false} axisLine={false} minTickGap={48} tick={{ fontSize: 11 }} tickFormatter={(v: string) => formatDate(v)} />
              <YAxis tickLine={false} axisLine={false} width={64} tick={{ fontSize: 11 }} tickFormatter={(v: number) => axisTick(v, digits)} />
              <ChartTooltip content={<ChartTooltipContent labelFormatter={(v) => formatDate(String(v))} formatter={(v) => valueText(Number(v), s)} />} />
              <ReferenceLine y={0} stroke="var(--border)" />
              <Bar dataKey="value" name={s.code} isAnimationActive={false}>
                {points.map((p) => (
                  <Cell key={p.date} fill={p.value >= 0 ? POSITIVE : NEGATIVE} />
                ))}
              </Bar>
              {points.length > 60 && <Brush dataKey="date" height={24} travellerWidth={8} tickFormatter={(v: string) => formatDate(v)} stroke="var(--muted-foreground)" fill="var(--background)" />}
            </BarChart>
          </ChartContainer>
        ) : (
          <ChartContainer config={lineConfig} className="aspect-auto h-[460px] w-full">
            <LineChart data={points} margin={{ left: 4, right: 8, top: 8 }}>
              <CartesianGrid vertical={false} strokeDasharray="3 3" />
              <XAxis dataKey="date" tickLine={false} axisLine={false} minTickGap={48} tick={{ fontSize: 11 }} tickFormatter={(v: string) => formatDate(v)} />
              <YAxis tickLine={false} axisLine={false} width={64} domain={["auto", "auto"]} tick={{ fontSize: 11 }} tickFormatter={(v: number) => axisTick(v, digits)} />
              <ChartTooltip
                content={
                  <ChartTooltipContent
                    labelFormatter={(v, payload) => {
                      const released = (payload?.[0]?.payload as { released?: string } | undefined)?.released
                      return released && released !== v ? `${formatDate(String(v))} · published ${formatDate(released)}` : formatDate(String(v))
                    }}
                    formatter={(v) => valueText(Number(v), s)}
                  />
                }
              />
              {c.stats && (
                <ReferenceLine
                  y={c.stats.average}
                  stroke="var(--muted-foreground)"
                  strokeDasharray="4 4"
                  label={{ value: `avg ${formatNumber(c.stats.average, digits)}`, position: "insideTopLeft", fontSize: 11, fill: "var(--muted-foreground)" }}
                />
              )}
              <Line
                dataKey="value"
                name={s.code}
                type={s.frequency === "EVENT" ? "stepAfter" : "linear"}
                stroke="var(--color-value)"
                dot={points.length <= 40}
                strokeWidth={1.75}
                isAnimationActive={false}
              />
              {points.length > 60 && <Brush dataKey="date" height={24} travellerWidth={8} tickFormatter={(v: string) => formatDate(v)} stroke="var(--muted-foreground)" fill="var(--background)" />}
            </LineChart>
          </ChartContainer>
        )}
        <p className="text-xs text-muted-foreground">
          {c.from ? `${c.range}: ${formatDate(c.from)} – ${formatDate(c.to)}` : `Whole history: ${formatDate(c.stats?.first.date)} – ${formatDate(c.to)}`}
          {" "}(the range ends at the latest observation)
          {c.sampled === "WEEKLY" && (flow ? " · drawn weekly: the sum of each week" : " · drawn weekly: the last observation of each week")}
          {points.length > 60 && " · drag the handles below the chart to zoom"}
          {c.kind === "LEVEL" && c.stats && " · dashed line: the range's average"}
        </p>
      </CardContent>
    </Card>
  )
}

// ------------------------------------------------------------------ statistics

function StatsCard({ data }: { data: MacroDataSeriesChart }) {
  const s = data.series
  const st = data.chart.stats
  if (!st) return null
  const flow = data.chart.kind === "FLOW"
  const tiles: { label: string; value: string; hint?: string; tone?: string }[] = [
    { label: "Observations", value: formatNumber(st.observations, 0), hint: `${formatDate(st.first.date)} – ${formatDate(st.last.date)}` },
    { label: flow ? "Largest inflow" : "High", value: valueText(st.high.value, s), hint: formatDate(st.high.date) },
    { label: flow ? "Largest outflow" : "Low", value: valueText(st.low.value, s), hint: formatDate(st.low.date) },
    { label: flow ? "Average per observation" : "Average", value: valueText(st.average, s) },
    {
      label: flow ? "Net over the range" : "Change over the range",
      value: changeText(st.change, st.changePct, s),
      hint: flow ? undefined : `from ${valueText(st.first.value, s)}`,
      tone: toneOf(st.change),
    },
  ]
  return (
    <Card size="sm">
      <CardHeader>
        <CardTitle>In this range</CardTitle>
        <CardDescription>Every observation of the range, also when the chart is drawn weekly</CardDescription>
      </CardHeader>
      <CardContent>
        <dl className="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-5">
          {tiles.map((t) => (
            <div key={t.label} className="min-w-0 rounded-lg border p-3">
              <dt className="text-xs text-muted-foreground">{t.label}</dt>
              <dd className={cn("mt-1 truncate font-mono text-sm font-semibold tabular-nums", t.tone)}>{t.value}</dd>
              {t.hint && <dd className="mt-0.5 truncate text-xs text-muted-foreground">{t.hint}</dd>}
            </div>
          ))}
        </dl>
      </CardContent>
    </Card>
  )
}

// ------------------------------------------------------------------ about

function AboutCard({ series: s }: { series: MacroDataSeries }) {
  const rows: [string, ReactNode][] = [
    ["Code", <span key="c" className="font-mono">{s.code}</span>],
    ["Country", MACRO_COUNTRY_LABELS[s.country] ?? s.country],
    ["Category", s.category.charAt(0) + s.category.slice(1).toLowerCase()],
    ["Unit", s.unit],
    ["Frequency", FREQUENCY_LABEL[s.frequency] ?? s.frequency],
    [
      "Source",
      s.sourceUrl ? (
        <a key="s" href={s.sourceUrl} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 hover:underline">
          {s.source} <ExternalLinkIcon className="size-3" />
        </a>
      ) : (
        s.source
      ),
    ],
    ["Stored", `${formatNumber(s.rows, 0)} observations · ${formatDate(s.firstDate)} – ${formatDate(s.lastDate)}`],
    ["Last published", formatDate(s.lastRelease)],
    ["Read by", s.usedBy.length > 0 ? s.usedBy.join(", ") : EMPTY],
  ]
  return (
    <Card size="sm">
      <CardHeader>
        <CardTitle>About the series</CardTitle>
        {s.description && <CardDescription>{s.description}</CardDescription>}
      </CardHeader>
      <CardContent>
        <dl className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-1.5 text-sm">
          {rows.map(([label, value]) => (
            <div key={label} className="contents">
              <dt className="text-muted-foreground">{label}</dt>
              <dd className="min-w-0 break-words">{value}</dd>
            </div>
          ))}
        </dl>
        {(s.state === "BEHIND" || s.state === "FAILING") && (
          <p className="mt-3 flex items-start gap-2 text-xs text-amber-800 dark:text-amber-300">
            <CircleAlertIcon className="mt-px size-3.5 shrink-0" />
            Newer observations should be public by now: update the series on Ingestion › Macro.
          </p>
        )}
      </CardContent>
    </Card>
  )
}

function RecentCard({ data }: { data: MacroDataSeriesChart }) {
  const s = data.series
  const recent = data.chart.recent
  if (recent.length === 0) return null
  const flow = data.chart.kind === "FLOW"
  return (
    <Card size="sm">
      <CardHeader>
        <CardTitle>Latest observations</CardTitle>
        <CardDescription>The {recent.length} most recent, newest first</CardDescription>
      </CardHeader>
      <CardContent className="px-0">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead className="pl-4">Date</TableHead>
              <TableHead className="text-right">Value</TableHead>
              {!flow && <TableHead className="pr-4 text-right">Change</TableHead>}
            </TableRow>
          </TableHeader>
          <TableBody>
            {recent.map((r) => (
              <TableRow key={r.date}>
                <TableCell className="pl-4">
                  <div className="tabular-nums">{formatDate(r.date)}</div>
                  {r.released !== r.date && <div className="text-xs text-muted-foreground">published {formatDate(r.released)}</div>}
                </TableCell>
                <TableCell className={cn("text-right font-mono tabular-nums", flow && toneOf(r.value))}>
                  {formatNumber(r.value, digitsOf(s))}
                </TableCell>
                {!flow && (
                  <TableCell className={cn("pr-4 text-right font-mono tabular-nums", toneOf(r.change))}>
                    {r.change === null ? EMPTY : `${r.change > 0 ? "+" : ""}${formatNumber(r.change, digitsOf(s))}`}
                  </TableCell>
                )}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  )
}
