"use client"

import { useEffect, useState } from "react"
import Link from "next/link"
import { ChevronRightIcon, HistoryIcon } from "lucide-react"

import { EmptyState } from "@/components/empty-state"
import { JobStatusBadge } from "@/components/ingestion/job-status-badge"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { formatTimestamp } from "@/lib/format"
import { requestJson } from "@/lib/client-api"
import { creatorLabel, isActive } from "@/lib/ingestion"
import { AGENT_SHORT, formatTokens, formatUsd, reportHref, RUN_LIMIT, scopeLabel } from "@/lib/screening"
import type { ScreeningRun, ScreeningScope } from "@/lib/types"

const ACTIVE_POLL_MS = 3000
const IDLE_POLL_MS = 15000

/**
 * The screening runs of one page (most recent first): those of a market-cap tier (Screening Stocks) or of selected
 * stocks (Selected Stocks). Refreshed every 3 s while one is active. Saved reports open on click.
 */
export function RunsTable({ initial, scope }: { initial: ScreeningRun[]; scope: ScreeningScope }) {
  const [runs, setRuns] = useState(initial)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | undefined
    async function load() {
      try {
        const { body } = await requestJson<ScreeningRun[]>(`/api/screenings?limit=${RUN_LIMIT}&scope=${scope}`)
        if (cancelled) return
        setRuns(body)
        setError(null)
        timer = setTimeout(load, body.some((r) => isActive(r.status)) ? ACTIVE_POLL_MS : IDLE_POLL_MS)
      } catch (e) {
        if (cancelled) return
        setError(e instanceof Error ? e.message : String(e))
        timer = setTimeout(load, IDLE_POLL_MS)
      }
    }
    timer = setTimeout(load, initial.some((r) => isActive(r.status)) ? ACTIVE_POLL_MS : IDLE_POLL_MS)
    return () => {
      cancelled = true
      if (timer) clearTimeout(timer)
    }
  }, [initial, scope])

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <HistoryIcon className="size-4 text-primary" />
          Saved screenings
        </CardTitle>
        <CardDescription>
          Every report is stored in the database. Open one to read it again or download it as PDF.
        </CardDescription>
      </CardHeader>
      <CardContent className="px-0">
        {error && (
          <p role="alert" className="px-4 pb-2 text-xs text-destructive">
            {error}
          </p>
        )}
        {runs.length === 0 ? (
          <EmptyState
            variant="search"
            title="No screenings yet"
            description="Start a screening above; its report appears here."
          />
        ) : (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="pl-4">Screening</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead className="text-right">Selected</TableHead>
                  <TableHead className="text-right">Cost</TableHead>
                  <TableHead className="text-right">Tokens</TableHead>
                  <TableHead>Requested</TableHead>
                  <TableHead className="pr-4 text-right">
                    <span className="sr-only">Open</span>
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {runs.map((run) => (
                  <TableRow key={run.id} className="relative">
                    <TableCell className="pl-4">
                      <Link href={reportHref(run)} className="font-medium after:absolute after:inset-0">
                        {run.exchange} · {scopeLabel(run)} · top {run.topN}
                      </Link>
                      {run.tickers && (
                        <div className="max-w-[320px] truncate text-xs" title={run.tickers.join(", ")}>
                          {run.tickers.join(", ")}
                        </div>
                      )}
                      <div className="text-xs text-muted-foreground">
                        {run.agents.map((a) => AGENT_SHORT[a]).join(", ")}
                      </div>
                    </TableCell>
                    <TableCell className="max-w-[280px]">
                      <div className="flex flex-col gap-1">
                        <JobStatusBadge status={run.status} />
                        <span className="truncate text-xs text-muted-foreground" title={run.stage ?? undefined}>
                          {run.stage}
                        </span>
                      </div>
                    </TableCell>
                    <TableCell className="text-right tabular-nums">
                      {run.selectedCount ?? "—"}
                      {run.shortlistCount != null && (
                        <span className="text-xs text-muted-foreground"> / {run.shortlistCount}</span>
                      )}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">{formatUsd(run.costUsd)}</TableCell>
                    <TableCell className="text-right text-xs tabular-nums text-muted-foreground">
                      {formatTokens(run.promptTokens + run.completionTokens)}
                    </TableCell>
                    <TableCell className="text-xs">
                      {formatTimestamp(run.requestedAt)}
                      <div className="text-muted-foreground">by {creatorLabel(run.createdBy)}</div>
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
      </CardContent>
    </Card>
  )
}
