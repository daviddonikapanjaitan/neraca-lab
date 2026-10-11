import {
  Table,
  TableBody,
  TableCell,
  TableFooter,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { formatTokens, formatUsd, STAGE_LABEL } from "@/lib/screening"
import type { IngestionJobCost, ScreeningUsage } from "@/lib/types"

/**
 * Tokens and cost of the model calls of a job per stage and model, as reported by OpenRouter: the standard
 * extraction (plan, tool turns, review) and the full-text extraction of an upload, each with its own model.
 */
export function JobUsage({ cost, usage }: { cost: IngestionJobCost; usage: ScreeningUsage[] }) {
  return (
    <div className="flex flex-col gap-2">
      <div className="overflow-x-auto rounded-lg border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Stage and model</TableHead>
              <TableHead className="text-right">Calls</TableHead>
              <TableHead className="text-right">Prompt</TableHead>
              <TableHead className="text-right">Completion</TableHead>
              <TableHead className="text-right">Cost</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {usage.map((u) => (
              <TableRow key={u.stage + u.model}>
                <TableCell>
                  <div>
                    {STAGE_LABEL[u.stage] ?? u.stage}
                    {u.errors > 0 && <span className="ml-1 text-xs text-destructive">({u.errors} failed)</span>}
                  </div>
                  <div className="font-mono text-xs text-muted-foreground">{u.model}</div>
                </TableCell>
                <TableCell className="text-right tabular-nums">{u.calls}</TableCell>
                <TableCell className="text-right tabular-nums">
                  {formatTokens(u.promptTokens)}
                  {u.cachedTokens > 0 && (
                    <div className="text-xs text-muted-foreground">{formatTokens(u.cachedTokens)} cached</div>
                  )}
                </TableCell>
                <TableCell className="text-right tabular-nums">{formatTokens(u.completionTokens)}</TableCell>
                <TableCell className="text-right tabular-nums">
                  {formatUsd(u.costUsd)}
                  {u.costEstimated && <span title="estimated from list prices">*</span>}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
          <TableFooter>
            <TableRow>
              <TableCell className="font-medium">
                Total
                {cost.failedCalls > 0 && (
                  <span className="ml-1 text-xs font-normal text-destructive">({cost.failedCalls} failed)</span>
                )}
              </TableCell>
              <TableCell className="text-right tabular-nums">{cost.modelCalls}</TableCell>
              <TableCell className="text-right tabular-nums">{formatTokens(cost.promptTokens)}</TableCell>
              <TableCell className="text-right tabular-nums">{formatTokens(cost.completionTokens)}</TableCell>
              <TableCell className="text-right font-medium tabular-nums">
                {formatUsd(cost.costUsd)}
                {cost.costEstimated && <span title="estimated from list prices">*</span>}
              </TableCell>
            </TableRow>
          </TableFooter>
        </Table>
      </div>
      <p className="text-xs text-muted-foreground">
        Every model call of the job, as reported by OpenRouter. A failed call has no tokens and no cost here.
        {cost.costEstimated && " * No cost was reported for a call: estimated from the list prices."}
      </p>
    </div>
  )
}
