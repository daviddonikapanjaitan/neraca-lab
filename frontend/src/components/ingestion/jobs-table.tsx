"use client"

import { useEffect, useRef, useState } from "react"
import {
  ChevronRightIcon,
  CircleAlertIcon,
  CircleCheckIcon,
  CircleXIcon,
  FileSpreadsheetIcon,
  HourglassIcon,
  LineChartIcon,
  LoaderIcon,
  TriangleAlertIcon,
} from "lucide-react"

import { EmptyState } from "@/components/empty-state"
import { DownloadFileButton } from "@/components/ingestion/download-file-button"
import { JobDetailSheet } from "@/components/ingestion/job-detail-sheet"
import { JobStatusBadge } from "@/components/ingestion/job-status-badge"
import { StatTile } from "@/components/stat-tile"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { EMPTY, formatTimestamp } from "@/lib/format"
import { requestJson } from "@/lib/client-api"
import { creatorLabel, formatDuration, isActive, JOB_LIMIT, TYPE_LABEL } from "@/lib/ingestion"
import type { IngestionJob, IngestionJobList, IngestionJobType } from "@/lib/types"
import { cn } from "@/lib/utils"

/** Refresh interval while a job is active, and otherwise. */
const ACTIVE_POLL_MS = 2000
const IDLE_POLL_MS = 10000

type TypeFilter = "ALL" | IngestionJobType
type StatusFilter = "all" | "active" | "done" | "failed"

const TYPE_TABS: { value: TypeFilter; label: string }[] = [
  { value: "ALL", label: "All" },
  { value: "FINANCIAL_STATEMENT", label: "Financial statements" },
  { value: "PRICE", label: "Prices" },
]

const STATUS_FILTERS: { value: StatusFilter; label: string; statuses?: string }[] = [
  { value: "all", label: "All statuses" },
  { value: "active", label: "In progress", statuses: "QUEUED,RUNNING,WAITING_RATE_LIMIT" },
  { value: "done", label: "Done or incomplete", statuses: "SUCCEEDED,INCOMPLETE" },
  { value: "failed", label: "Failed", statuses: "FAILED" },
]

function query(type: TypeFilter, status: StatusFilter): string {
  const params = new URLSearchParams({ limit: String(JOB_LIMIT) })
  if (type !== "ALL") params.set("type", type)
  const statuses = STATUS_FILTERS.find((s) => s.value === status)?.statuses
  if (statuses) params.set("status", statuses)
  return params.toString()
}

function duration(job: IngestionJob, now: number | null): string {
  if (!job.startedAt) return EMPTY
  const start = new Date(job.startedAt).getTime()
  if (job.finishedAt) return formatDuration(new Date(job.finishedAt).getTime() - start)
  // running: only on the client (the current time differs between server and browser)
  return now === null ? EMPTY : formatDuration(Math.max(0, now - start))
}

/**
 * Every ingestion job (most recent first), refreshed every 2 seconds while a job is in progress
 * and every 10 seconds otherwise. {@code watch} is the job just submitted on this page; when it, or
 * any job seen in progress here, finishes, {@code onJobFinished} lets the page reload its data
 * (new company, latest price date).
 */
