"use client"

import Link from "next/link"
import { ExternalLinkIcon, LineChartIcon, SparklesIcon, TriangleAlertIcon } from "lucide-react"

import { TechnicalAgentCard } from "@/components/technical-screening/agent-card"
import { PlanTiles } from "@/components/technical-screening/plan-tiles"
import { Badge } from "@/components/ui/badge"
import { Separator } from "@/components/ui/separator"
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from "@/components/ui/sheet"
import { EMPTY, formatCompact, formatDate, formatNumber } from "@/lib/format"
import { technicalsHref } from "@/lib/links"
import { CONVICTION_CLASS, formatScore, scoreClass, yahooQuoteUrl } from "@/lib/screening"
import {
  formatIdr,
  formatSignedPct,
  planSourceLabel,
  STANCE_LABEL,
  STANCE_STYLE,
  type TechnicalAgentCode,
  type TechnicalCandidate,
  type TechnicalMetrics,
} from "@/lib/technical-screening"
import { cn } from "@/lib/utils"

/** The preset of Companies › Technicals each agent's rules summary comes from. */
const PRESET_OF: Record<TechnicalAgentCode, string> = {
  GILL: "gill",
  MURPHY: "murphy",
  TUDOR_JONES: "ptj",
  DRUCKENMILLER: "druckenmiller",
}

const METRICS: { key: keyof TechnicalMetrics; label: string; kind: "price" | "pct" | "signed" | "number" | "value" | "text" }[] = [
  { key: "changePct", label: "Change (day)", kind: "signed" },
  { key: "vsSma50Pct", label: "vs SMA 50", kind: "signed" },
  { key: "vsSma200Pct", label: "vs SMA 200", kind: "signed" },
  { key: "cross", label: "SMA 50 / 200", kind: "text" },
  { key: "rsi14", label: "RSI (14)", kind: "number" },
  { key: "macdHistogram", label: "MACD histogram", kind: "number" },
  { key: "adx14", label: "ADX (14)", kind: "number" },
  { key: "atrPct", label: "ATR % of price", kind: "pct" },
  { key: "momentum12_1Pct", label: "12-1 month momentum", kind: "signed" },
  { key: "return6mPct", label: "6-month return", kind: "signed" },
  { key: "percentile6m", label: "6-month percentile", kind: "number" },
  { key: "rsChange50Pct", label: "RS vs IHSG (50 days)", kind: "signed" },
  { key: "week52High", label: "52-week high", kind: "price" },
  { key: "week52Low", label: "52-week low", kind: "price" },
  { key: "range52Pct", label: "In 52-week range", kind: "pct" },
  { key: "athDrawdownPct", label: "From the high", kind: "signed" },
  { key: "upDownVolumeRatio50", label: "Up / down volume", kind: "number" },
  { key: "avgValue20", label: "Traded per day (20d)", kind: "value" },
]

function metric(value: unknown, kind: (typeof METRICS)[number]["kind"]): string {
  if (kind === "text") return typeof value === "string" ? value.toLowerCase() : EMPTY
  if (typeof value !== "number") return EMPTY
  switch (kind) {
    case "price":
      return formatIdr(value)
    case "pct":
      return `${formatNumber(value, 1)}%`
    case "signed":
      return formatSignedPct(value)
    case "value":
      return formatCompact(value, "IDR")
    default:
      return formatNumber(value, 2)
  }
}

