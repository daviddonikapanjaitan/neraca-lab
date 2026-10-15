"use client"

import { useRef, useState } from "react"
import { useRouter } from "next/navigation"
import { Line, LineChart, XAxis, YAxis } from "recharts"
import {
  CalendarCheckIcon,
  ChartLineIcon,
  CircleAlertIcon,
  CircleCheckIcon,
  ExternalLinkIcon,
  HistoryIcon,
  LoaderIcon,
  RefreshCwIcon,
  UploadIcon,
} from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { ChartContainer, ChartTooltip, ChartTooltipContent, type ChartConfig } from "@/components/ui/chart"
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from "@/components/ui/sheet"
import { Skeleton } from "@/components/ui/skeleton"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { requestJson } from "@/lib/client-api"
import { EMPTY, formatDate, formatNumber } from "@/lib/format"
import type { IngestionJob, MacroHistory, MacroResult, MacroSeriesStatus, MacroState, MacroStatus, MacroUpdate } from "@/lib/types"
import { cn } from "@/lib/utils"

type Notice = { tone: "success" | "error"; text: string }

const GROUPS: { title: string; countries: string[] }[] = [
  { title: "Indonesia", countries: ["ID"] },
  { title: "United States", countries: ["US"] },
  { title: "Global and China", countries: ["GLOBAL", "CN"] },
]

const STATE_META: Record<MacroState, { label: string; className: string }> = {
  UP_TO_DATE: { label: "Up to date", className: "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400" },
  BEHIND: { label: "Behind", className: "bg-amber-500/10 text-amber-600 dark:text-amber-400" },
  NEVER_FETCHED: { label: "Not fetched", className: "border-border bg-muted text-muted-foreground" },
  FAILING: { label: "Failing", className: "bg-destructive/10 text-destructive" },
  MANUAL: { label: "CSV upload", className: "bg-sky-500/10 text-sky-600 dark:text-sky-400" },
}

const PRESET_LABEL: Record<string, string> = { DRUCKENMILLER: "Druckenmiller", TUDOR_JONES: "Tudor Jones" }

/** 5.75 % / 146,320 USD mn / 102.55 index: the value with its unit */
function valueWithUnit(value: number | null, unit: string): string {
  if (value === null) return EMPTY
  const digits = Math.abs(value) >= 1000 ? 0 : 2
  return unit === "%" ? `${formatNumber(value, digits)}%` : `${formatNumber(value, digits)} ${unit}`
}

const historyConfig = {
  value: { label: "Value", theme: { light: "oklch(0.371 0 0)", dark: "oklch(0.87 0 0)" } },
} satisfies ChartConfig

/**
 * Ingestion › Macro: every macro series of the Druckenmiller and Paul Tudor Jones views with what is stored of it,
 * "Update all macro data" (every series whose next release is due), one series fetched again or with its full history,
 * a CSV upload for the series without a source a program may read, and the history of a series.
 */
