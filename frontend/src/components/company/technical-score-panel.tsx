"use client"

import { useEffect, useState } from "react"
import Link from "next/link"
import { CircleAlertIcon, LineChartIcon } from "lucide-react"

import { EmptyState } from "@/components/empty-state"
import { Evidence, PriceTile, STANCE_STYLE } from "@/components/technical/preset-summaries"
import { Badge } from "@/components/ui/badge"
import { buttonVariants } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Skeleton } from "@/components/ui/skeleton"
import { RequestError } from "@/lib/client-api"
import { EMPTY, formatDate, formatNumber } from "@/lib/format"
import { technicalsHref } from "@/lib/links"
import { scoreTone } from "@/lib/scorecards"
import {
  fetchValidated,
  formatLevel,
  STANCE_LABELS,
  STANCES,
  technicalScoreSchema,
  technicalUrl,
  type PresetSummary,
  type TechnicalAgentScore,
  type TechnicalScoreResponse,
} from "@/lib/technical"
import { cn } from "@/lib/utils"

const TONE_TEXT = {
  good: "text-emerald-600 dark:text-emerald-400",
  mid: "text-amber-600 dark:text-amber-400",
  poor: "text-rose-600 dark:text-rose-400",
  none: "text-muted-foreground",
} as const

const TONE_BAR = {
  good: "bg-emerald-500",
  mid: "bg-amber-500",
  poor: "bg-rose-500",
  none: "bg-muted-foreground/30",
} as const

function Bar({ score, className }: { score: number | null; className?: string }) {
  const tone = scoreTone(score)
  return (
    <div className={cn("h-1.5 w-full overflow-hidden rounded-full bg-muted", className)}>
      <div
        className={cn("h-full rounded-full", TONE_BAR[tone])}
        style={{ width: `${Math.max(0, Math.min(100, score ?? 0))}%` }}
      />
    </div>
  )
}

type State =
  | { state: "loading" }
  | { state: "ready"; data: TechnicalScoreResponse }
  | { state: "error"; message: string }

/**
 * The Technical (Score) tab of a company: the preset summaries of Companies > Technicals (Murphy, Keith Gill, Paul Tudor
 * Jones, Druckenmiller) as four agents, each with its score on 0-100, its stance, buy / sell / stop, thesis and
 * evidence, and the overall of the four. No chart. Rules only, no AI model (backend TechnicalScore). Loaded when the
 * tab is first opened.
 */
export function TechnicalScorePanel({ exchange, ticker }: { exchange: string; ticker: string }) {
  const [result, setResult] = useState<State>({ state: "loading" })
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    const abort = new AbortController()
    fetchValidated(technicalUrl(exchange, ticker, "score"), technicalScoreSchema, abort.signal)
      .then((data) => {
        if (!abort.signal.aborted) setResult({ state: "ready", data })
      })
      .catch((e: unknown) => {
        if (abort.signal.aborted) return
        setResult({
          state: "error",
          message: e instanceof RequestError ? e.message : "The technical score could not be loaded.",
        })
      })
    return () => abort.abort()
  }, [exchange, ticker, attempt])

  if (result.state === "loading") {
    return (
      <div className="flex flex-col gap-4" aria-busy="true" aria-label="Loading the technical score">
        <Skeleton className="h-[140px]" />
        <div className="grid gap-4 xl:grid-cols-2">
          {[0, 1, 2, 3].map((i) => (
            <Skeleton key={i} className="h-[320px]" />
          ))}
        </div>
      </div>
    )
  }

  if (result.state === "error") {
    return (
      <Card>
        <CardContent>
          <EmptyState
            variant="generic"
            title="The technical score cannot be loaded"
            description={result.message}
            actionLabel="Try again"
            onAction={() => {
              setResult({ state: "loading" })
              setAttempt((n) => n + 1)
            }}
          />
        </CardContent>
      </Card>
    )
  }

  const d = result.data
  const t = d.technicalScore
  const tone = scoreTone(t.overall)
  const decimals: number | boolean = d.priceDecimals ?? false
  const byPreset = new Map(t.agents.map((a) => [a.preset, a]))

  return (
    <div className="flex flex-col gap-4">
      {/* Overall */}
      <Card>
        <CardContent className="flex flex-col gap-4">
          <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
            <div className="flex items-center gap-4">
              <div className={cn("text-4xl font-semibold tracking-tight tabular-nums", TONE_TEXT[tone])}>
                {t.overall === null ? EMPTY : formatNumber(t.overall, 1)}
              </div>
              <div className="space-y-1">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-sm font-medium">Overall technical score</span>
                  {t.stance && (
                    <Badge variant="outline" className={cn("font-semibold", STANCE_STYLE[t.stance])}>
                      {STANCE_LABELS[t.stance]}
                    </Badge>
                  )}
                </div>
                <p className="text-xs text-muted-foreground">
                  Average of the four agents&apos; scores (0 - 100). Rules only, no AI.
                </p>
              </div>
            </div>
            <div className="flex flex-col gap-2 lg:items-end">
              <p className="text-xs text-muted-foreground lg:text-right">
                Close {d.currency === "IDR" ? "Rp " : ""}
                {formatLevel(d.close, decimals)} on {formatDate(d.asOfDate)} · rules {d.rulesVersion}
              </p>
              <Link
                href={technicalsHref(d.exchange, d.ticker)}
                className={buttonVariants({ variant: "outline", size: "sm" })}
              >
                <LineChartIcon /> Open the chart in Technicals
              </Link>
            </div>
          </div>

          <p className="text-sm leading-relaxed">{t.summary}</p>

          {/* every agent's score at a glance */}
          <ul className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4" aria-label="Score of each agent">
            {t.agents.map((a) => (
              <AgentScoreTile key={a.preset} agent={a} />
            ))}
          </ul>

          <div className="flex flex-wrap gap-2 text-xs text-muted-foreground">
            {STANCES.filter((s) => (t.stances[s] ?? 0) > 0).map((s) => (
              <Badge key={s} variant="outline" className={cn("font-normal", STANCE_STYLE[s])}>
                {t.stances[s]} × {STANCE_LABELS[s]}
              </Badge>
            ))}
          </div>
        </CardContent>
      </Card>

      {d.caveats.length > 0 && (
        <ul role="note" className="space-y-1 rounded-lg border border-amber-600/30 bg-amber-600/5 px-3 py-2 text-xs">
          {d.caveats.map((c) => (
            <li key={c} className="flex items-start gap-2 text-amber-800 dark:text-amber-300">
              <CircleAlertIcon className="mt-px size-3.5 shrink-0" />
              <span>{c}</span>
            </li>
          ))}
        </ul>
      )}

      {/* each agent's summary */}
      <div className="grid gap-4 xl:grid-cols-2">
        {d.summaries.map((s) => (
          <AgentSummaryCard key={s.preset} summary={s} score={byPreset.get(s.preset) ?? null} decimals={decimals} />
        ))}
      </div>

      <Card>
        <CardHeader>
          <CardTitle>How it is scored</CardTitle>
          <CardDescription>
            Each agent is a preset of Companies &gt; Technicals, read by fixed rules from the stored daily prices: its
            evidence votes up or down, and the votes add up to its rules score between −max and +max. The agent&apos;s
            score is 50 + 50 × rules score ÷ max (0 - 100). The overall is the average of the four agents: Buy from 75,
            Accumulate from 58.3, Wait from 41.7, Avoid below. The buy, sell (target) and stop-loss prices are the
            same as on the Technicals page. {d.disclaimer}
          </CardDescription>
        </CardHeader>
      </Card>
    </div>
  )
}

