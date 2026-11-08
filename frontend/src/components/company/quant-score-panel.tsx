import { CircleCheckIcon, CircleHelpIcon, InfoIcon, TriangleAlertIcon } from "lucide-react"

import { EmptyState } from "@/components/empty-state"
import { Badge } from "@/components/ui/badge"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { EMPTY, formatDate, formatNumber, formatPercent } from "@/lib/format"
import {
  type AgentScorecard,
  findings,
  formatMetricValue,
  type ScorecardCriterion,
  type Scorecards,
  scoreTone,
  SOURCE_LABELS,
  VERDICT_LABELS,
} from "@/lib/scorecards"
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

function Bar({ share, tone, className }: { share: number; tone: keyof typeof TONE_BAR; className?: string }) {
  return (
    <div className={cn("h-1.5 w-full overflow-hidden rounded-full bg-muted", className)}>
      <div className={cn("h-full rounded-full", TONE_BAR[tone])} style={{ width: `${Math.max(0, Math.min(1, share)) * 100}%` }} />
    </div>
  )
}

function CriterionRow({ c }: { c: ScorecardCriterion }) {
  const tone = c.points === null ? "none" : c.points >= 0.65 ? "good" : c.points >= 0.45 ? "mid" : "poor"
  return (
    <li className={cn("flex flex-col gap-1 py-2", !c.applies && "opacity-50")}>
      <div className="flex items-baseline justify-between gap-2 text-sm">
        <span className="min-w-0">
          {c.label}
          <span className="ml-1 text-xs text-muted-foreground">×{formatNumber(c.weight, 1)}</span>
        </span>
        <span className={cn("shrink-0 font-medium tabular-nums", TONE_TEXT[tone])}>
          {!c.applies ? "n/a" : c.value === null ? "unknown" : formatMetricValue(c.value, c.unit)}
        </span>
      </div>
      {c.applies && <Bar share={c.points ?? 0} tone={tone} />}
      <div className="flex flex-wrap justify-between gap-x-2 text-[11px] text-muted-foreground">
        <span>
          poor {formatMetricValue(c.poor, c.unit)} → good {formatMetricValue(c.good, c.unit)}
        </span>
        <span>
          {!c.applies
            ? "not applicable to this company"
            : c.points === null
              ? "no data"
              : `${formatPercent(c.points, 0)} of points · ${c.source ? SOURCE_LABELS[c.source] : ""}${c.basis ? ` (${c.basis})` : ""}`}
        </span>
      </div>
    </li>
  )
}