export function JobsTable({
  initial,
  watch,
  onJobFinished,
}: {
  initial: IngestionJobList
  watch: { id: string; seq: number } | null
  onJobFinished: () => void
}) {
  const [list, setList] = useState(initial)
  const [type, setType] = useState<TypeFilter>("ALL")
  const [status, setStatus] = useState<StatusFilter>("all")
  const [error, setError] = useState<string | null>(null)
  /** filter query of the jobs shown; differs from the selected filters while they load */
  const [loadedQuery, setLoadedQuery] = useState(() => query("ALL", "all"))
  const [selected, setSelected] = useState<string | null>(null)
  const [now, setNow] = useState<number | null>(null)
  /** jobs seen in progress (or submitted) on this page, to notice when they finish */
  const watched = useRef(new Set(initial.jobs.filter((j) => isActive(j.status)).map((j) => j.id)))
  const finishedCallback = useRef(onJobFinished)

  useEffect(() => {
    finishedCallback.current = onJobFinished
  }, [onJobFinished])

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [])

  useEffect(() => {
    if (watch) watched.current.add(watch.id)
    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | undefined

    const current = query(type, status)

    async function load() {
      try {
        const { body } = await requestJson<IngestionJobList>(`/api/ingestions?${current}`)
        if (cancelled) return
        setList(body)
        setLoadedQuery(current)
        setError(null)
        let finished = false
        for (const job of body.jobs) {
          if (isActive(job.status)) {
            watched.current.add(job.id)
          } else if (watched.current.delete(job.id)) {
            finished = true
          }
        }
        if (finished) finishedCallback.current()
        timer = setTimeout(load, body.active > 0 ? ACTIVE_POLL_MS : IDLE_POLL_MS)
      } catch (e) {
        if (cancelled) return
        setError(e instanceof Error ? e.message : String(e))
        timer = setTimeout(load, IDLE_POLL_MS)
      }
    }

    load()
    return () => {
      cancelled = true
      if (timer) clearTimeout(timer)
    }
  }, [type, status, watch])

  const statusItems = STATUS_FILTERS.map((s) => ({ value: s.value, label: s.label }))
  const filtered = type !== "ALL" || status !== "all"
  const loading = loadedQuery !== query(type, status)

  return (
    <div className="flex flex-col gap-4">
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <StatTile
          label="In progress"
          value={list.active}
          icon={list.active > 0 ? LoaderIcon : HourglassIcon}
          hint={`${list.counts.QUEUED} queued · ${list.counts.RUNNING} running`}
        />
        <StatTile label="Done" value={list.counts.SUCCEEDED} icon={CircleCheckIcon} tone="positive" />
        <StatTile
          label="Incomplete"
          value={list.counts.INCOMPLETE}
          icon={TriangleAlertIcon}
          tone="muted"
          hint="Stored, with open items"
        />
        <StatTile label="Failed" value={list.counts.FAILED} icon={CircleXIcon} tone="negative" />
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            Ingestion jobs
            {loading && <LoaderIcon className="size-3.5 animate-spin text-muted-foreground" />}
          </CardTitle>
          <CardDescription>
            Every upload and price ingestion with its progress, most recent first (up to {JOB_LIMIT}). Updates
            automatically. Select a job for details.
          </CardDescription>
          <CardAction className="hidden sm:block">
            <Badge variant="outline" className="tabular-nums">
              {list.active > 0 ? `${list.active} in progress` : "Idle"}
            </Badge>
          </CardAction>
        </CardHeader>
        <CardContent className={cn("flex flex-col gap-3 px-0 transition-opacity", loading && "opacity-60")}>
          <div className="flex flex-wrap items-center gap-2 px-4">
            <Tabs value={type} onValueChange={(v) => setType(v as TypeFilter)}>
              <TabsList>
                {TYPE_TABS.map((t) => (
                  <TabsTrigger key={t.value} value={t.value} className="px-2.5">
                    {t.label}
                  </TabsTrigger>
                ))}
              </TabsList>
            </Tabs>
            <Select items={statusItems} value={status} onValueChange={(v) => v && setStatus(v as StatusFilter)}>
              <SelectTrigger aria-label="Status" className="min-w-[170px]">
                <SelectValue placeholder="Status" />
              </SelectTrigger>
              <SelectContent>
                {statusItems.map((s) => (
                  <SelectItem key={s.value} value={s.value}>
                    {s.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          {error && (
            <p role="alert" className="flex items-start gap-2 px-4 text-xs text-destructive">
              <CircleAlertIcon className="mt-px size-3.5 shrink-0" />
              <span>Cannot refresh the jobs: {error}</span>
            </p>
          )}

          {list.jobs.length === 0 ? (
            filtered ? (
              <EmptyState
                variant="filter"
                actionLabel="Show all jobs"
                onAction={() => {
                  setType("ALL")
                  setStatus("all")
                }}
              />
            ) : (
              <EmptyState
                variant="generic"
                title="No ingestion jobs yet"
                description="Upload a financial statement or fetch prices above. The jobs will show here."
              />
            )
          ) : (
            <div className="overflow-x-auto">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead className="pl-4">Job</TableHead>
                    <TableHead>Status</TableHead>
                    <TableHead className="hidden md:table-cell">Progress</TableHead>
                    <TableHead className="hidden lg:table-cell">Requested</TableHead>
                    <TableHead className="hidden sm:table-cell text-right">Duration</TableHead>
                    <TableHead className="pr-4 text-right">
                      <span className="sr-only">Details</span>
                    </TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {list.jobs.map((job) => {
                    const Icon = job.type === "PRICE" ? LineChartIcon : FileSpreadsheetIcon
                    return (
                      <TableRow key={job.id} className="cursor-pointer" onClick={() => setSelected(job.id)}>
                        <TableCell className="pl-4">
                          <div className="flex items-center gap-2.5">
                            <div className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
                              <Icon className="size-4" />
                            </div>
                            <div className="min-w-0 max-w-[260px]">
                              <div className="flex items-center gap-1.5">
                                <span className="font-medium">
                                  {job.ticker ? (
                                    <span className="font-mono">{job.ticker}</span>
                                  ) : (
                                    TYPE_LABEL[job.type]
                                  )}
                                </span>
                                {job.ticker && (
                                  <span className="text-xs text-muted-foreground">{TYPE_LABEL[job.type]}</span>
                                )}
                                {job.fullHistory && <Badge variant="secondary">Full history</Badge>}
                              </div>
                              <div className="truncate text-xs text-muted-foreground">
                                {job.file ? (
                                  <>
                                    {job.file.fileName}
                                    {job.file.reused && " · stored file reused"}
                                  </>
                                ) : (
                                  job.exchange ?? EMPTY
                                )}
                              </div>
                            </div>
                          </div>
                        </TableCell>
                        <TableCell>
                          <JobStatusBadge status={job.status} />
                          {/* progress text below the badge on small screens */}
                          <div className="mt-1 max-w-[200px] truncate text-xs text-muted-foreground md:hidden">
                            {job.stage}
                          </div>
                        </TableCell>
                        <TableCell className="hidden md:table-cell">
                          <div className="max-w-[380px]">
                            <div className="truncate">{job.stage ?? EMPTY}</div>
                            {job.message && (
                              <div
                                className={cn(
                                  "truncate text-xs",
                                  job.status === "FAILED" ? "text-destructive" : "text-muted-foreground"
                                )}
                                title={job.message}
                              >
                                {job.message}
                              </div>
                            )}
                          </div>
                        </TableCell>
                        <TableCell className="hidden text-xs lg:table-cell">
                          <div className="tabular-nums text-muted-foreground">{formatTimestamp(job.requestedAt)}</div>
                          <div className="max-w-[180px] truncate" title={creatorLabel(job.createdBy)}>
                            {job.createdBy ? (
                              <>
                                by <span className="font-medium">{job.createdBy.username}</span>
                              </>
                            ) : (
                              <span className="text-muted-foreground">Scheduled run</span>
                            )}
                          </div>
                        </TableCell>
                        <TableCell className="hidden text-right tabular-nums sm:table-cell">
                          {duration(job, now)}
                        </TableCell>
                        <TableCell className="pr-4 text-right whitespace-nowrap">
                          {job.file && (
                            <DownloadFileButton jobId={job.id} fileName={job.file.fileName} compact />
                          )}
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={(e) => {
                              e.stopPropagation()
                              setSelected(job.id)
                            }}
                          >
                            Details <ChevronRightIcon />
                          </Button>
                        </TableCell>
                      </TableRow>
                    )
                  })}
                </TableBody>
              </Table>
            </div>
          )}
        </CardContent>
      </Card>

      <JobDetailSheet jobId={selected} onOpenChange={(open) => !open && setSelected(null)} />
    </div>
  )
}
