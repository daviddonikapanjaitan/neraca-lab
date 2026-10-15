"use client"

import { CircleAlertIcon, MinusIcon, TrendingDownIcon, TrendingUpIcon } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { formatDate, formatNumber } from "@/lib/format"
import { formatLevel, type PresetId, type PresetSummary, type PriceLevel, type Stance, type Summary } from "@/lib/technical"
import { cn } from "@/lib/utils"

const STANCE_STYLE: Record<Stance, string> = {
  BUY: "border-emerald-600/30 bg-emerald-600/10 text-emerald-700 dark:text-emerald-400",
  ACCUMULATE: "border-sky-600/30 bg-sky-600/10 text-sky-700 dark:text-sky-400",
  WAIT: "border-amber-600/30 bg-amber-600/10 text-amber-700 dark:text-amber-400",
  AVOID: "border-rose-600/30 bg-rose-600/10 text-rose-700 dark:text-rose-400",
}

const STANCE_LABEL: Record<Stance, string> = {
  BUY: "Buy",
  ACCUMULATE: "Accumulate",
  WAIT: "Wait",
  AVOID: "Avoid",
}

/**
 * Below the chart: a quick summary of the chart for each preset (Murphy, Keith Gill, Paul Tudor Jones, Druckenmiller) -
 * stance, buy price, sell price, stop and the thesis from the indicators and price patterns. Computed by the backend
 * with fixed rules (no AI model); the selected preset comes first.
 */
export function PresetSummariesCard({
  summary,
  activePreset,
  onShowPreset,
}: {
  summary: Summary
  activePreset: PresetId | null
  onShowPreset: (preset: PresetId) => void
}) {
  const index = summary.type === "INDEX"
  const ordered = [...summary.summaries].sort(
    (a, b) => Number(b.preset === activePreset) - Number(a.preset === activePreset)
  )
  return (
    <Card>
      <CardHeader>
        <CardTitle>Preset summaries</CardTitle>
        <CardDescription>
          A quick read of the chart in the style of each preset: when to buy, where to sell, where the idea is wrong,
          and why. {summary.ticker} closed at {summary.currency === "IDR" && !index ? "Rp " : ""}
          {formatLevel(summary.close, index)}
          {index ? " points" : ""} on {formatDate(summary.asOfDate)}.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {summary.caveats.length > 0 && (
          <ul role="note" className="space-y-1 rounded-lg border border-amber-600/30 bg-amber-600/5 px-3 py-2 text-xs">
            {summary.caveats.map((c) => (
              <li key={c} className="flex items-start gap-2 text-amber-800 dark:text-amber-300">
                <CircleAlertIcon className="mt-px size-3.5 shrink-0" />
                <span>{c}</span>
              </li>
            ))}
          </ul>
        )}
        <div className="grid gap-4 xl:grid-cols-2">
          {ordered.map((s) => (
            <PresetCard
              key={s.preset}
              summary={s}
              index={index}
              active={s.preset === activePreset}
              onShow={() => onShowPreset(s.preset)}
            />
          ))}
        </div>
        <p className="text-xs text-muted-foreground">{summary.disclaimer}</p>
      </CardContent>
    </Card>
  )
}

function PresetCard({
  summary: s,
  index,
  active,
  onShow,
}: {
  summary: PresetSummary
  index: boolean
  active: boolean
  onShow: () => void
}) {
  return (
    <section
      aria-label={`${s.name} summary`}
      className={cn("flex min-w-0 flex-col gap-3 rounded-xl border p-4", active && "border-primary ring-1 ring-primary/40")}
    >
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <h3 className="flex flex-wrap items-center gap-2 font-semibold">
            {s.name}
            <Badge variant="outline" className={cn("font-semibold", STANCE_STYLE[s.stance])}>
              {STANCE_LABEL[s.stance]}
            </Badge>
          </h3>
          <p className="mt-0.5 text-sm">{s.stanceLabel}</p>
        </div>
        <Button variant={active ? "secondary" : "outline"} size="sm" onClick={onShow} disabled={active}>
          {active ? "On the chart" : "Show on chart"}
        </Button>
      </div>

      {s.buy ? (
        <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
          <PriceTile label={s.buy.type === "STOP" ? "Buy at or above" : "Buy at or below"} level={s.buy} index={index} tone="buy" />
          <PriceTile label="Sell (target)" level={s.sell} index={index} tone="sell" />
          <PriceTile label="Stop loss" level={s.stopLoss} index={index} tone="stop" />
        </div>
      ) : (
        <p className="rounded-lg border border-dashed px-3 py-2 text-xs text-muted-foreground">
          Too little history for prices (no ATR yet).
        </p>
      )}

      <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
        <span>
          Score <span className="font-mono text-foreground tabular-nums">{formatNumber(s.score, 1)}</span> of{" "}
          <span className="font-mono tabular-nums">±{formatNumber(s.maxScore, 1)}</span>
        </span>
        {s.rewardRisk !== null && (
          <span>
            Reward / risk <span className="font-mono text-foreground tabular-nums">{formatNumber(s.rewardRisk, 2)}</span>
          </span>
        )}
      </div>

      <p className="text-sm leading-relaxed">{s.thesis}</p>

      <details className="group text-sm">
        <summary className="cursor-pointer text-xs font-medium text-muted-foreground select-none hover:text-foreground">
          Evidence ({s.points.length}) · {s.style}
        </summary>
        <ul className="mt-2 space-y-1.5">
          {s.points.map((p, i) => (
            <li key={i} className="flex items-start gap-2 text-xs">
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
      </details>
    </section>
  )
}

function PriceTile({
  label,
  level,
  index,
  tone,
}: {
  label: string
  level: PriceLevel | null
  index: boolean
  tone: "buy" | "sell" | "stop"
}) {
  return (
    <div className="flex min-w-0 flex-col gap-0.5 rounded-lg border bg-muted/30 px-3 py-2" title={level?.basis}>
      <span className="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">{label}</span>
      <span
        className={cn(
          "font-mono text-base font-semibold tabular-nums",
          tone === "buy" && "text-emerald-700 dark:text-emerald-400",
          tone === "stop" && "text-rose-700 dark:text-rose-400"
        )}
      >
        {level ? formatLevel(level.price, index) : "—"}
      </span>
      {level && (
        <>
          <span className="font-mono text-xs text-muted-foreground tabular-nums">
            {level.distancePct === null ? "" : `${level.distancePct > 0 ? "+" : ""}${formatNumber(level.distancePct, 1)}% vs close`}
          </span>
          <span className="line-clamp-3 text-xs text-muted-foreground">{level.basis}</span>
        </>
      )}
    </div>
  )
}
