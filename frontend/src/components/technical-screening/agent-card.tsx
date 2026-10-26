"use client"

import { MinusIcon, TrendingDownIcon, TrendingUpIcon } from "lucide-react"

import { PlanTiles } from "@/components/technical-screening/plan-tiles"
import { Badge } from "@/components/ui/badge"
import { formatNumber } from "@/lib/format"
import { formatScore, scoreClass } from "@/lib/screening"
import {
  planSourceLabel,
  STANCE_LABEL,
  STANCE_STYLE,
  type RulesSummary,
  type TechnicalAgentScore,
} from "@/lib/technical-screening"
import { cn } from "@/lib/utils"

/**
 * One technical agent's view: its stance and scores (rules prior, AI, final), its trade plan (buy, sell, stop loss),
 * thesis, supports and risks, the reflection, and the preset's rules summary with its evidence.
 */
export function TechnicalAgentCard({ score, rules }: { score: TechnicalAgentScore; rules: RulesSummary | undefined }) {
  const r = score.reflection
  return (
    <section aria-label={`${score.label} view`} className="flex min-w-0 flex-col gap-2.5 rounded-xl border p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="flex flex-wrap items-center gap-2 font-medium">
          {score.label}
          {score.stance && (
            <Badge variant="outline" className={cn("font-semibold", STANCE_STYLE[score.stance])}>
              {STANCE_LABEL[score.stance]}
            </Badge>
          )}
        </span>
        <span className="flex items-center gap-3 text-xs tabular-nums">
          <span className="text-muted-foreground">rules {formatScore(score.priorScore)}</span>
          <span className="text-muted-foreground">AI {formatScore(score.llmScore)}</span>
          <span className={cn("text-sm font-semibold", scoreClass(score.finalScore))}>{formatScore(score.finalScore)}</span>
        </span>
      </div>

      <PlanTiles plan={score.plan} compact />
      <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
        <span>Levels: {planSourceLabel(score.plan)}</span>
        {score.plan?.rewardRisk != null && (
          <span>
            Reward / risk{" "}
            <span className="font-mono text-foreground tabular-nums">{formatNumber(score.plan.rewardRisk, 2)}</span>
          </span>
        )}
      </div>

      {score.thesis && <p className="text-sm">{score.thesis}</p>}
      {((score.supports?.length ?? 0) > 0 || (score.risks?.length ?? 0) > 0) && (
        <div className="grid gap-1 text-xs sm:grid-cols-2">
          <ul>
            {score.supports?.map((s) => (
              <li key={s} className="text-emerald-700 dark:text-emerald-400">+ {s}</li>
            ))}
          </ul>
          <ul>
            {score.risks?.map((s) => (
              <li key={s} className="text-rose-700 dark:text-rose-400">− {s}</li>
            ))}
          </ul>
        </div>
      )}
      {score.status === "PRIOR_ONLY" && (
        <p className="text-xs text-muted-foreground">
          Rules summary only{r?.error ? `: ${r.error}` : ""}.
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

      {rules && (
        <details className="text-xs">
          <summary className="cursor-pointer text-muted-foreground select-none hover:text-foreground">
            Rules summary of the {rules.name} preset: {STANCE_LABEL[rules.stance]}, score {formatNumber(rules.score, 1)} of ±
            {formatNumber(rules.maxScore, 1)} · {rules.points.length} evidence points
          </summary>
          <div className="mt-2 flex flex-col gap-2">
            <p>{rules.stanceLabel}</p>
            <ul className="space-y-1.5">
              {rules.points.map((p, i) => (
                <li key={i} className="flex items-start gap-2">
                  {p.tone === "POSITIVE" ? (
                    <TrendingUpIcon aria-label="supports" className="mt-px size-3.5 shrink-0 text-emerald-600 dark:text-emerald-400" />
                  ) : p.tone === "NEGATIVE" ? (
                    <TrendingDownIcon aria-label="against" className="mt-px size-3.5 shrink-0 text-rose-600 dark:text-rose-400" />
                  ) : (
                    <MinusIcon aria-label="neutral" className="mt-px size-3.5 shrink-0 text-muted-foreground" />
                  )}
                  <span>{p.text}</span>
                </li>
              ))}
            </ul>
            <p className="text-muted-foreground">{rules.thesis}</p>
          </div>
        </details>
      )}
    </section>
  )
}