export function MacroCard({ status, onSubmitted }: { status: MacroStatus; onSubmitted: (job: IngestionJob) => void }) {
  const router = useRouter()
  const [pending, setPending] = useState<string | null>(null)
  const [notice, setNotice] = useState<Notice | null>(null)
  const [history, setHistory] = useState<HistoryState | null>(null)
  const toFetch = status.behind + status.neverFetched + status.failing

  async function updateAll() {
    if (pending) return
    setPending("all")
    setNotice(null)
    try {
      const { body } = await requestJson<MacroUpdate>("/api/macro/updates", { method: "POST" })
      const parts = [body.queued > 0 ? `Queued ${body.queued} series.` : "No series needed new data."]
      if (body.upToDate > 0) parts.push(`${body.upToDate} up to date.`)
      if (body.alreadyActive > 0) parts.push(`${body.alreadyActive} already queued or running.`)
      if (body.manual > 0) parts.push(`${body.manual} by CSV upload only.`)
      setNotice({ tone: "success", text: parts.join(" ") })
      if (body.firstJob) onSubmitted(body.firstJob as IngestionJob)
    } catch (error) {
      setNotice({ tone: "error", text: error instanceof Error ? error.message : String(error) })
    } finally {
      setPending(null)
    }
  }

  async function fetchSeries(series: MacroSeriesStatus, full: boolean) {
    if (pending) return
    setPending(series.code)
    setNotice(null)
    try {
      const { status: http, body } = await requestJson<IngestionJob>("/api/macro/ingestions", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ code: series.code, full }),
      })
      setNotice({
        tone: "success",
        text:
          http === 202
            ? `Queued ${full ? "the full history" : "the latest observations"} of ${series.name} from ${series.sourceLabel}.`
            : `${series.name} is already queued or running. Follow it in the table below.`,
      })
      onSubmitted(body)
    } catch (error) {
      setNotice({ tone: "error", text: error instanceof Error ? error.message : String(error) })
    } finally {
      setPending(null)
    }
  }

  async function openHistory(code: string) {
    setHistory({ code })
    try {
      const { body } = await requestJson<MacroHistory>(`/api/macro/series/${encodeURIComponent(code)}`)
      setHistory((h) => (h?.code === code ? { code, history: body } : h))
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      setHistory((h) => (h?.code === code ? { code, error: message } : h))
    }
  }

  async function upload(series: MacroSeriesStatus, file: File) {
    setPending(series.code)
    setNotice(null)
    try {
      const form = new FormData()
      form.append("file", file, file.name)
      const { body } = await requestJson<MacroResult>(`/api/macro/series/${encodeURIComponent(series.code)}/upload`, {
        method: "POST",
        body: form,
      })
      setNotice({
        tone: "success",
        text: `${series.name}: ${body.inserted} new and ${body.revised} revised observations stored from ${file.name}${
          body.latestDate ? `, latest ${formatDate(body.latestDate)}` : ""
        }.${body.warnings.length > 0 ? ` Check ${body.warnings.length} large move(s): ${body.warnings[0]}.` : ""}`,
      })
      router.refresh()
    } catch (error) {
      setNotice({ tone: "error", text: `${series.name}: ${error instanceof Error ? error.message : String(error)}` })
    } finally {
      setPending(null)
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <CalendarCheckIcon className="size-4 text-primary" />
            Update all macro data to today
          </CardTitle>
          <CardDescription>
            Asks each source only for the series whose next release is due (a daily series every weekday, a monthly one
            once its new month is published). The series that only take a CSV are left as they are.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          <dl className="grid grid-cols-2 gap-2 text-xs sm:grid-cols-3 lg:grid-cols-6">
            <Stat label="Series" value={status.series} />
            <Stat label="Up to date" value={status.upToDate} tone="good" />
            <Stat label="Behind" value={status.behind} tone={status.behind > 0 ? "warn" : undefined} />
            <Stat label="Not fetched" value={status.neverFetched} tone={status.neverFetched > 0 ? "warn" : undefined} />
            <Stat label="Failing" value={status.failing} tone={status.failing > 0 ? "bad" : undefined} />
            <Stat label="CSV upload" value={status.manual} />
          </dl>
          <ul className="list-disc space-y-1 pl-4 text-xs text-muted-foreground">
            <li>
              Each observation keeps the day it became public (the end of its period plus the publication lag): the
              snapshot of a day never uses a figure published later.
            </li>
            <li>
              A value outside the series&apos; range, a date given twice or a date in the future fails the job and
              nothing is stored; an unusually large move is stored and listed as a warning.
            </li>
            <li>
              FRED, Bank Indonesia, CNBC and the CFTC are asked one request at a time with a pause in between; a site that
              refuses an automated request is not worked around (upload a CSV of <code>obs_date,value</code> instead).
            </li>
          </ul>
          {notice && <NoticeLine notice={notice} />}
          <Button onClick={updateAll} disabled={!!pending || toFetch === 0} className="self-start">
            {pending === "all" ? <LoaderIcon className="animate-spin" /> : <RefreshCwIcon />}
            {pending === "all" ? "Queuing..." : toFetch === 0 ? "Everything is up to date" : `Update ${toFetch} series`}
          </Button>
        </CardContent>
      </Card>

      {GROUPS.map((group) => {
        const items = status.items.filter((s) => group.countries.includes(s.country))
        if (items.length === 0) return null
        return (
          <Card key={group.title} className="min-w-0">
            <CardHeader>
              <CardTitle>{group.title}</CardTitle>
            </CardHeader>
            <CardContent className="px-0">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead className="pl-4">Series</TableHead>
                    <TableHead>Source</TableHead>
                    <TableHead>Latest</TableHead>
                    <TableHead>State</TableHead>
                    <TableHead className="pr-4 text-right">Actions</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {items.map((s) => (
                    <SeriesRow
                      key={s.code}
                      series={s}
                      busy={pending === s.code}
                      disabled={!!pending}
                      onFetch={(full) => fetchSeries(s, full)}
                      onUpload={(file) => upload(s, file)}
                      onHistory={() => openHistory(s.code)}
                    />
                  ))}
                </TableBody>
              </Table>
            </CardContent>
          </Card>
        )
      })}

      <HistorySheet state={history} onClose={() => setHistory(null)} />
    </div>
  )
}