function AgentScoreTile({ agent: a }: { agent: TechnicalAgentScore }) {
  const tone = scoreTone(a.score)
  return (
    <li className="flex min-w-0 flex-col gap-1.5 rounded-lg border bg-muted/30 px-3 py-2">
      <div className="flex items-center justify-between gap-2">
        <span className="truncate text-sm font-medium">{a.name}</span>
        {a.stance && (
          <Badge variant="outline" className={cn("shrink-0 font-normal", STANCE_STYLE[a.stance])}>
            {STANCE_LABELS[a.stance]}
          </Badge>
        )}
      </div>
      <div className="flex items-baseline justify-between gap-2">
        <span className={cn("font-mono text-xl font-semibold tabular-nums", TONE_TEXT[tone])}>
          {formatNumber(a.score, 1)}
        </span>
        <span className="font-mono text-xs text-muted-foreground tabular-nums">
          rules {formatNumber(a.raw, 1)} of ±{formatNumber(a.maxScore, 1)}
        </span>
      </div>
      <Bar score={a.score} />
    </li>
  )
}

function AgentSummaryCard({
  summary: s,
  score,
  decimals,
}: {
  summary: PresetSummary
  score: TechnicalAgentScore | null
  decimals: boolean | number
}) {
  const tone = scoreTone(score?.score ?? null)
  return (
    <section aria-label={`${s.name} summary`} className="flex min-w-0 flex-col gap-3 rounded-xl border bg-card p-4">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <h3 className="flex flex-wrap items-center gap-2 font-semibold">
            {s.name}
            <Badge variant="outline" className={cn("font-semibold", STANCE_STYLE[s.stance])}>
              {STANCE_LABELS[s.stance]}
            </Badge>
          </h3>
          <p className="mt-0.5 text-sm">{s.stanceLabel}</p>
        </div>
        <div className="shrink-0 text-right">
          <div className={cn("text-2xl font-semibold tabular-nums", TONE_TEXT[tone])}>
            {score ? formatNumber(score.score, 1) : EMPTY}
          </div>
          <div className="text-[11px] text-muted-foreground">score of 100</div>
        </div>
      </div>
      <Bar score={score?.score ?? null} />

      {s.buy ? (
        <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
          <PriceTile
            label={s.buy.type === "STOP" ? "Buy at or above" : "Buy at or below"}
            level={s.buy}
            decimals={decimals}
            tone="buy"
          />
          <PriceTile label="Sell (target)" level={s.sell} decimals={decimals} tone="sell" />
          <PriceTile label="Stop loss" level={s.stopLoss} decimals={decimals} tone="stop" />
        </div>
      ) : (
        <p className="rounded-lg border border-dashed px-3 py-2 text-xs text-muted-foreground">
          Too little history for prices (no ATR yet).
        </p>
      )}

      <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
        <span>
          Rules score <span className="font-mono text-foreground tabular-nums">{formatNumber(s.score, 1)}</span> of{" "}
          <span className="font-mono tabular-nums">±{formatNumber(s.maxScore, 1)}</span>
        </span>
        {s.rewardRisk !== null && (
          <span>
            Reward / risk <span className="font-mono text-foreground tabular-nums">{formatNumber(s.rewardRisk, 2)}</span>
          </span>
        )}
      </div>

      <p className="text-sm leading-relaxed">{s.thesis}</p>

      <Evidence summary={s} />
    </section>
  )
}
