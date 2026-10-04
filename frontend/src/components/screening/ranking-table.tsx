"use client"

import { ChevronRightIcon, TriangleAlertIcon } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { AGENT_SHORT, CONVICTION_CLASS, formatScore, scoreClass, SENTIMENT_CLASS } from "@/lib/screening"
import type { InvestorAgentCode, ScreeningCandidate } from "@/lib/types"
import { cn } from "@/lib/utils"

/** Candidates with their overall and per-agent final scores; a row opens the candidate's details. */
export function RankingTable({
  candidates,
  agents,
  onSelect,
}: {
  candidates: ScreeningCandidate[]
  agents: InvestorAgentCode[]
  onSelect: (candidate: ScreeningCandidate) => void
}) {
  return (
    <div className="overflow-x-auto">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className="w-10 pl-4 text-right">#</TableHead>
            <TableHead>Stock</TableHead>
            <TableHead className="hidden md:table-cell">Sector</TableHead>
            <TableHead className="text-right">Overall</TableHead>
            {agents.map((a) => (
              <TableHead key={a} className="text-right">
                {AGENT_SHORT[a]}
              </TableHead>
            ))}
            <TableHead>Conviction</TableHead>
            <TableHead className="hidden lg:table-cell">News</TableHead>
            <TableHead className="pr-4">
              <span className="sr-only">Details</span>
            </TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {candidates.map((c) => {
            const sentiment = c.news?.brief?.sentiment
            return (
              <TableRow key={c.id} className="cursor-pointer" onClick={() => onSelect(c)}>
                <TableCell className="pl-4 text-right tabular-nums text-muted-foreground">
                  {c.finalRank ?? "—"}
                </TableCell>
                <TableCell className="max-w-[240px]">
                  <div className="flex items-center gap-1.5">
                    <span className="font-mono font-semibold">{c.ticker}</span>
                    {c.redFlags && c.redFlags.length > 0 && (
                      <TriangleAlertIcon className="size-3.5 text-amber-500" aria-label="Red flags" />
                    )}
                  </div>
                  <div className="truncate text-xs text-muted-foreground">{c.companyName}</div>
                </TableCell>
                <TableCell className="hidden max-w-[160px] truncate text-xs md:table-cell">{c.sector ?? "—"}</TableCell>
                <TableCell className={cn("text-right font-semibold tabular-nums", scoreClass(c.overallScore))}>
                  {formatScore(c.overallScore)}
                  {c.synthesisAdjustment != null && c.synthesisAdjustment !== 0 && (
                    <span className="ml-1 text-[10px] font-normal text-muted-foreground">
                      ({c.synthesisAdjustment > 0 ? "+" : ""}
                      {c.synthesisAdjustment})
                    </span>
                  )}
                </TableCell>
                {agents.map((a) => {
                  const score = c.agents.find((s) => s.agent === a)
                  return (
                    <TableCell key={a} className={cn("text-right tabular-nums", scoreClass(score?.finalScore))}>
                      {formatScore(score?.finalScore)}
                      {score?.status === "QUANT_ONLY" && (
                        <span className="ml-0.5 text-[10px] text-muted-foreground" title="Quantitative score only">
                          q
                        </span>
                      )}
                    </TableCell>
                  )
                })}
                <TableCell>
                  {c.conviction ? (
                    <Badge variant="outline" className={cn("border-transparent", CONVICTION_CLASS[c.conviction])}>
                      {c.conviction.toLowerCase()}
                    </Badge>
                  ) : (
                    <span className="text-xs text-muted-foreground">—</span>
                  )}
                </TableCell>
                <TableCell className="hidden lg:table-cell">
                  {sentiment ? (
                    <Badge variant="outline" className={cn("border-transparent", SENTIMENT_CLASS[sentiment])}>
                      {sentiment.toLowerCase()}
                    </Badge>
                  ) : (
                    <span className="text-xs text-muted-foreground">—</span>
                  )}
                </TableCell>
                <TableCell className="pr-4">
                  <ChevronRightIcon className="ml-auto size-4 text-muted-foreground" />
                </TableCell>
              </TableRow>
            )
          })}
        </TableBody>
      </Table>
    </div>
  )
}
