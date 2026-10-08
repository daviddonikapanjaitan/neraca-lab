"use client"

import { CpuIcon } from "lucide-react"

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { formatTokens, formatUsd, STAGE_LABEL } from "@/lib/screening"
import type { ScreeningUsage } from "@/lib/types"

/** Token usage and cost of a screening or an analysis per stage and model, as reported by OpenRouter. */
export function UsageTable({ usage }: { usage: ScreeningUsage[] }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <CpuIcon className="size-4 text-primary" /> Token usage
        </CardTitle>
        <CardDescription>Every model call of the run, as reported by OpenRouter.</CardDescription>
      </CardHeader>
      <CardContent className="px-0">
        {usage.length === 0 ? (
          <p className="px-4 text-sm text-muted-foreground">No model calls yet.</p>
        ) : (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="pl-4">Stage</TableHead>
                  <TableHead>Model</TableHead>
                  <TableHead className="text-right">Calls</TableHead>
                  <TableHead className="text-right">Prompt</TableHead>
                  <TableHead className="text-right">Completion</TableHead>
                  <TableHead className="text-right">Cached</TableHead>
                  <TableHead className="pr-4 text-right">Cost</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {usage.map((u) => (
                  <TableRow key={u.stage + u.model}>
                    <TableCell className="pl-4">
                      {STAGE_LABEL[u.stage] ?? u.stage}
                      {u.errors > 0 && <span className="ml-1 text-xs text-destructive">({u.errors} failed)</span>}
                    </TableCell>
                    <TableCell className="font-mono text-xs">{u.model}</TableCell>
                    <TableCell className="text-right tabular-nums">{u.calls}</TableCell>
                    <TableCell className="text-right tabular-nums">{formatTokens(u.promptTokens)}</TableCell>
                    <TableCell className="text-right tabular-nums">{formatTokens(u.completionTokens)}</TableCell>
                    <TableCell className="text-right tabular-nums">{formatTokens(u.cachedTokens)}</TableCell>
                    <TableCell className="pr-4 text-right tabular-nums">
                      {formatUsd(u.costUsd)}
                      {u.costEstimated && <span title="estimated from list prices">*</span>}
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