function SeriesRow({
  series: s,
  busy,
  disabled,
  onFetch,
  onUpload,
  onHistory,
}: {
  series: MacroSeriesStatus
  busy: boolean
  disabled: boolean
  onFetch: (full: boolean) => void
  onUpload: (file: File) => void
  onHistory: () => void
}) {
  const fileRef = useRef<HTMLInputElement>(null)
  const meta = STATE_META[s.state]
  return (
    <TableRow className="align-top">
      <TableCell className="max-w-[300px] pl-4 whitespace-normal">
        <div className="font-medium">{s.name}</div>
        <div className="text-xs text-muted-foreground">
          <span className="font-mono">{s.code}</span> · {s.frequency.toLowerCase()} · {s.unit}
          {s.priority === 1 ? " · core" : ""}
        </div>
        <div className="mt-1 flex flex-wrap gap-1">
          {s.usedBy.map((u) => (
            <Badge key={u} variant="outline" className="text-[10px]">
              {PRESET_LABEL[u] ?? u}
            </Badge>
          ))}
        </div>
      </TableCell>
      <TableCell className="text-xs whitespace-normal">
        {s.sourceUrl ? (
          <a href={s.sourceUrl} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-1 underline-offset-4 hover:underline">
            {s.sourceLabel} <ExternalLinkIcon className="size-3" />
          </a>
        ) : (
          s.sourceLabel
        )}
        <div className="text-muted-foreground">public {s.releaseLagDays === 0 ? "the same day" : `${s.releaseLagDays} day(s) after the period`}</div>
      </TableCell>
      <TableCell className="text-xs whitespace-normal">
        <div className="font-mono tabular-nums">{valueWithUnit(s.lastValue, s.unit)}</div>
        <div className="text-muted-foreground">
          {s.lastDate ? `${formatDate(s.lastDate)} · ${s.rows.toLocaleString("en-US")} obs.` : "nothing stored"}
        </div>
      </TableCell>
      <TableCell className="max-w-[220px] text-xs whitespace-normal">
        <Badge className={cn("border-transparent", meta.className)}>{meta.label}</Badge>
        {s.state === "BEHIND" && <div className="mt-1 text-muted-foreground">due {formatDate(s.expectedLatest)}</div>}
        {s.state === "FAILING" && s.lastJobMessage && (
          <div className="mt-1 line-clamp-3 text-destructive">{s.lastJobMessage}</div>
        )}
      </TableCell>
      <TableCell className="pr-4">
        <div className="flex flex-wrap justify-end gap-1.5">
          {s.source !== "manual" && (
            <>
              <Button size="sm" variant="outline" disabled={disabled} onClick={() => onFetch(false)} title="Fetch the latest observations">
                {busy ? <LoaderIcon className="animate-spin" /> : <RefreshCwIcon />} Fetch
              </Button>
              <Button size="sm" variant="ghost" disabled={disabled} onClick={() => onFetch(true)} title="Fetch the whole history again">
                <HistoryIcon /> Full
              </Button>
            </>
          )}
          <Button
            size="sm"
            variant={s.source === "manual" ? "outline" : "ghost"}
            disabled={disabled}
            onClick={() => fileRef.current?.click()}
            title="Upload a CSV of obs_date,value"
          >
            <UploadIcon /> CSV
          </Button>
          <input
            ref={fileRef}
            type="file"
            accept=".csv,text/csv,text/plain"
            className="hidden"
            onChange={(e) => {
              const file = e.target.files?.[0]
              e.target.value = ""
              if (file) onUpload(file)
            }}
          />
          <Button size="sm" variant="ghost" disabled={s.rows === 0} onClick={onHistory} title="Show the history">
            <ChartLineIcon /> Chart
          </Button>
        </div>
      </TableCell>
    </TableRow>
  )
}