function AgentCard({ card }: { card: AgentScorecard }) {
  const tone = scoreTone(card.score)
  const { strengths, concerns, gaps } = findings(card)
  return (
    <Card size="sm" className="h-full">
      <CardHeader>
        <div className="flex items-start justify-between gap-2">
          <div className="min-w-0 space-y-1">
            <CardTitle>{card.label}</CardTitle>
            <CardDescription className="text-xs">{card.focus}</CardDescription>
          </div>
          <div className="shrink-0 text-right">
            <div className={cn("text-2xl font-semibold tabular-nums", TONE_TEXT[tone])}>
              {card.score === null ? EMPTY : formatNumber(card.score, 1)}
            </div>
            {card.verdict && (
              <Badge variant="outline" className="font-normal">
                {VERDICT_LABELS[card.verdict]}
              </Badge>
            )}
          </div>
        </div>
        <Bar share={(card.score ?? 0) / 100} tone={tone} className="mt-2" />
        <p className="text-[11px] text-muted-foreground">
          coverage {formatPercent(card.coverage, 0)}
          {!card.inOverall && card.score !== null && " · too little known: not in the overall"}
        </p>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <ul className="divide-y">
          {card.criteria.map((c) => (
            <CriterionRow key={c.key} c={c} />
          ))}
        </ul>
        {(strengths.length > 0 || concerns.length > 0 || gaps.length > 0) && (
          <div className="flex flex-col gap-1.5 rounded-lg bg-muted/40 p-2.5 text-xs">
            {strengths.map((s) => (
              <p key={s} className="flex items-start gap-1.5">
                <CircleCheckIcon className="mt-px size-3.5 shrink-0 text-emerald-500" />
                <span>{s}</span>
              </p>
            ))}
            {concerns.map((s) => (
              <p key={s} className="flex items-start gap-1.5">
                <TriangleAlertIcon className="mt-px size-3.5 shrink-0 text-rose-500" />
                <span>{s}</span>
              </p>
            ))}
            {gaps.map((s) => (
              <p key={s} className="flex items-start gap-1.5 text-muted-foreground">
                <CircleHelpIcon className="mt-px size-3.5 shrink-0" />
                <span>{s}</span>
              </p>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  )
}

/**
 * The Quant Score tab: the six fundamental agents (Buffett, Munger, Lynch, Fisher, Keith Gill, Risk) scored by preset
 * rules on the stored data - the filings, the valuation and the prices, with Yahoo Finance's screening data where
 * those lack a metric. No AI: the same scorecards as the quantitative part of Screening > Analysis.
 */
export function QuantScorePanel({ scorecards, error }: { scorecards: Scorecards | null; error: string | null }) {
  if (!scorecards) {
    return (
      <Card>
        <CardContent>
          <EmptyState variant="generic" title="The quant score cannot be loaded" description={error ?? "Unknown error"} />
        </CardContent>
      </Card>
    )
  }
  const s = scorecards
  const tone = scoreTone(s.overall)
  const dates = [
    s.fiscalYear && `filings ${s.fiscalYear}`,
    s.valuationDate && `valuation ${formatDate(s.valuationDate)}`,
    s.pricesDate && `prices to ${formatDate(s.pricesDate)}`,
    s.screeningDate && `screening data ${formatDate(s.screeningDate)}`,
  ].filter(Boolean)

  return (
    <div className="flex flex-col gap-4">
      <Card>
        <CardContent className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
          <div className="flex items-center gap-4">
            <div className={cn("text-4xl font-semibold tracking-tight tabular-nums", TONE_TEXT[tone])}>
              {s.overall === null ? EMPTY : formatNumber(s.overall, 1)}
            </div>
            <div className="space-y-1">
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-sm font-medium">Overall quant score</span>
                {s.verdict && <Badge variant="outline">{VERDICT_LABELS[s.verdict]}</Badge>}
                {s.financial && <Badge variant="secondary">Scored as a financial company</Badge>}
              </div>
              <p className="text-xs text-muted-foreground">
                Average of the investor agents, {formatPercent(1 - s.riskWeight, 0)} with the Risk agent at{" "}
                {formatPercent(s.riskWeight, 0)}; only scorecards with at least {formatPercent(s.minCoverage, 0)} of their
                metrics known count. Rules only, no AI.
              </p>
            </div>
          </div>
          <p className="text-xs text-muted-foreground lg:max-w-sm lg:text-right">{dates.join(" · ") || "No data stored"}</p>
        </CardContent>
      </Card>

      {s.notes.length > 0 && (
        <div className="flex flex-col gap-1">
          {s.notes.map((n) => (
            <p key={n} className="flex items-start gap-2 text-xs text-muted-foreground">
              <InfoIcon className="mt-px size-3.5 shrink-0" />
              <span>{n}</span>
            </p>
          ))}
        </div>
      )}

      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
        {s.agents.map((a) => (
          <AgentCard key={a.agent} card={a} />
        ))}
      </div>

      <Card>
        <CardHeader>
          <CardTitle>How it is scored</CardTitle>
          <CardDescription>
            Every criterion maps its metric linearly from the poor bound (0 points) to the good bound (full points); the
            score is the weighted share of points earned on the known criteria, times 0.75 + 0.25 × coverage, so missing
            data never raises a score. Statement and valuation metrics come from the filings and the valuation (Neraca
            Lab&apos;s data) first, Yahoo Finance&apos;s screening data fills the gaps; market data (price, traded value,
            52-week range, beta, insider ownership) comes from the screening data first, the stored prices next. A
            wrong input gives a wrong criterion: compare with the Valuation and Metrics tabs when a value looks off.
          </CardDescription>
        </CardHeader>
      </Card>
    </div>
  )
}
