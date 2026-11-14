"use client"

import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { useRouter } from "next/navigation"
import {
  Bar,
  BarChart,
  CartesianGrid,
  Line,
  LineChart,
  ReferenceLine,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts"
import {
  CalendarClockIcon,
  CircleAlertIcon,
  LoaderIcon,
  PencilIcon,
  PlusIcon,
  SaveIcon,
  TrendingDownIcon,
  TrendingUpIcon,
  WalletIcon,
} from "lucide-react"

import { EmptyState } from "@/components/empty-state"
import { YearSheet } from "@/components/portfolio/performance-year-sheet"
import { StatTile } from "@/components/stat-tile"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { ChartContainer, type ChartConfig } from "@/components/ui/chart"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { RequestError, requestJson } from "@/lib/client-api"
import { EMPTY, formatDate, formatNumber, formatPercent } from "@/lib/format"
import { PORTFOLIO_PERFORMANCE_HREF } from "@/lib/portfolio"
import {
  PERFORMANCE_ALL,
  PERFORMANCE_SERIES,
  performanceQuery,
  type Performance,
  type PerformancePoint,
  type PerformanceSaved,
  type PerformanceSnapshot,
  type PerformanceYear,
} from "@/lib/portfolio-performance"
import { cn } from "@/lib/utils"

const chartConfig = {
  portfolio: { label: "Portfolio", color: "var(--series-1)" },
  ihsg: { label: "IHSG", color: "var(--series-2)" },
  lq45: { label: "LQ45", color: "var(--series-3)" },
} satisfies ChartConfig

type SeriesKey = (typeof PERFORMANCE_SERIES)[number]["key"]

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]

/** "2026-11-13" -> its UTC midnight in ms (no time-zone shifts). */
function toMs(iso: string): number {
  const [y, m, d] = iso.slice(0, 10).split("-").map(Number)
  return Date.UTC(y, m - 1, d)
}

function signedPct(v: number | null | undefined, digits = 2): string {
  if (v === null || v === undefined || !Number.isFinite(v)) return EMPTY
  return `${v > 0 ? "+" : ""}${formatPercent(v, digits)}`
}

function rp(v: number | null | undefined): string {
  return v === null || v === undefined || !Number.isFinite(v) ? EMPTY : formatNumber(v, 0)
}

function signedRp(v: number | null | undefined): string {
  if (v === null || v === undefined || !Number.isFinite(v)) return EMPTY
  return `${v > 0 ? "+" : ""}${formatNumber(v, 0)}`
}

function toneOf(v: number | null | undefined): string {
  if (v === null || v === undefined || v === 0) return "text-muted-foreground"
  return v > 0 ? "text-emerald-700 dark:text-emerald-400" : "text-rose-700 dark:text-rose-400"
}

/** "17:05" in WIB of an ISO timestamp. */
function wib(iso: string | null | undefined): string {
  if (!iso) return EMPTY
  return new Date(iso).toLocaleTimeString("en-GB", { timeZone: "Asia/Jakarta", hour: "2-digit", minute: "2-digit" })
}

/** "Mon 16 Nov 2026, 17:00 WIB" */
function wibDateTime(iso: string): string {
  const d = new Date(iso)
  const day = d.toLocaleDateString("en-GB", { timeZone: "Asia/Jakarta", weekday: "short" })
  const date = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Jakarta" }).format(d)
  return `${day} ${formatDate(date)}, ${wib(iso)} WIB`
}

const KIND_LABEL: Record<string, string> = {
  BASE: "start of the range",
  YEAR_END: "year end (the year's row)",
  SNAPSHOT: "daily snapshot",
  LIVE: "now, not saved yet",
}

const END_LABEL: Record<string, string> = { LIVE: "Now", SNAPSHOT: "Snapshot", TYPED: "Sheet" }

