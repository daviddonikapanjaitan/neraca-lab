"use client"

import { ChevronRightIcon, TriangleAlertIcon } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { formatNumber } from "@/lib/format"
import { CONVICTION_CLASS, formatScore, scoreClass } from "@/lib/screening"
import {
  formatIdr,
  STANCE_LABEL,
  STANCE_STYLE,
  TECHNICAL_AGENT_SHORT,
  type TechnicalAgentCode,
  type TechnicalCandidate,
} from "@/lib/technical-screening"
import { cn } from "@/lib/utils"

/**
 * Candidates with their action, trade plan (buy, sell, stop loss, reward / risk), overall and per-agent final scores;
 * a row opens the candidate's details.
 */
export function TechnicalRankingTable({
  candidates,
  agents,
  onSelect,
}: {
  candidates: TechnicalCandidate[]
  agents: TechnicalAgentCode[]
  onSelect: (candidate: TechnicalCandidate) => void
}) {
  return (
    <div className="overflow-x-auto">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className="w-10 pl-4 text-right">#</TableHead>
            <TableHead>Stock</TableHead>
            <TableHead className="text-right">Close</TableHead>
            <TableHead>Action</TableHead>
            <TableHead className="text-right">Buy</TableHead>
            <TableHead className="text-right">Sell</TableHead>
            <TableHead className="text-right">Stop loss</TableHead>
            <TableHead className="text-right">R / R</TableHead>
            <TableHead className="text-right">Overall</TableHead>
            {agents.map((a) => (
              <TableHead key={a} className="text-right">
                {TECHNICAL_AGENT_SHORT[a]}
              </TableHead>
            ))}
            <TableHead>Conviction</TableHead>
            <TableHead className="pr-4">
              <span className="sr-only">Details</span>
            </TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {candidates.map((c) => (
            <TableRow key={c.id} className="cursor-pointer" onClick={() => onSelect(c)}>
              <TableCell className="pl-4 text-right tabular-nums text-muted-foreground">
                {c.finalRank ?? c.priorRank ?? "—"}
              </TableCell>
              <TableCell className="max-w-[220px]">
                <div className="flex items-center gap-1.5">
                  <span className="font-mono font-semibold">{c.ticker}</span>
                  {c.redFlags && c.redFlags.length > 0 && (
                    <TriangleAlertIcon className="size-3.5 text-amber-500" aria-label="Red flags" />
                  )}
                </div>
                <div className="truncate text-xs text-muted-foreground">{c.companyName}</div>
              </TableCell>
              <TableCell className="text-right font-mono tabular-nums">{formatIdr(c.close)}</TableCell>
              <TableCell>
                {c.action ? (
                  <Badge variant="outline" className={cn("font-semibold", STANCE_STYLE[c.action])}>
                    {STANCE_LABEL[c.action]}
                  </Badge>
                ) : (
                  <span className="text-xs text-muted-foreground">—</span>
                )}
              </TableCell>
              <TableCell className="text-right font-mono tabular-nums text-emerald-700 dark:text-emerald-400">
                {formatIdr(c.plan?.buy?.price)}
                {c.plan?.buy?.type === "STOP" && (
                  <span className="ml-0.5 text-[10px] text-muted-foreground" title="Buy-stop: at or above (a breakout)">
                    ↑
                  </span>
                )}
              </TableCell>
              <TableCell className="text-right font-mono tabular-nums">{formatIdr(c.plan?.sell?.price)}</TableCell>
              <TableCell className="text-right font-mono tabular-nums text-rose-700 dark:text-rose-400">
                {formatIdr(c.plan?.stopLoss?.price)}
              </TableCell>
              <TableCell className="text-right tabular-nums">
                {c.plan?.rewardRisk != null ? formatNumber(c.plan.rewardRisk, 2) : "—"}
              </TableCell>
              <TableCell className={cn("text-right font-semibold tabular-nums", scoreClass(c.overallScore ?? c.priorOverall))}>
                {formatScore(c.overallScore ?? c.priorOverall)}
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
                    {score?.stance && (
                      <span className="ml-1 text-[10px] text-muted-foreground" title={STANCE_LABEL[score.stance]}>
                        {score.stance.charAt(0)}
                      </span>
                    )}
                    {score?.status === "PRIOR_ONLY" && (
                      <span className="ml-0.5 text-[10px] text-muted-foreground" title="Rules summary only">
                        r
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
              <TableCell className="pr-4">
                <ChevronRightIcon className="ml-auto size-4 text-muted-foreground" />
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}
