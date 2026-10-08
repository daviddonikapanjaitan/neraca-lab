"use client"

import { Badge } from "@/components/ui/badge"
import { EMPTY, formatNumber } from "@/lib/format"
import { formatScore, scoreClass, verdictLabel } from "@/lib/screening"
import type { AgentScore } from "@/lib/types"
import { cn } from "@/lib/utils"

/** One investor agent's view: scores, verdict, thesis, strengths and concerns, reflection, quantitative scorecard. */
export function AgentCard({ score }: { score: AgentScore }) {
  const r = score.reflection
  return (
    <div className="flex flex-col gap-1.5 rounded-lg border p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="font-medium">{score.label}</span>
        <span className="flex items-center gap-3 text-xs tabular-nums">
          <span className="text-muted-foreground">quant {formatScore(score.quantScore)}</span>
          <span className="text-muted-foreground">AI {formatScore(score.llmScore)}</span>
          <span className={cn("text-sm font-semibold", scoreClass(score.finalScore))}>
            {formatScore(score.finalScore)}
          </span>
          {score.verdict && <Badge variant="secondary">{verdictLabel(score.verdict)}</Badge>}
        </span>
      </div>
      {score.thesis && <p className="text-sm">{score.thesis}</p>}
      {((score.strengths?.length ?? 0) > 0 || (score.concerns?.length ?? 0) > 0) && (
        <div className="grid gap-1 text-xs sm:grid-cols-2">
          <ul>
            {score.strengths?.map((s) => (
              <li key={s} className="text-emerald-700 dark:text-emerald-400">+ {s}</li>
            ))}
          </ul>
          <ul>
            {score.concerns?.map((s) => (
              <li key={s} className="text-rose-700 dark:text-rose-400">− {s}</li>
            ))}
          </ul>
        </div>
      )}
      {score.status === "QUANT_ONLY" && (
        <p className="text-xs text-muted-foreground">
          Quantitative score only{r?.error ? `: ${r.error}` : ""}.
        </p>
      )}
      {score.status === "NO_SCORE" && (
        <p className="text-xs text-muted-foreground">
          No score: no model answer and no market data{r?.error ? ` (${r.error})` : ""}.
        </p>
      )}
      {r?.issues && r.issues.length > 0 && (
        <div className="rounded-md bg-muted/60 p-2 text-xs">
          <span className="font-medium">Reflection: </span>
          {r.issues.map((i) => i.message).join("; ")}.
          {r.revised && (
            <span>
              {" "}
              {r.changed
                ? `Revised ${Math.round(r.original?.score ?? 0)} → ${Math.round(r.revised.score)}`
                : "Kept after review"}
              {r.note ? ` (${r.note})` : ""}.
            </span>
          )}
        </div>
      )}
      {score.quantDetail && score.quantDetail.parts.length > 0 && (
        <details className="text-xs">
          <summary className="cursor-pointer text-muted-foreground">
            Quantitative scorecard (coverage {Math.round(score.quantDetail.coverage * 100)}%)
          </summary>
          <table className="mt-1 w-full">
            <tbody>
              {score.quantDetail.parts.map((p) => (
                <tr key={p.key} className="border-t">
                  <td className="py-0.5 pr-2">{p.label}</td>
                  <td className="py-0.5 pr-2 text-right tabular-nums">
                    {p.value == null ? "unknown" : formatNumber(p.value, 3)}
                  </td>
                  <td className="py-0.5 text-right tabular-nums text-muted-foreground">
                    {p.points == null ? EMPTY : `${Math.round(p.points * 100)}% × ${p.weight}`}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </details>
      )}
    </div>
  )
}