/** Details of one candidate: the trade plan, thesis, red flags, key technicals, the chart link and every agent. */
export function TechnicalCandidateSheet({
  candidate,
  exchange,
  onClose,
}: {
  candidate: TechnicalCandidate | null
  exchange: string
  onClose: () => void
}) {
  const c = candidate
  return (
    <Sheet open={c !== null} onOpenChange={(open) => !open && onClose()}>
      <SheetContent className="w-full overflow-y-auto data-[side=right]:sm:max-w-2xl">
        {c && (
          <>
            <SheetHeader>
              <SheetTitle className="flex flex-wrap items-center gap-2">
                <span className="font-mono">{c.ticker}</span>
                <span className="font-normal">{c.companyName}</span>
              </SheetTitle>
              <SheetDescription>
                {[c.sector, c.marketCap != null ? `market cap ${formatCompact(c.marketCap, "IDR")}` : null]
                  .filter(Boolean)
                  .join(" · ") || EMPTY}
                {` · close Rp ${formatIdr(c.close)} on ${formatDate(c.asOfDate)}`}
                {c.finalRank != null && ` · rank ${c.finalRank}${c.selected ? " (selected)" : ""}`}
              </SheetDescription>
            </SheetHeader>

            <div className="flex flex-col gap-4 px-4 pb-6">
              <div className="flex flex-wrap items-center gap-3">
                <div>
                  <p className="text-xs text-muted-foreground">Overall score</p>
                  <p className={cn("text-2xl font-semibold tabular-nums", scoreClass(c.overallScore))}>
                    {formatScore(c.overallScore)}
                  </p>
                </div>
                <div className="text-xs text-muted-foreground">
                  Rules {formatScore(c.priorOverall)} (Stage 1 rank {c.priorRank ?? EMPTY})
                  {c.synthesisAdjustment != null && c.synthesisAdjustment !== 0 && (
                    <div>
                      Synthesis adjustment {c.synthesisAdjustment > 0 ? "+" : ""}
                      {c.synthesisAdjustment}
                    </div>
                  )}
                </div>
                {c.action && (
                  <Badge variant="outline" className={cn("font-semibold", STANCE_STYLE[c.action])}>
                    {STANCE_LABEL[c.action]}
                  </Badge>
                )}
                {c.conviction && (
                  <Badge variant="outline" className={cn("border-transparent", CONVICTION_CLASS[c.conviction])}>
                    {c.conviction.toLowerCase()} conviction
                  </Badge>
                )}
              </div>

              <section className="flex flex-col gap-2">
                <h3 className="text-sm font-semibold">Trade plan</h3>
                <PlanTiles plan={c.plan} />
                <p className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
                  <span>Set by: {planSourceLabel(c.plan)}</span>
                  {c.plan?.rewardRisk != null && (
                    <span>
                      Reward / risk{" "}
                      <span className="font-mono text-foreground tabular-nums">{formatNumber(c.plan.rewardRisk, 2)}</span>
                    </span>
                  )}
                </p>
                {c.plan?.note && <p className="text-xs text-amber-700 dark:text-amber-400">{c.plan.note}</p>}
              </section>

              {c.thesis && (
                <p className="flex gap-2 text-sm">
                  <SparklesIcon className="mt-0.5 size-4 shrink-0 text-primary" />
                  <span>{c.thesis}</span>
                </p>
              )}

              {c.redFlags && c.redFlags.length > 0 && (
                <ul className="flex flex-col gap-1 rounded-lg bg-amber-500/10 p-2.5 text-xs text-amber-700 dark:text-amber-400">
                  {c.redFlags.map((flag) => (
                    <li key={flag} className="flex gap-1.5">
                      <TriangleAlertIcon className="mt-px size-3.5 shrink-0" />
                      {flag}
                    </li>
                  ))}
                </ul>
              )}

              <Separator />
              <section className="flex flex-col gap-2">
                <h3 className="text-sm font-semibold">Key technicals</h3>
                <dl className="grid grid-cols-2 gap-x-4 gap-y-1.5 text-sm sm:grid-cols-3">
                  {METRICS.filter((m) => c.metrics && c.metrics[m.key] !== undefined).map((m) => (
                    <div key={m.key} className="flex flex-col">
                      <dt className="text-xs text-muted-foreground">{m.label}</dt>
                      <dd className="tabular-nums">{metric(c.metrics?.[m.key], m.kind)}</dd>
                    </div>
                  ))}
                </dl>
                <p className="text-xs text-muted-foreground">
                  {c.metrics?.tradingDays != null && `${c.metrics.tradingDays} daily bars from ${formatDate(c.metrics.firstDate)}`}
                  {c.metrics?.baseDetected ? " · a base is forming" : ""}
                </p>
                <div className="flex flex-wrap gap-3">
                  {c.inCompanies && (
                    <Link
                      href={technicalsHref(exchange, c.ticker)}
                      className="inline-flex items-center gap-1 text-xs text-primary hover:underline"
                    >
                      <LineChartIcon className="size-3" /> Open the chart on Companies › Technicals
                    </Link>
                  )}
                  <a
                    href={yahooQuoteUrl(exchange, c.ticker)}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="inline-flex items-center gap-1 text-xs text-primary hover:underline"
                  >
                    {c.ticker} on Yahoo Finance <ExternalLinkIcon className="size-3" />
                  </a>
                </div>
              </section>

              <Separator />
              <section className="flex flex-col gap-3">
                <h3 className="text-sm font-semibold">Technical agents</h3>
                {c.agents.map((a) => (
                  <TechnicalAgentCard
                    key={a.agent}
                    score={a}
                    rules={c.rules?.summaries.find((s) => s.preset === PRESET_OF[a.agent])}
                  />
                ))}
              </section>
            </div>
          </>
        )}
      </SheetContent>
    </Sheet>
  )
}
