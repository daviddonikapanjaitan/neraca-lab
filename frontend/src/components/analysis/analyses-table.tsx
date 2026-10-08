"use client"

import { useEffect, useRef, useState } from "react"
import Link from "next/link"
import { ChevronRightIcon, CircleAlertIcon, HistoryIcon, LoaderIcon } from "lucide-react"

import { EmptyState } from "@/components/empty-state"
import { JobStatusBadge } from "@/components/ingestion/job-status-badge"
import { pageCount, TablePagination } from "@/components/table-pagination"
import { Badge } from "@/components/ui/badge"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { EMPTY, formatTimestamp } from "@/lib/format"
import { requestJson } from "@/lib/client-api"
import { creatorLabel, DEFAULT_TABLE_PAGE_SIZE, isActive, TABLE_PAGE_SIZES } from "@/lib/ingestion"
import { CONVICTION_CLASS, formatScore, formatTokens, formatUsd, scoreClass, verdictLabel } from "@/lib/screening"
import type { AnalysisPage } from "@/lib/types"
import { cn } from "@/lib/utils"

const ACTIVE_POLL_MS = 3000
const IDLE_POLL_MS = 15000

function query(page: number, pageSize: number): string {
  return new URLSearchParams({ limit: String(pageSize), offset: String((page - 1) * pageSize) }).toString()
}

/**
 * The saved analyses (most recent first), page by page (10 rows, or 5 / 20 / 50); refreshed every 3 s while one
 * is in progress, every 15 s otherwise. A row opens its report.
 */
export function AnalysesTable({ initial }: { initial: AnalysisPage }) {
  const [list, setList] = useState(initial)
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(DEFAULT_TABLE_PAGE_SIZE)
  const [error, setError] = useState<string | null>(null)
  /** query of the rows shown; differs from the chosen page while it loads */
  const [loadedQuery, setLoadedQuery] = useState(() => query(1, DEFAULT_TABLE_PAGE_SIZE))
  const first = useRef(true)
  const current = query(page, pageSize)
  const loading = loadedQuery !== current

  useEffect(() => {
    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | undefined

    async function load() {
      try {
        const { body } = await requestJson<AnalysisPage>(`/api/analyses?${current}`)
        if (cancelled) return
        const last = pageCount(body.total, pageSize)
        if (page > last) {
          // the page no longer exists: show the last one
          setPage(last)
          return
        }
        setList(body)
        setLoadedQuery(current)
        setError(null)
        timer = setTimeout(load, body.analyses.some((a) => isActive(a.status)) ? ACTIVE_POLL_MS : IDLE_POLL_MS)
      } catch (e) {
        if (cancelled) return
        setError(e instanceof Error ? e.message : String(e))
        timer = setTimeout(load, IDLE_POLL_MS)
      }
    }

    if (first.current) {
      // the server rendered the first page: poll it later
      first.current = false
      timer = setTimeout(load, initial.analyses.some((a) => isActive(a.status)) ? ACTIVE_POLL_MS : IDLE_POLL_MS)
    } else {
      load()
    }
    return () => {
      cancelled = true
      if (timer) clearTimeout(timer)
    }
  }, [current, page, pageSize, initial])

  function changePageSize(value: number) {
    setPageSize(value)
    setPage(1)
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <HistoryIcon className="size-4 text-primary" />
          Saved analyses
          {loading && !error && <LoaderIcon className="size-3.5 animate-spin text-muted-foreground" />}
        </CardTitle>
        <CardDescription>
          Every report is stored in the database. Open one to read it again or download it as PDF.
        </CardDescription>
      </CardHeader>
      <CardContent className={cn("flex flex-col gap-3 px-0 transition-opacity", loading && !error && "opacity-60")}>
        {error && (
          <p role="alert" className="flex items-start gap-2 px-4 text-xs text-destructive">
            <CircleAlertIcon className="mt-px size-3.5 shrink-0" />
            <span>Cannot refresh the analyses: {error}</span>
          </p>
        )}
        {list.analyses.length === 0 ? (
          <EmptyState
            variant="search"
            title="No analyses yet"
            description="Choose a stock above and start an analysis; its report appears here."
          />
        ) : (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="pl-4">Stock</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead className="text-right">Score</TableHead>
                  <TableHead className="hidden sm:table-cell">Conviction</TableHead>
                  <TableHead className="text-right">Cost</TableHead>
                  <TableHead className="hidden lg:table-cell">Requested</TableHead>
                  <TableHead className="pr-4 text-right">
                    <span className="sr-only">Open</span>
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {list.analyses.map((a) => (
                  <TableRow key={a.id} className="relative">
                    <TableCell className="pl-4">
                      <Link href={`/screening/analysis/${a.id}`} className="font-medium after:absolute after:inset-0">
                        <span className="font-mono">{a.ticker}</span>
                      </Link>
                      <div className="max-w-[220px] truncate text-xs text-muted-foreground">{a.companyName}</div>
                    </TableCell>
                    <TableCell className="max-w-[280px]">
                      <div className="flex flex-col gap-1">
                        <JobStatusBadge status={a.status} />
                        <span className="truncate text-xs text-muted-foreground" title={a.stage ?? undefined}>
                          {a.stage}
                        </span>
                      </div>
                    </TableCell>
                    <TableCell className="text-right tabular-nums">
                      <span className={cn("font-semibold", scoreClass(a.overallScore))}>
                        {formatScore(a.overallScore)}
                      </span>
                      {a.verdict && <div className="text-xs text-muted-foreground">{verdictLabel(a.verdict)}</div>}
                    </TableCell>
                    <TableCell className="hidden sm:table-cell">
                      {a.conviction ? (
                        <Badge variant="outline" className={cn("border-transparent", CONVICTION_CLASS[a.conviction])}>
                          {a.conviction.toLowerCase()}
                        </Badge>
                      ) : (
                        <span className="text-muted-foreground">{EMPTY}</span>
                      )}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">
                      {formatUsd(a.costUsd)}
                      <div className="text-xs text-muted-foreground">
                        {formatTokens(a.promptTokens + a.completionTokens)} tokens
                      </div>
                    </TableCell>
                    <TableCell className="hidden text-xs lg:table-cell">
                      {formatTimestamp(a.requestedAt)}
                      <div className="text-muted-foreground">by {creatorLabel(a.createdBy)}</div>
                    </TableCell>
                    <TableCell className="pr-4 text-right">
                      <ChevronRightIcon className="ml-auto size-4 text-muted-foreground" />
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
        {list.total > 0 && (
          <div className="px-4">
            <TablePagination
              page={page}
              pageSize={pageSize}
              pageSizes={TABLE_PAGE_SIZES}
              total={list.total}
              noun="analyses"
              disabled={loading && !error}
              onPageChange={setPage}
              onPageSizeChange={changePageSize}
            />
          </div>
        )}
      </CardContent>
    </Card>
  )
}
