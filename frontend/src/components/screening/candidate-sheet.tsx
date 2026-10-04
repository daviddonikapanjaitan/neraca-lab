"use client"

import { ExternalLinkIcon, NewspaperIcon, SparklesIcon, TriangleAlertIcon } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { Separator } from "@/components/ui/separator"
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from "@/components/ui/sheet"
import { EMPTY, formatCompact, formatDate, formatMultiple, formatNumber, formatPercent } from "@/lib/format"
import {
  CONVICTION_CLASS,
  formatScore,
  METRIC_FORMAT,
  scoreClass,
  SENTIMENT_CLASS,
  verdictLabel,
  yahooQuoteUrl,
} from "@/lib/screening"
import type { AgentScore, ScreeningCandidate } from "@/lib/types"
import { cn } from "@/lib/utils"

function metric(value: unknown, kind: (typeof METRIC_FORMAT)[number]["kind"]): string {
  if (typeof value !== "number") return EMPTY
  switch (kind) {
    case "percent":
      return formatPercent(value, 1)
    case "multiple":
      return formatMultiple(value)
    case "compact":
      return formatCompact(value, "IDR")
    default:
      return formatNumber(value, 2)
  }
}

/** Details of one candidate: thesis, red flags, news (with the research agent's steps), metrics, every agent. */
export function CandidateSheet({
  candidate,
  exchange,
  onClose,
}: {
  candidate: ScreeningCandidate | null
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
                {[c.sector, c.industry].filter(Boolean).join(" · ") || EMPTY}
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
                  Quantitative {formatScore(c.quantOverall)} (Stage 1 rank {c.quantRank ?? EMPTY})
                  {c.synthesisAdjustment != null && c.synthesisAdjustment !== 0 && (
                    <div>
                      Synthesis adjustment {c.synthesisAdjustment > 0 ? "+" : ""}
                      {c.synthesisAdjustment}
                    </div>
                  )}
                </div>
                {c.conviction && (
                  <Badge variant="outline" className={cn("border-transparent", CONVICTION_CLASS[c.conviction])}>
                    {c.conviction.toLowerCase()} conviction
                  </Badge>
                )}
              </div>

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
              <News candidate={c} />

              <Separator />
              <section className="flex flex-col gap-2">
                <h3 className="text-sm font-semibold">Key metrics</h3>
                <dl className="grid grid-cols-2 gap-x-4 gap-y-1.5 text-sm sm:grid-cols-3">
                  {METRIC_FORMAT.filter((m) => c.metrics && m.key in c.metrics).map((m) => (
                    <div key={m.key} className="flex flex-col">
                      <dt className="text-xs text-muted-foreground">{m.label}</dt>
                      <dd className="tabular-nums">{metric(c.metrics?.[m.key], m.kind)}</dd>
                    </div>
                  ))}
                </dl>
                <a
                  href={yahooQuoteUrl(exchange, c.ticker)}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-flex items-center gap-1 self-start text-xs text-primary hover:underline"
                >
                  {c.ticker} on Yahoo Finance <ExternalLinkIcon className="size-3" />
                </a>
              </section>

              <Separator />
              <section className="flex flex-col gap-3">
                <h3 className="text-sm font-semibold">Investor agents</h3>
                {c.agents.map((a) => (
                  <AgentCard key={a.agent} score={a} />
                ))}
              </section>
            </div>
          </>
        )}
      </SheetContent>
    </Sheet>
  )
}

function News({ candidate }: { candidate: ScreeningCandidate }) {
  const news = candidate.news
  const brief = news?.brief
  return (
    <section className="flex flex-col gap-2">
      <h3 className="flex items-center gap-2 text-sm font-semibold">
        <NewspaperIcon className="size-4 text-primary" />
        News brief
        {brief && (
          <Badge variant="outline" className={cn("border-transparent", SENTIMENT_CLASS[brief.sentiment])}>
            {brief.sentiment.toLowerCase()}
          </Badge>
        )}
      </h3>
      {brief ? (
        <>
          <p className="text-sm">{brief.summary || EMPTY}</p>
          {(brief.catalysts.length > 0 || brief.risks.length > 0) && (
            <div className="grid gap-2 text-xs sm:grid-cols-2">
              <ul className="flex flex-col gap-0.5">
                {brief.catalysts.map((x) => (
                  <li key={x} className="text-emerald-700 dark:text-emerald-400">+ {x}</li>
                ))}
              </ul>
              <ul className="flex flex-col gap-0.5">
                {brief.risks.map((x) => (
                  <li key={x} className="text-rose-700 dark:text-rose-400">− {x}</li>
                ))}
              </ul>
            </div>
          )}
        </>
      ) : (
        <p className="text-sm text-muted-foreground">No news brief.</p>
      )}
      {news?.note && <p className="text-xs text-muted-foreground">{news.note}</p>}
      {news && news.trace.length > 0 && (
        <details className="rounded-lg border text-xs">
          <summary className="cursor-pointer px-3 py-1.5 font-medium text-muted-foreground">
            Research agent steps (ReAct)
          </summary>
          <ol className="flex flex-col gap-1 border-t px-3 py-2">
            {news.trace.map((step) => (
              <li key={step.iteration}>
                <span className="font-medium">Turn {step.iteration}:</span> {step.thought ?? "(answer)"}
                {step.tools.length > 0 && <span className="text-muted-foreground"> → {step.tools.join(", ")}</span>}
              </li>
            ))}
          </ol>
        </details>
      )}
      {news && news.headlines.length > 0 && (
        <details className="rounded-lg border text-xs">
          <summary className="cursor-pointer px-3 py-1.5 font-medium text-muted-foreground">
            Headlines found ({news.headlines.length})
          </summary>
          <ul className="flex flex-col gap-1.5 border-t px-3 py-2">
            {news.headlines.map((h) => (
              <li key={h.url}>
                <a href={h.url} target="_blank" rel="noopener noreferrer" className="inline-flex gap-1 hover:underline">
                  {h.title}
                  <ExternalLinkIcon className="mt-px size-3 shrink-0" />
                </a>
                <div className="text-muted-foreground">
                  {h.source}
                  {h.publishedAt && ` · ${formatDate(h.publishedAt)}`}
                </div>
              </li>
            ))}
          </ul>
        </details>
      )}
    </section>
  )
}

function AgentCard({ score }: { score: AgentScore }) {
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
