"use client"

import { useEffect, useState } from "react"
import Link from "next/link"
import { HistoryIcon } from "lucide-react"

import { EmptyState } from "@/components/empty-state"
import { JobStatusBadge } from "@/components/ingestion/job-status-badge"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { requestJson } from "@/lib/client-api"
import { EMPTY, formatNumber, formatTimestamp } from "@/lib/format"
import { formatDuration, isActive } from "@/lib/ingestion"
import {
  ANALYSIS_RUN_LIMIT,
  analysisReportHref,
  analysisRunTitle,
  type AnalysisRun,
} from "@/lib/portfolio-analysis"
import { formatUsd } from "@/lib/screening"

const POLL_MS = 4000

/** The user's portfolio analyses, most recent first; refreshed while one is queued or running. */
export function AnalysisRunsTable({ initial }: { initial: AnalysisRun[] }) {
  const [runs, setRuns] = useState(initial)
  const active = runs.some((r) => isActive(r.status))

  useEffect(() => {
    if (!active) return
    const timer = setInterval(async () => {
      try {
        const { body } = await requestJson<AnalysisRun[]>(`/api/portfolio/analyses?limit=${ANALYSIS_RUN_LIMIT}`)
        setRuns(body)
      } catch {
        // the next round tries again
      }
    }, POLL_MS)
    return () => clearInterval(timer)
  }, [active])

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <HistoryIcon className="size-4 text-muted-foreground" />
          Your analyses
        </CardTitle>
        <CardDescription>Each run is saved with its recommendations; open one to read its report.</CardDescription>
      </CardHeader>
      <CardContent className="px-0">
        {runs.length === 0 ? (
          <div className="px-4">
            <EmptyState variant="generic" title="No analysis yet" description="Start one above." />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="pl-4">Requested</TableHead>
                  <TableHead>Stocks to choose from</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead className="text-right">Capital</TableHead>
                  <TableHead className="text-right">Stocks after</TableHead>
                  <TableHead className="text-right">Time</TableHead>
                  <TableHead className="pr-4 text-right">Cost</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {runs.map((r) => {
                  const ms = r.startedAt && r.finishedAt ? new Date(r.finishedAt).getTime() - new Date(r.startedAt).getTime() : null
                  return (
                    <TableRow key={r.id}>
                      <TableCell className="pl-4">
                        <Link href={analysisReportHref(r.id)} className="font-medium hover:underline">
                          {formatTimestamp(r.requestedAt)}
                        </Link>
                      </TableCell>
                      <TableCell>
                        <div>{analysisRunTitle(r)}</div>
                        {r.mode === "SELECTED" && r.tickers && (
                          <div className="max-w-80 truncate text-xs text-muted-foreground" title={r.tickers.join(", ")}>
                            {r.tickers.join(", ")}
                          </div>
                        )}
                      </TableCell>
                      <TableCell>
                        <JobStatusBadge status={r.status} />
                        {r.stage && isActive(r.status) && (
                          <div className="mt-0.5 max-w-72 truncate text-xs text-muted-foreground">{r.stage}</div>
                        )}
                      </TableCell>
                      <TableCell className="text-right font-mono tabular-nums">
                        {r.capital === null ? EMPTY : `Rp ${formatNumber(r.capital, 0)}`}
                      </TableCell>
                      <TableCell className="text-right tabular-nums">{r.recommended ?? EMPTY}</TableCell>
                      <TableCell className="text-right tabular-nums">{formatDuration(ms)}</TableCell>
                      <TableCell className="pr-4 text-right tabular-nums">{formatUsd(r.costUsd)}</TableCell>
                    </TableRow>
                  )
                })}
              </TableBody>
            </Table>
          </div>
        )}
      </CardContent>
    </Card>
  )
}