/**
 * Portfolio > Performance: the sheet tracking_performance of instruction/Position Portfolio.xlsx. The range (all years
 * or one), the change of the portfolio, IHSG and LQ45 since the last close of the year before on one chart (as
 * Macro > Data draws a series), the years compared side by side, the table of the years (editable) and the daily
 * snapshots saved at or after 17:00 WIB. A change of range reloads the page's data and keeps it in the URL.
 */
export function PerformanceClient({ initial }: { initial: Performance }) {
  const router = useRouter()
  const [data, setData] = useState<Performance>(initial)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [editing, setEditing] = useState<{ year: number; row: PerformanceYear | null } | null>(null)
  const controller = useRef<AbortController | null>(null)

  useEffect(() => () => controller.current?.abort(), [])

  const range = data.chart.range

  const load = useCallback(
    async (next: string) => {
      controller.current?.abort()
      const abort = new AbortController()
      controller.current = abort
      setLoading(true)
      setError(null)
      try {
        const { body } = await requestJson<Performance>(`/api/portfolio/performance${performanceQuery(next)}`, {
          signal: abort.signal,
        })
        if (abort.signal.aborted) return
        setData(body)
        router.replace(`${PORTFOLIO_PERFORMANCE_HREF}?${new URLSearchParams({ range: next })}`, { scroll: false })
      } catch (e) {
        if (abort.signal.aborted) return
        setError(e instanceof RequestError ? e.message : "The performance could not be loaded.")
      } finally {
        if (!abort.signal.aborted) setLoading(false)
      }
    },
    [router]
  )

  async function saveToday() {
    if (saving) return
    setSaving(true)
    setError(null)
    setMessage(null)
    try {
      const { body } = await requestJson<PerformanceSaved>(
        `/api/portfolio/performance/snapshots${performanceQuery(range)}`,
        { method: "POST" }
      )
      setData(body.performance)
      setMessage(body.message)
    } catch (e) {
      setError(e instanceof RequestError ? e.message : "Today's snapshot could not be saved.")
    } finally {
      setSaving(false)
    }
  }

  const current = data.years.find((y) => y.status === "CURRENT") ?? null
  const latest = data.live ?? data.snapshots[0] ?? null

  return (
    <div className="flex flex-col gap-4">
      {/* Controls */}
      <div className="flex flex-col gap-2 sm:flex-row sm:flex-wrap sm:items-center">
        <div role="group" aria-label="Range" className="flex flex-wrap gap-1">
          {data.ranges.map((r) => (
            <Button
              key={r}
              size="sm"
              variant={r === range ? "secondary" : "ghost"}
              aria-pressed={r === range}
              onClick={() => r !== range && load(r)}
            >
              {r === PERFORMANCE_ALL ? `${data.ranges[1]} – ${data.ranges[data.ranges.length - 1]}` : r}
            </Button>
          ))}
        </div>
        <span className="flex items-center gap-2 text-xs text-muted-foreground sm:ml-auto">
          {loading && <LoaderIcon className="size-3.5 animate-spin" aria-label="Loading" />}
          Portfolio vs IHSG and LQ45 · sheet tracking_performance
        </span>
      </div>

      {error && (
        <p role="alert" className="rounded-lg border border-rose-600/30 bg-rose-600/5 px-3 py-2 text-sm text-rose-700 dark:text-rose-400">
          {error}
        </p>
      )}
      {message && (
        <p role="status" className="rounded-lg border border-emerald-600/30 bg-emerald-600/5 px-3 py-2 text-sm text-emerald-800 dark:text-emerald-300">
          {message}
        </p>
      )}

      {/* Metrics */}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-5">
        <StatTile
          label={`Portfolio ${data.currentYear} (POT G/L %)`}
          value={<span className={toneOf(current?.profitPct)}>{signedPct(current?.profitPct)}</span>}
          hint={current?.profit != null ? `Rp ${signedRp(current.profit)} · ${END_LABEL[current.endFrom ?? ""] ?? EMPTY}` : "no start capital yet"}
          icon={(current?.profitPct ?? 0) >= 0 ? TrendingUpIcon : TrendingDownIcon}
          tone={(current?.profitPct ?? 0) >= 0 ? "positive" : "negative"}
        />
        <StatTile
          label={`IHSG ${data.currentYear} YTD`}
          value={<span className={toneOf(current?.ihsg)}>{signedPct(current?.ihsg)}</span>}
          hint={latest?.ihsgClose != null ? `${formatNumber(latest.ihsgClose, 2)} · ${formatDate(latest.ihsgDate)}` : undefined}
          icon={(current?.ihsg ?? 0) >= 0 ? TrendingUpIcon : TrendingDownIcon}
          tone="muted"
        />
        <StatTile
          label={`LQ45 ${data.currentYear} YTD`}
          value={<span className={toneOf(current?.lq45)}>{signedPct(current?.lq45)}</span>}
          hint={latest?.lq45Close != null ? `${formatNumber(latest.lq45Close, 2)} · ${formatDate(latest.lq45Date)}` : undefined}
          icon={(current?.lq45 ?? 0) >= 0 ? TrendingUpIcon : TrendingDownIcon}
          tone="muted"
        />
        <StatTile
          label="Market value + cash"
          value={latest ? `Rp ${rp(latest.totalValue)}` : EMPTY}
          hint={latest ? `stocks ${signedPct(latest.profitLossPct)} vs average value` : undefined}
          icon={WalletIcon}
        />
        <StatTile
          label={data.saving.savedToday ? "Saved today · next snapshot" : "Next snapshot"}
          value={wibDateTime(data.saving.next)}
          hint={`${data.saving.saved} saved${data.saving.lastSaved ? ` · last ${formatDate(data.saving.lastSaved)}` : ""}${data.saving.scheduled ? "" : " · schedule off"}`}
          icon={CalendarClockIcon}
          tone="muted"
        />
      </div>

      <div className={cn("flex flex-col gap-4 transition-opacity", loading && "opacity-60")}>
        <ChartCard data={data} />
        <YearsChartCard years={data.years} />
        <YearsTableCard years={data.years} onEdit={(year, row) => setEditing({ year, row })} />
        <SnapshotsCard data={data} saving={saving} onSave={saveToday} />
        {data.notes.length > 0 && (
          <ul className="space-y-1 text-xs text-amber-800 dark:text-amber-300">
            {data.notes.map((n) => (
              <li key={n} className="flex items-start gap-2">
                <CircleAlertIcon className="mt-px size-3.5 shrink-0" />
                {n}
              </li>
            ))}
          </ul>
        )}
      </div>

      <YearSheet
        open={editing !== null}
        year={editing?.year ?? data.currentYear}
        row={editing?.row ?? null}
        range={range}
        onClose={() => setEditing(null)}
        onSaved={(p) => setData(p)}
      />
    </div>
  )
}