/** The series whose history is open: loading, loaded or failed. */
type HistoryState = { code: string; history?: MacroHistory; error?: string }

/** The history of one series: a line over every stored observation and the latest ones with their release dates. */
function HistorySheet({ state, onClose }: { state: HistoryState | null; onClose: () => void }) {
  const code = state?.code ?? null
  const history = state?.history ?? null
  const points = history ? history.points.map(([date, value]) => ({ date, value })) : []
  const latest = history ? history.points.slice(-8).reverse() : []

  return (
    <Sheet open={!!code} onOpenChange={(open) => !open && onClose()}>
      <SheetContent side="right" className="w-full overflow-y-auto sm:max-w-xl">
        <SheetHeader>
          <SheetTitle>{history?.series.name ?? code}</SheetTitle>
          <SheetDescription>
            {history
              ? `${history.series.unit} · ${history.series.frequency.toLowerCase()} · ${history.series.sourceLabel} · ${history.points.length.toLocaleString("en-US")} observations`
              : "Loading…"}
          </SheetDescription>
        </SheetHeader>
        <div className="flex flex-col gap-4 px-4 pb-6">
          {state?.error ? (
            <p role="alert" className="text-sm text-destructive">{state.error}</p>
          ) : !history ? (
            <Skeleton className="h-[240px] w-full" />
          ) : (
            <>
              {history.series.description && <p className="text-sm text-muted-foreground">{history.series.description}</p>}
              <ChartContainer config={historyConfig} className="aspect-auto h-[240px] w-full">
                <LineChart data={points} margin={{ left: 4, right: 4 }}>
                  <XAxis dataKey="date" tickLine={false} axisLine={false} minTickGap={48} tick={{ fontSize: 10 }} tickFormatter={(v: string) => v.slice(0, 7)} />
                  <YAxis tickLine={false} axisLine={false} width={56} domain={["auto", "auto"]} tick={{ fontSize: 10 }} tickFormatter={(v: number) => formatNumber(v, 2)} />
                  <ChartTooltip content={<ChartTooltipContent labelFormatter={(v) => formatDate(String(v))} />} />
                  <Line dataKey="value" stroke="var(--color-value)" dot={false} strokeWidth={1.5} isAnimationActive={false} />
                </LineChart>
              </ChartContainer>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Date</TableHead>
                    <TableHead className="text-right">Value</TableHead>
                    <TableHead>Public on</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {latest.map(([date, value, released]) => (
                    <TableRow key={date}>
                      <TableCell>{formatDate(date)}</TableCell>
                      <TableCell className="text-right font-mono tabular-nums">{valueWithUnit(value, history.series.unit)}</TableCell>
                      <TableCell>{formatDate(released)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </>
          )}
        </div>
      </SheetContent>
    </Sheet>
  )
}

function NoticeLine({ notice }: { notice: Notice }) {
  return (
    <p
      role={notice.tone === "error" ? "alert" : "status"}
      className={cn(
        "flex items-start gap-2 text-xs",
        notice.tone === "error" ? "text-destructive" : "text-emerald-600 dark:text-emerald-400"
      )}
    >
      {notice.tone === "error" ? (
        <CircleAlertIcon className="mt-px size-3.5 shrink-0" />
      ) : (
        <CircleCheckIcon className="mt-px size-3.5 shrink-0" />
      )}
      <span>{notice.text}</span>
    </p>
  )
}

function Stat({ label, value, tone }: { label: string; value: number; tone?: "good" | "warn" | "bad" }) {
  return (
    <div className="rounded-lg border bg-muted/30 px-2.5 py-1.5">
      <dt className="text-[11px] text-muted-foreground">{label}</dt>
      <dd
        className={cn(
          "font-mono text-sm font-semibold tabular-nums",
          tone === "good" && "text-emerald-700 dark:text-emerald-400",
          tone === "warn" && "text-amber-700 dark:text-amber-400",
          tone === "bad" && "text-destructive"
        )}
      >
        {value.toLocaleString("en-US")}
      </dd>
    </div>
  )
}