// ------------------------------------------------------------------ the chart

/** The axis ticks: 1 January of each year of a multi-year range, the first of each month of one year. */
function ticksOf(from: string, to: string): number[] {
  const a = Number(from.slice(0, 4))
  const b = Number(to.slice(0, 4))
  const out: number[] = []
  if (a === b) {
    for (let m = 0; m < 12; m++) out.push(Date.UTC(a, m, 1))
    out.push(Date.UTC(a, 11, 31))
  } else {
    for (let y = a; y <= b; y++) out.push(Date.UTC(y, 0, 1))
    out.push(Date.UTC(b, 11, 31))
  }
  return out
}

function tickLabel(ms: number, oneYear: boolean): string {
  const d = new Date(ms)
  if (d.getUTCMonth() === 11 && d.getUTCDate() === 31) return oneYear ? "31 Dec" : `end ${d.getUTCFullYear()}`
  return oneYear ? MONTHS[d.getUTCMonth()] : String(d.getUTCFullYear())
}

type Row = PerformancePoint & { t: number }

function ChartCard({ data }: { data: Performance }) {
  const c = data.chart
  const oneYear = c.range !== PERFORMANCE_ALL
  const rows: Row[] = useMemo(() => c.points.map((p) => ({ ...p, t: toMs(p.date) })), [c.points])
  const ticks = useMemo(() => ticksOf(c.from, c.to), [c.from, c.to])
  const domain: [number, number] = [toMs(c.base), toMs(c.to)]
  const portfolioPoints = rows.filter((r) => r.portfolio !== null).length
  const last: Record<SeriesKey, number | null> = { portfolio: c.portfolio, ihsg: c.ihsg, lq45: c.lq45 }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Change since {formatDate(c.base)}</CardTitle>
        <CardDescription>
          {oneYear ? c.range : `${c.from.slice(0, 4)} – ${c.to.slice(0, 4)}`}: the portfolio (each year&apos;s POT G/L %
          chained, the daily snapshots within a year), IHSG and LQ45 (daily closes), all from 0% at the last close
          of {Number(c.from.slice(0, 4)) - 1}. The axis runs to {formatDate(c.to)}.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {/* legend with each series' change over the range */}
        <ul className="flex flex-wrap gap-x-5 gap-y-1 text-sm" aria-label="Series">
          {PERFORMANCE_SERIES.map((s) => (
            <li key={s.key} className="flex items-center gap-2">
              <span className="inline-block h-0.5 w-4 rounded-full" style={{ background: s.color }} aria-hidden />
              <span className="text-muted-foreground">{s.label}</span>
              <span className={cn("font-mono tabular-nums font-semibold", toneOf(last[s.key]))}>{signedPct(last[s.key])}</span>
            </li>
          ))}
          {c.latest && <li className="text-xs text-muted-foreground self-center">portfolio to {formatDate(c.latest)}</li>}
        </ul>
        {rows.length < 2 ? (
          <EmptyState variant="chart" title="Nothing to draw yet" description="No IHSG / LQ45 close and no portfolio figures in this range." />
        ) : (
          <ChartContainer config={chartConfig} className="aspect-auto h-[460px] w-full">
            <LineChart data={rows} margin={{ left: 4, right: 12, top: 8 }}>
              <CartesianGrid vertical={false} strokeDasharray="3 3" />
              <XAxis
                dataKey="t"
                type="number"
                scale="time"
                domain={domain}
                ticks={ticks}
                tickLine={false}
                axisLine={false}
                tick={{ fontSize: 11 }}
                tickFormatter={(v: number) => tickLabel(v, oneYear)}
              />
              <YAxis
                tickLine={false}
                axisLine={false}
                width={56}
                domain={["auto", "auto"]}
                tick={{ fontSize: 11 }}
                tickFormatter={(v: number) => formatPercent(v, 0)}
              />
              <ReferenceLine y={0} stroke="var(--muted-foreground)" strokeOpacity={0.5} />
              <Tooltip content={<PointTooltip />} cursor={{ stroke: "var(--muted-foreground)", strokeDasharray: "3 3" }} />
              <Line
                dataKey="ihsg"
                name="IHSG"
                stroke="var(--color-ihsg)"
                strokeWidth={2}
                dot={false}
                connectNulls
                isAnimationActive={false}
              />
              <Line
                dataKey="lq45"
                name="LQ45"
                stroke="var(--color-lq45)"
                strokeWidth={2}
                dot={false}
                connectNulls
                isAnimationActive={false}
              />
              <Line
                dataKey="portfolio"
                name="Portfolio"
                stroke="var(--color-portfolio)"
                strokeWidth={2.5}
                connectNulls
                isAnimationActive={false}
                dot={(props: { cx?: number; cy?: number; index?: number; payload?: Row }) => {
                  const p = props.payload
                  const show = p && p.portfolio !== null && (p.kind === "YEAR_END" || p.kind === "LIVE" || portfolioPoints <= 40)
                  return show && props.cx != null && props.cy != null ? (
                    <circle
                      key={`dot-${props.index}`}
                      cx={props.cx}
                      cy={props.cy}
                      r={4}
                      fill="var(--color-portfolio)"
                      stroke="var(--card)"
                      strokeWidth={2}
                    />
                  ) : (
                    <g key={`dot-${props.index}`} />
                  )
                }}
              />
            </LineChart>
          </ChartContainer>
        )}
        <p className="text-xs text-muted-foreground">
          Dots: the portfolio at each year end (the year&apos;s row) and now. Its line inside a year follows the daily
          snapshots saved at {data.saving.saveFrom} WIB. Choose a year above to see it closer.
        </p>
        {c.notes.length > 0 && (
          <ul className="space-y-1 text-xs text-amber-800 dark:text-amber-300">
            {c.notes.map((n) => (
              <li key={n} className="flex items-start gap-2">
                <CircleAlertIcon className="mt-px size-3.5 shrink-0" />
                {n}
              </li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  )
}

/** The tooltip of a day: the three series' change since the base, those without a value that day left out. */
function PointTooltip({ active, payload }: { active?: boolean; payload?: { payload?: Row }[] }) {
  const p = payload?.[0]?.payload
  if (!active || !p) return null
  const values: Record<SeriesKey, number | null> = { portfolio: p.portfolio, ihsg: p.ihsg, lq45: p.lq45 }
  return (
    <div className="grid min-w-44 gap-1.5 rounded-lg border border-border/50 bg-background px-2.5 py-1.5 text-xs shadow-xl">
      <div className="font-medium">{formatDate(p.date)}</div>
      {PERFORMANCE_SERIES.filter((s) => values[s.key] !== null).map((s) => (
        <div key={s.key} className="flex items-center gap-2">
          <span className="size-2.5 shrink-0 rounded-[2px]" style={{ background: s.color }} aria-hidden />
          <span className="flex-1 text-muted-foreground">{s.label}</span>
          <span className="font-mono tabular-nums">{signedPct(values[s.key])}</span>
        </div>
      ))}
      {p.portfolio !== null && p.kind && <div className="text-muted-foreground">Portfolio: {KIND_LABEL[p.kind]}</div>}
    </div>
  )
}

// ------------------------------------------------------------------ years

function YearsChartCard({ years }: { years: PerformanceYear[] }) {
  const rows = years
    .filter((y) => y.status !== "FUTURE")
    .map((y) => ({ year: String(y.year), portfolio: y.profitPct, ihsg: y.ihsg, lq45: y.lq45, status: y.status }))
  if (rows.every((r) => r.portfolio === null && r.ihsg === null && r.lq45 === null)) return null
  return (
    <Card>
      <CardHeader>
        <CardTitle>Year by year</CardTitle>
        <CardDescription>
          Each year on its own: the portfolio&apos;s POT G/L % against IHSG and LQ45 over the same calendar year (this
          year to date).
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        <ul className="flex flex-wrap gap-x-5 gap-y-1 text-sm" aria-label="Series">
          {PERFORMANCE_SERIES.map((s) => (
            <li key={s.key} className="flex items-center gap-2">
              <span className="size-2.5 rounded-[2px]" style={{ background: s.color }} aria-hidden />
              <span className="text-muted-foreground">{s.label}</span>
            </li>
          ))}
        </ul>
        <ChartContainer config={chartConfig} className="aspect-auto h-[300px] w-full">
          <BarChart data={rows} margin={{ left: 4, right: 12, top: 8 }} barGap={2} barCategoryGap="24%">
            <CartesianGrid vertical={false} strokeDasharray="3 3" />
            <XAxis dataKey="year" tickLine={false} axisLine={false} tick={{ fontSize: 11 }} tickFormatter={(v: string) => (rows.find((r) => r.year === v)?.status === "CURRENT" ? `${v} YTD` : v)} />
            <YAxis tickLine={false} axisLine={false} width={56} tick={{ fontSize: 11 }} tickFormatter={(v: number) => formatPercent(v, 0)} />
            <ReferenceLine y={0} stroke="var(--muted-foreground)" strokeOpacity={0.5} />
            <Tooltip
              cursor={{ fill: "var(--muted)", opacity: 0.4 }}
              content={({ active, payload, label }) => {
                if (!active || !payload?.length) return null
                const r = payload[0]?.payload as (typeof rows)[number]
                const values: Record<SeriesKey, number | null> = { portfolio: r.portfolio, ihsg: r.ihsg, lq45: r.lq45 }
                return (
                  <div className="grid min-w-40 gap-1.5 rounded-lg border border-border/50 bg-background px-2.5 py-1.5 text-xs shadow-xl">
                    <div className="font-medium">{label}{r.status === "CURRENT" ? " (to date)" : ""}</div>
                    {PERFORMANCE_SERIES.map((s) => (
                      <div key={s.key} className="flex items-center gap-2">
                        <span className="size-2.5 shrink-0 rounded-[2px]" style={{ background: s.color }} aria-hidden />
                        <span className="flex-1 text-muted-foreground">{s.label}</span>
                        <span className="font-mono tabular-nums">{signedPct(values[s.key])}</span>
                      </div>
                    ))}
                  </div>
                )
              }}
            />
            <Bar dataKey="portfolio" name="Portfolio" fill="var(--color-portfolio)" radius={2} isAnimationActive={false} />
            <Bar dataKey="ihsg" name="IHSG" fill="var(--color-ihsg)" radius={2} isAnimationActive={false} />
            <Bar dataKey="lq45" name="LQ45" fill="var(--color-lq45)" radius={2} isAnimationActive={false} />
          </BarChart>
        </ChartContainer>
      </CardContent>
    </Card>
  )
}

function YearsTableCard({
  years,
  onEdit,
}: {
  years: PerformanceYear[]
  onEdit: (year: number, row: PerformanceYear | null) => void
}) {
  const next = Math.max(...years.map((y) => y.year)) + 1
  return (
    <Card>
      <CardHeader>
        <CardTitle>Years</CardTitle>
        <CardDescription>
          As the sheet: POT G/L = market value + cash − start capital − new investment; POT G/L % = POT G/L / start
          capital; Deviden % = dividend / (market value + cash). The start capital is the market value + cash at the
          end of the year before. IHSG / LQ45 from the stored daily closes (the sheet&apos;s own figure in the
          tooltip).
        </CardDescription>
        <CardAction>
          <Button size="sm" variant="outline" onClick={() => onEdit(next, null)}>
            <PlusIcon /> Add {next}
          </Button>
        </CardAction>
      </CardHeader>
      <CardContent className="px-0">
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="pl-4">Year</TableHead>
                <TableHead className="text-right">Start capital</TableHead>
                <TableHead className="text-right">New investment</TableHead>
                <TableHead className="text-right">Cash</TableHead>
                <TableHead className="text-right">Market value</TableHead>
                <TableHead className="text-right">Avg value</TableHead>
                <TableHead className="text-right">POT G/L</TableHead>
                <TableHead className="text-right">POT G/L %</TableHead>
                <TableHead className="text-right">IHSG</TableHead>
                <TableHead className="text-right">LQ45</TableHead>
                <TableHead className="text-right">vs IHSG</TableHead>
                <TableHead className="text-right">Dividend</TableHead>
                <TableHead className="text-right">Dividend %</TableHead>
                <TableHead className="pr-4 text-right">
                  <span className="sr-only">Edit</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {years.map((y) => (
                <TableRow key={y.year} className={cn(y.status === "FUTURE" && "text-muted-foreground")}>
                  <TableCell className="pl-4">
                    <div className="flex items-center gap-2 font-medium tabular-nums">
                      {y.year}
                      {y.status === "CURRENT" && <Badge variant="outline">to date</Badge>}
                    </div>
                    <div className="text-xs text-muted-foreground">
                      {y.endFrom ? `${END_LABEL[y.endFrom]}${y.endDate ? ` · ${formatDate(y.endDate)}` : ""}` : y.status === "FUTURE" ? "not begun" : "no figures"}
                    </div>
                  </TableCell>
                  <TableCell className="text-right font-mono tabular-nums" title={y.startFrom === "YEAR_BEFORE" ? "market value + cash at the end of the year before" : undefined}>
                    {rp(y.startCapital)}
                    {y.startFrom === "YEAR_BEFORE" && <div className="text-xs text-muted-foreground">from {y.year - 1}</div>}
                  </TableCell>
                  <TableCell className="text-right font-mono tabular-nums">{rp(y.newInvestment)}</TableCell>
                  <TableCell className="text-right font-mono tabular-nums">{rp(y.cash)}</TableCell>
                  <TableCell className="text-right font-mono tabular-nums">{rp(y.marketValue)}</TableCell>
                  <TableCell className="text-right font-mono tabular-nums">{rp(y.averageValue)}</TableCell>
                  <TableCell className={cn("text-right font-mono tabular-nums", toneOf(y.profit))}>{signedRp(y.profit)}</TableCell>
                  <TableCell className={cn("text-right font-mono tabular-nums font-semibold", toneOf(y.profitPct))}>{signedPct(y.profitPct)}</TableCell>
                  <TableCell
                    className={cn("text-right font-mono tabular-nums", toneOf(y.ihsg))}
                    title={y.ihsgRecorded !== null ? `recorded in the sheet: ${signedPct(y.ihsgRecorded)}` : undefined}
                  >
                    {signedPct(y.ihsg)}
                  </TableCell>
                  <TableCell
                    className={cn("text-right font-mono tabular-nums", toneOf(y.lq45))}
                    title={y.lq45Recorded !== null ? `recorded in the sheet: ${signedPct(y.lq45Recorded)}` : undefined}
                  >
                    {signedPct(y.lq45)}
                  </TableCell>
                  <TableCell className={cn("text-right font-mono tabular-nums", toneOf(y.portfolioVsIhsg))}>
                    {y.portfolioVsIhsg === null ? EMPTY : `${y.portfolioVsIhsg > 0 ? "+" : ""}${formatNumber(y.portfolioVsIhsg * 100, 2)} pts`}
                  </TableCell>
                  <TableCell className="text-right font-mono tabular-nums">{rp(y.dividend)}</TableCell>
                  <TableCell className="text-right font-mono tabular-nums">{formatPercent(y.dividendPct, 2)}</TableCell>
                  <TableCell className="pr-4 text-right">
                    <Button size="icon-sm" variant="ghost" aria-label={`Edit ${y.year}`} onClick={() => onEdit(y.year, y)}>
                      <PencilIcon />
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      </CardContent>
    </Card>
  )
}

// ------------------------------------------------------------------ snapshots

function SnapshotsCard({ data, saving, onSave }: { data: Performance; saving: boolean; onSave: () => void }) {
  const s = data.saving
  const rows: PerformanceSnapshot[] = data.snapshots
  return (
    <Card>
      <CardHeader>
        <CardTitle>Daily snapshots</CardTitle>
        <CardDescription>
          Saved once every IDX trading day at or after {s.saveFrom} WIB, the end of the session
          {s.scheduled ? " (automatically, every 15 minutes until 23:45 when the backend was down at 17:00)" : " (the schedule is off: use Save today)"}:
          market value, average value, the profit or loss, and the IHSG / LQ45 change of the day and of the year.
          Exchange holidays are skipped.
        </CardDescription>
        <CardAction>
          <Button size="sm" onClick={onSave} disabled={!s.canSaveNow || saving} title={s.reason ?? undefined}>
            {saving ? <LoaderIcon className="animate-spin" /> : <SaveIcon />}
            {s.savedToday ? "Save today again" : "Save today"}
          </Button>
        </CardAction>
      </CardHeader>
      <CardContent className="space-y-2 px-0">
        {s.reason && <p className="px-4 text-xs text-muted-foreground">{s.reason}</p>}
        {rows.length === 0 ? (
          <div className="px-4">
            <EmptyState
              variant="generic"
              title="No snapshot yet"
              description={`The first is saved ${wibDateTime(s.next)}.`}
            />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="pl-4">Date</TableHead>
                  <TableHead className="text-right">Market value</TableHead>
                  <TableHead className="text-right">Avg value</TableHead>
                  <TableHead className="text-right">P/L</TableHead>
                  <TableHead className="text-right">P/L %</TableHead>
                  <TableHead className="text-right">Day</TableHead>
                  <TableHead className="text-right">POT G/L % (YTD)</TableHead>
                  <TableHead className="text-right">IHSG</TableHead>
                  <TableHead className="text-right">IHSG day</TableHead>
                  <TableHead className="text-right">IHSG YTD</TableHead>
                  <TableHead className="text-right">LQ45</TableHead>
                  <TableHead className="text-right">LQ45 day</TableHead>
                  <TableHead className="pr-4 text-right">LQ45 YTD</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((r) => (
                  <TableRow key={r.date}>
                    <TableCell className="pl-4">
                      <div className="tabular-nums font-medium">{formatDate(r.date)}</div>
                      <div className="text-xs text-muted-foreground">
                        {wib(r.capturedAt)} WIB · {r.source === "MANUAL" ? "Save today" : "schedule"}
                        {r.pricedToday < r.positions && ` · ${r.pricedToday}/${r.positions} priced today`}
                      </div>
                    </TableCell>
                    <TableCell className="text-right font-mono tabular-nums">{rp(r.marketValue)}</TableCell>
                    <TableCell className="text-right font-mono tabular-nums">{rp(r.averageValue)}</TableCell>
                    <TableCell className={cn("text-right font-mono tabular-nums", toneOf(r.profitLoss))}>{signedRp(r.profitLoss)}</TableCell>
                    <TableCell className={cn("text-right font-mono tabular-nums", toneOf(r.profitLossPct))}>{signedPct(r.profitLossPct)}</TableCell>
                    <TableCell className={cn("text-right font-mono tabular-nums", toneOf(r.dayGainPct))}>{signedPct(r.dayGainPct)}</TableCell>
                    <TableCell className={cn("text-right font-mono tabular-nums font-semibold", toneOf(r.ytdProfitPct))}>{signedPct(r.ytdProfitPct)}</TableCell>
                    <TableCell className="text-right font-mono tabular-nums">{r.ihsgClose === null ? EMPTY : formatNumber(r.ihsgClose, 2)}</TableCell>
                    <TableCell className={cn("text-right font-mono tabular-nums", toneOf(r.ihsgChangePct))}>{signedPct(r.ihsgChangePct)}</TableCell>
                    <TableCell className={cn("text-right font-mono tabular-nums", toneOf(r.ihsgYtdPct))}>{signedPct(r.ihsgYtdPct)}</TableCell>
                    <TableCell className="text-right font-mono tabular-nums">{r.lq45Close === null ? EMPTY : formatNumber(r.lq45Close, 2)}</TableCell>
                    <TableCell className={cn("text-right font-mono tabular-nums", toneOf(r.lq45ChangePct))}>{signedPct(r.lq45ChangePct)}</TableCell>
                    <TableCell className={cn("pr-4 text-right font-mono tabular-nums", toneOf(r.lq45YtdPct))}>{signedPct(r.lq45YtdPct)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </CardContent>
    </Card>
  )
}

