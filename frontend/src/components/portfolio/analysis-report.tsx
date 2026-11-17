"use client"

import { Fragment, useEffect, useMemo, useState } from "react"
import Link from "next/link"
import {
  ArrowLeftIcon,
  CheckCircle2Icon,
  CircleAlertIcon,
  CoinsIcon,
  FilterIcon,
  LoaderIcon,
  PieChartIcon,
  SparklesIcon,
  WalletIcon,
  XCircleIcon,
} from "lucide-react"

import { JobStatusBadge } from "@/components/ingestion/job-status-badge"
import { UsageTable } from "@/components/screening/usage-table"
import { StatTile } from "@/components/stat-tile"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { requestJson } from "@/lib/client-api"
import { EMPTY, formatNumber, formatPercent, formatTimestamp } from "@/lib/format"
import { creatorLabel, formatDuration, isActive } from "@/lib/ingestion"
import {
  ACTION_LABEL,
  ACTION_ORDER,
  analysisRunTitle,
  hasTechnicalSteps,
  PORTFOLIO_ANALYSIS_HREF,
  STAGE_LABEL,
  type AnalysisAction,
  type AnalysisCandidate,
  type AnalysisReport,
} from "@/lib/portfolio-analysis"
import { companyHref } from "@/lib/links"
import { formatUsd } from "@/lib/screening"
import { cn } from "@/lib/utils"

const POLL_MS = 3000

const ACTION_STYLE: Record<AnalysisAction, string> = {
  BUY: "border-emerald-600/40 bg-emerald-600/10 text-emerald-800 dark:text-emerald-300",
  INCREASE: "border-emerald-600/30 text-emerald-700 dark:text-emerald-400",
  HOLD: "text-muted-foreground",
  DECREASE: "border-amber-600/40 text-amber-800 dark:text-amber-300",
  SELL: "border-rose-600/40 bg-rose-600/10 text-rose-800 dark:text-rose-300",
}

function rp(v: number | null | undefined): string {
  return v === null || v === undefined || !Number.isFinite(v) ? EMPTY : formatNumber(v, 0)
}

function signed(v: number | null | undefined, digits = 0): string {
  if (v === null || v === undefined || !Number.isFinite(v)) return EMPTY
  return `${v > 0 ? "+" : ""}${formatNumber(v, digits)}`
}

function pct(v: number | null | undefined, digits = 1): string {
  return formatPercent(v ?? null, digits)
}

function toneOf(v: number | null | undefined): string {
  if (v === null || v === undefined || v === 0) return "text-muted-foreground"
  return v > 0 ? "text-emerald-700 dark:text-emerald-400" : "text-rose-700 dark:text-rose-400"
}

/** A Portfolio › Analysis report; polls every 3 s while the run is queued or running. */
export function AnalysisReportView({ initial }: { initial: AnalysisReport }) {
  const [report, setReport] = useState(initial)
  const [error, setError] = useState<string | null>(null)
  const [now, setNow] = useState<number | null>(null)
  const [showLeftOut, setShowLeftOut] = useState(false)
  const run = report.run
  const active = isActive(run.status)

  useEffect(() => {
    if (!active) return
    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | undefined
    async function load() {
      try {
        const { body } = await requestJson<AnalysisReport>(`/api/portfolio/analyses/${encodeURIComponent(run.id)}`)
        if (cancelled) return
        setReport(body)
        setError(null)
        if (isActive(body.run.status)) timer = setTimeout(load, POLL_MS)
      } catch (e) {
        if (cancelled) return
        setError(e instanceof Error ? e.message : String(e))
        timer = setTimeout(load, POLL_MS * 3)
      }
    }
    timer = setTimeout(load, POLL_MS)
    return () => {
      cancelled = true
      if (timer) clearTimeout(timer)
    }
  }, [active, run.id])

  useEffect(() => {
    if (!active) return
    // the current time only on the client (it differs between server and browser)
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [active])

  const started = run.startedAt ? new Date(run.startedAt).getTime() : null
  const elapsed =
    started === null ? null : run.finishedAt ? new Date(run.finishedAt).getTime() - started : now === null ? null : now - started

  const recommended = useMemo(
    () =>
      report.candidates
        .filter((c) => c.action !== null)
        .sort(
          (a, b) =>
            ACTION_ORDER.indexOf(a.action!) - ACTION_ORDER.indexOf(b.action!) ||
            (b.recommendation?.targetValue ?? 0) - (a.recommendation?.targetValue ?? 0) ||
            a.ticker.localeCompare(b.ticker)
        ),
    [report.candidates]
  )
  const analysed = report.candidates.filter(
    (c) => c.action === null && ["NOT_CHOSEN", "FUNDAMENTAL_AI_OUT", "TECHNICAL_AI_OUT", "RECOMMENDED", "HELD"].includes(c.stage)
  )
  const leftOut = report.candidates.filter((c) => c.stage === "FUNDAMENTAL_OUT" || c.stage === "TECHNICAL_OUT")
  const a = report.allocation
  const done = !active && a !== null
  const technical = hasTechnicalSteps(run)

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-1">
        <Link href={PORTFOLIO_ANALYSIS_HREF} className="inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground">
          <ArrowLeftIcon className="size-3.5" /> All portfolio analyses
        </Link>
        <h1 className="text-xl font-semibold tracking-tight">Portfolio analysis · {analysisRunTitle(run)}</h1>
        <p className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
          <JobStatusBadge status={run.status} />
          <span>
            requested {formatTimestamp(run.requestedAt)} by {creatorLabel(run.createdBy)}
            {elapsed !== null && ` · ${formatDuration(elapsed)}`}
          </span>
        </p>
        {run.stage && (
          <p className="flex items-center gap-2 text-sm">
            {active && <LoaderIcon className="size-3.5 animate-spin" aria-hidden />}
            {run.stage}
          </p>
        )}
        {run.message && !active && <p className="text-xs text-muted-foreground">{run.message}</p>}
        {error && <p role="alert" className="text-xs text-destructive">{error}</p>}
      </div>

      {/* Metrics */}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-5">
        <StatTile
          label="Capital"
          value={report.capital ? `Rp ${rp(report.capital.total)}` : EMPTY}
          hint={report.capital ? `stocks Rp ${rp(report.capital.marketValue)} · cash Rp ${rp(report.capital.cash)}${report.capital.addedCapital ? ` · added Rp ${rp(report.capital.addedCapital)}` : ""}` : undefined}
          icon={WalletIcon}
        />
        <StatTile
          label="Stocks after"
          value={a ? `${a.stocksAfter} of at most ${a.limits.maxStocks}` : EMPTY}
          hint={a ? `${a.stocksBefore} held before` : undefined}
          icon={PieChartIcon}
          tone="muted"
        />
        <StatTile
          label="Cash after"
          value={a ? `Rp ${rp(a.cashAfter)}` : EMPTY}
          hint={a ? `${pct(a.cashWeightBefore)} → ${pct(a.cashWeightAfter)} of the capital` : undefined}
          icon={WalletIcon}
          tone="muted"
        />
        <StatTile
          label="Buy / sell"
          value={a ? `Rp ${rp(a.buyValue)} / ${rp(a.sellValue)}` : EMPTY}
          hint="at the last closes, in whole lots"
          icon={SparklesIcon}
          tone="muted"
        />
        <StatTile
          label="Model cost"
          value={formatUsd(run.costUsd)}
          hint={`${run.modelCalls} calls · cap ${formatUsd(run.budgetUsd)}`}
          icon={CoinsIcon}
          tone="muted"
        />
      </div>

      {report.synthesis?.executiveSummary && (
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <SparklesIcon className="size-4 text-primary" />
              Summary
            </CardTitle>
            <CardDescription>
              {report.synthesis.model ? `Written by ${report.synthesis.model}` : "Written by fixed rules (no synthesis model)"}
              {report.synthesis.fallback && ` · ${report.synthesis.fallback}`}
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-3 text-sm">
            <p className="leading-relaxed">{report.synthesis.executiveSummary}</p>
            {report.synthesis.riskNotes.length > 0 && (
              <ul className="list-disc space-y-1 pl-5 text-muted-foreground">
                {report.synthesis.riskNotes.map((n) => (
                  <li key={n}>{n}</li>
                ))}
              </ul>
            )}
          </CardContent>
        </Card>
      )}

      {done && <RecommendationsCard rows={recommended} technical={technical} />}

      {done && a && (
        <div className="grid items-start gap-4 xl:grid-cols-[minmax(0,1fr)_380px]">
          <SectorsCard before={a.sectorsBefore} after={a.sectorsAfter} limit={a.limits.maxSectorWeight}
            cashBefore={a.cashWeightBefore} cashAfter={a.cashWeightAfter} />
          <ChecksCard report={report} />
        </div>
      )}

      {report.funnel && <MethodCard report={report} />}

      {analysed.length > 0 && <AnalysedCard rows={analysed} technical={technical} />}

      {leftOut.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <FilterIcon className="size-4 text-muted-foreground" />
              Left out by the rules steps ({leftOut.length})
            </CardTitle>
            <CardDescription>
              {technical
                ? <>Step 1 left them out by Fundamental (Score) or step 2 by Technical (Score); a bulk run lists step
                    2&apos;s and step 1&apos;s nearest misses.</>
                : <>Step 1 left them out by Fundamental (Score); a bulk run lists its nearest misses.</>}
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-2 px-0">
            <div className="px-4">
              <Button size="sm" variant="outline" onClick={() => setShowLeftOut((v) => !v)}>
                {showLeftOut ? "Hide" : "Show"} the {leftOut.length} stocks
              </Button>
            </div>
            {showLeftOut && <LeftOutTable rows={leftOut} />}
          </CardContent>
        </Card>
      )}

      {report.notes && <NotesCard report={report} />}

      {report.usage.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle>Model usage</CardTitle>
            <CardDescription>Every model call of the run, per stage and model</CardDescription>
          </CardHeader>
          <CardContent className="px-0">
            <UsageTable usage={report.usage} />
          </CardContent>
        </Card>
      )}

      <p className="text-xs text-muted-foreground">{report.disclaimer}</p>
    </div>
  )
}

// ------------------------------------------------------------------ recommendations

function RecommendationsCard({ rows, technical }: { rows: AnalysisCandidate[]; technical: boolean }) {
  const [open, setOpen] = useState<string | null>(null)
  return (
    <Card>
      <CardHeader>
        <CardTitle>Recommendations</CardTitle>
        <CardDescription>
          Sell, decrease, hold, increase or buy, with the target weight of the capital and the trade at the last close
          (whole lots of 100 shares; an odd lot held stays as it is). Click a row for the agents&apos; reasoning.
        </CardDescription>
      </CardHeader>
      <CardContent className="px-0">
        {rows.length === 0 ? (
          <p className="px-4 text-sm text-muted-foreground">No position: the whole capital stays in cash.</p>
        ) : (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="pl-4">Action</TableHead>
                  <TableHead>Stock</TableHead>
                  <TableHead className="text-right">Now</TableHead>
                  <TableHead className="text-right">Target</TableHead>
                  <TableHead className="text-right">Trade</TableHead>
                  <TableHead className="text-right">Price</TableHead>
                  <TableHead>Fundamental</TableHead>
                  {technical && <TableHead>Technical</TableHead>}
                  <TableHead className="text-right">Half-Kelly</TableHead>
                  <TableHead className="pr-4">Why</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((c) => {
                  const r = c.recommendation ?? {}
                  const plan = c.technicalAi?.plan
                  const expanded = open === c.ticker
                  return (
                    <Fragment key={c.ticker}>
                      <TableRow className="cursor-pointer" onClick={() => setOpen(expanded ? null : c.ticker)} aria-expanded={expanded}>
                        <TableCell className="pl-4 align-top">
                          <Badge variant="outline" className={ACTION_STYLE[c.action!]}>{ACTION_LABEL[c.action!]}</Badge>
                          {c.conviction && <div className="mt-1 text-[11px] text-muted-foreground">{c.conviction.toLowerCase()} conviction</div>}
                        </TableCell>
                        <TableCell className="align-top">
                          <Link href={companyHref("IDX", c.ticker)} className="font-medium hover:underline" onClick={(e) => e.stopPropagation()}>
                            {c.ticker}
                          </Link>
                          <div className="max-w-48 truncate text-xs text-muted-foreground">{c.companyName}</div>
                          <div className="max-w-48 truncate text-xs text-muted-foreground">{c.sector ?? "Unclassified"}</div>
                        </TableCell>
                        <TableCell className="text-right align-top font-mono tabular-nums">
                          {c.held ? (
                            <>
                              <div>{pct(r.currentWeight)}</div>
                              <div className="text-xs text-muted-foreground">{rp(r.currentShares)} sh</div>
                            </>
                          ) : (
                            <span className="text-muted-foreground">{EMPTY}</span>
                          )}
                        </TableCell>
                        <TableCell className="text-right align-top font-mono tabular-nums">
                          <div className="font-semibold">{pct(r.targetWeight)}</div>
                          <div className="text-xs text-muted-foreground">Rp {rp(r.targetValue)}</div>
                        </TableCell>
                        <TableCell className={cn("text-right align-top font-mono tabular-nums", toneOf(r.tradeShares))}>
                          <div>{signed(r.tradeShares)} sh</div>
                          <div className="text-xs">{r.tradeLots ? `${signed(r.tradeLots, 2)} lots` : EMPTY}</div>
                          <div className="text-xs">Rp {signed(r.tradeValue)}</div>
                        </TableCell>
                        <TableCell className="text-right align-top font-mono tabular-nums">{rp(r.price ?? c.close)}</TableCell>
                        <TableCell className="align-top text-xs">
                          <div className={c.fundamentalAi?.verdict === "PASS" ? "text-emerald-700 dark:text-emerald-400" : "text-rose-700 dark:text-rose-400"}>
                            {c.fundamentalAi?.verdict ?? EMPTY} · {formatNumber(c.fundamentalAi?.finalScore ?? c.fundamentalScore, 1)}
                          </div>
                          <div className="text-muted-foreground">rules {formatNumber(c.fundamentalScore, 1)}{c.fundamentalRank ? ` · #${c.fundamentalRank}` : ""}</div>
                        </TableCell>
                        {technical && (
                          <TableCell className="align-top text-xs">
                            <div>{c.technicalAi?.stance ?? EMPTY} · {formatNumber(c.technicalAi?.finalScore ?? c.technicalScore, 1)}</div>
                            {plan?.buy && plan.stopLoss && plan.sell && (
                              <div className="text-muted-foreground tabular-nums">
                                {rp(plan.buy.price)} / stop {rp(plan.stopLoss.price)} / {rp(plan.sell.price)}
                              </div>
                            )}
                          </TableCell>
                        )}
                        <TableCell className="text-right align-top font-mono tabular-nums">{pct(c.kelly?.weight)}</TableCell>
                        <TableCell className="w-72 min-w-56 max-w-80 pr-4 align-top text-xs whitespace-normal">
                          <div className="line-clamp-3 break-words">{r.reason ?? c.thesis ?? EMPTY}</div>
                          {(r.adjustments?.length ?? 0) > 0 && (
                            <div className="mt-1 text-amber-800 dark:text-amber-300">{r.adjustments!.join("; ")}</div>
                          )}
                        </TableCell>
                      </TableRow>
                      {expanded && (
                        <TableRow className="bg-muted/30 hover:bg-muted/30">
                          {/* table cells do not wrap (whitespace-nowrap): the detail must, or its lines run into each other */}
                          <TableCell colSpan={technical ? 10 : 9} className="p-0 whitespace-normal">
                            <div className="sticky left-0 w-full max-w-[calc(100vw-2.5rem)] px-4 py-3 md:max-w-[calc(100vw-20rem)]">
                              <CandidateDetail c={c} technical={technical} />
                            </div>
                          </TableCell>
                        </TableRow>
                      )}
                    </Fragment>
                  )
                })}
              </TableBody>
            </Table>
          </div>
        )}
      </CardContent>
    </Card>
  )
}

function CandidateDetail({ c, technical }: { c: AnalysisCandidate; technical: boolean }) {
  const f = c.fundamentalAi
  const t = c.technicalAi
  return (
    <div className={cn("grid grid-cols-1 gap-x-6 gap-y-4 text-xs leading-relaxed", technical ? "lg:grid-cols-3" : "lg:grid-cols-2")}>
      <div className="min-w-0 space-y-1.5 break-words">
        <div className="font-medium">Fundamental agent · {f?.status?.toLowerCase().replace("_", " ") ?? "not asked"}</div>
        {f?.answer?.thesis && <p>{f.answer.thesis}</p>}
        {(f?.answer?.strengths?.length ?? 0) > 0 && <p className="text-emerald-700 dark:text-emerald-400">+ {f!.answer!.strengths.join(" · ")}</p>}
        {(f?.answer?.risks?.length ?? 0) > 0 && <p className="text-rose-700 dark:text-rose-400">− {f!.answer!.risks.join(" · ")}</p>}
        {(f?.issues?.length ?? 0) > 0 && (
          <p className="text-amber-800 dark:text-amber-300">Reflection: {f!.issues!.map((i) => i.message).join(" ")}</p>
        )}
        {f?.answer?.note && <p className="text-muted-foreground">Critic: {f.answer.note}</p>}
      </div>
      {technical && <div className="min-w-0 space-y-1.5 break-words">
        <div className="font-medium">Technical agent · {t?.status?.toLowerCase().replace("_", " ") ?? "not asked"}</div>
        {t?.answer?.thesis && <p>{t.answer.thesis}</p>}
        {t?.note && <p className="text-muted-foreground">{t.note}</p>}
        {t?.plan?.buy && (
          <p className="tabular-nums">
            Plan ({t.planSource === "AI" ? "the agent's" : "the best preset's rules"}): buy {rp(t.plan.buy.price)}{" "}
            {t.plan.buy.type.toLowerCase()}, stop {rp(t.plan.stopLoss?.price)}, target {rp(t.plan.sell?.price)}
            {t.plan.rewardRisk !== null && ` · reward / risk ${formatNumber(t.plan.rewardRisk, 2)}`}
          </p>
        )}
        {(t?.answer?.risks?.length ?? 0) > 0 && <p className="text-rose-700 dark:text-rose-400">− {t!.answer!.risks.join(" · ")}</p>}
        {(t?.issues?.length ?? 0) > 0 && (
          <p className="text-amber-800 dark:text-amber-300">Reflection: {t!.issues!.map((i) => i.message).join(" ")}</p>
        )}
      </div>}
      <div className="min-w-0 space-y-1.5 break-words">
        <div className="font-medium">Sizing</div>
        {c.kelly ? <p>{c.kelly.note}</p> : <p>{EMPTY}</p>}
        {c.position && (
          <p className="tabular-nums">
            Held: {rp(c.position.shares)} shares at Rp {rp(c.position.averagePrice)} average · G/L{" "}
            <span className={toneOf(c.position.gainLossPct)}>{pct(c.position.gainLossPct)}</span>
          </p>
        )}
        {c.redFlags.length > 0 && <p className="text-amber-800 dark:text-amber-300">{c.redFlags.join(" · ")}</p>}
      </div>
    </div>
  )
}

// ------------------------------------------------------------------ allocation

function SectorsCard({
  before,
  after,
  limit,
  cashBefore,
  cashAfter,
}: {
  before: Record<string, number>
  after: Record<string, number>
  limit: number
  cashBefore: number
  cashAfter: number
}) {
  const sectors = Array.from(new Set([...Object.keys(before), ...Object.keys(after)])).sort(
    (x, y) => (after[y] ?? 0) - (after[x] ?? 0) || x.localeCompare(y)
  )
  const rows = [...sectors.map((s) => ({ name: s, before: before[s] ?? 0, after: after[s] ?? 0, cash: false })),
    { name: "Cash", before: cashBefore, after: cashAfter, cash: true }]
  return (
    <Card>
      <CardHeader>
        <CardTitle>Sectors and cash, before → after</CardTitle>
        <CardDescription>Share of the capital; no sector above {pct(limit, 0)} (the line)</CardDescription>
      </CardHeader>
      <CardContent className="space-y-2">
        {rows.map((r) => (
          <div key={r.name} className="grid grid-cols-[minmax(0,10rem)_minmax(0,1fr)_7rem] items-center gap-3 text-sm">
            <span className="truncate" title={r.name}>{r.name}</span>
            <div className="relative space-y-1">
              <div className="h-1.5 rounded-full bg-muted" aria-hidden>
                <div className="h-1.5 rounded-full bg-muted-foreground/50" style={{ width: `${Math.min(100, r.before * 100)}%` }} />
              </div>
              <div className="h-1.5 rounded-full bg-muted" aria-hidden>
                <div className="h-1.5 rounded-full bg-primary" style={{ width: `${Math.min(100, r.after * 100)}%` }} />
              </div>
              {!r.cash && (
                <div className="absolute inset-y-0 w-px bg-rose-600/60" style={{ left: `${limit * 100}%` }} aria-hidden />
              )}
            </div>
            <span className="text-right font-mono text-xs tabular-nums">
              {pct(r.before)} → <span className="font-semibold">{pct(r.after)}</span>
            </span>
          </div>
        ))}
        <p className="pt-1 text-xs text-muted-foreground">Upper bar: before · lower bar: after</p>
      </CardContent>
    </Card>
  )
}

function ChecksCard({ report }: { report: AnalysisReport }) {
  const checks = report.checks ?? []
  return (
    <Card>
      <CardHeader>
        <CardTitle>Limits checked</CardTitle>
        <CardDescription>Enforced on the result whatever the synthesis proposed</CardDescription>
      </CardHeader>
      <CardContent className="space-y-2 text-sm">
        {checks.map((c) => (
          <div key={c.name} className="flex items-start gap-2">
            {c.ok ? (
              <CheckCircle2Icon className="mt-0.5 size-4 shrink-0 text-emerald-600" aria-label="passed" />
            ) : (
              <XCircleIcon className="mt-0.5 size-4 shrink-0 text-rose-600" aria-label="failed" />
            )}
            <div>
              <div className="font-medium">{c.name}</div>
              <div className="text-xs text-muted-foreground">{c.detail}</div>
            </div>
          </div>
        ))}
      </CardContent>
    </Card>
  )
}

// ------------------------------------------------------------------ method

function MethodCard({ report }: { report: AnalysisReport }) {
  const f = report.funnel!
  const ai = report.candidates.filter((c) => c.fundamentalAi && c.fundamentalAi.status !== "NOT_ASKED")
  const passed = ai.filter((c) => c.fundamentalAi?.verdict === "PASS").length
  const timed = report.candidates.filter((c) => c.technicalAi && c.technicalAi.status !== "NOT_ASKED").length
  const technical = hasTechnicalSteps(report.run)
  const all = [
    { key: "f", title: "Fundamental (rules)", text: f.fundamental.rule, out: `${f.fundamental.passed} of ${f.fundamental.scored} scored stocks pass (${f.fundamental.aboveMin} at or above ${formatNumber(f.fundamental.minScore, 0)})`, technical: false },
    { key: "t", title: "Technical (rules)", text: f.technical.rule, out: `${f.technical.passed} of step 1's ${f.technical.inputs} pass (${f.technical.scored} with a Technical (Score))`, technical: true },
    { key: "fa", title: "Fundamental agent", text: "Judges each business: PASS or FAIL (the holdings too); Reflection reviews flagged answers", out: `${passed} of ${ai.length} passed`, technical: false },
    { key: "ta", title: "Technical agent", text: "Judges the timing of the stocks the fundamental agent passed, and of the holdings", out: `${timed} stocks read`, technical: true },
    { key: "s", title: "Synthesis and limits", text: technical ? "Target weights within the limits, half-Kelly as the guide, whole lots" : "Target weights within the limits, half-Kelly (even payoff) as the guide, whole lots; a fundamental PASS buys", out: report.allocation ? `${report.allocation.stocksAfter} stocks, cash ${pct(report.allocation.cashWeightAfter)}` : EMPTY, technical: false },
  ]
  const steps = all.filter((s) => technical || !s.technical).map((s, i) => ({ ...s, n: i + 1 }))
  return (
    <Card>
      <CardHeader>
        <CardTitle>How the stocks were chosen</CardTitle>
        <CardDescription>
          {f.mode === "BULK" ? `All ${f.universe} companies` : `${f.universe} selected stocks`}, {f.held} held ·{" "}
          {technical ? "fundamental first, technical after, never combined" : "fundamental analysis only (no technical step)"}
        </CardDescription>
      </CardHeader>
      <CardContent>
        <ol className={cn("grid gap-3", technical ? "md:grid-cols-5" : "md:grid-cols-3")}>
          {steps.map((s) => (
            <li key={s.key} className="rounded-lg border p-3 text-xs">
              <div className="mb-1 text-sm font-medium">
                {s.n}. {s.title}
              </div>
              <p className="text-muted-foreground">{s.text}</p>
              <p className="mt-2 font-medium">{s.out}</p>
            </li>
          ))}
        </ol>
      </CardContent>
    </Card>
  )
}

function AnalysedCard({ rows, technical }: { rows: AnalysisCandidate[]; technical: boolean }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Analysed, not in the portfolio ({rows.length})</CardTitle>
        <CardDescription>
          {technical
            ? "Passed both rules steps; rejected by an agent, not timely, or not chosen by the limits"
            : "Passed the fundamental step; rejected by the fundamental agent, or not chosen by the limits"}
        </CardDescription>
      </CardHeader>
      <CardContent className="px-0">
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="pl-4">Stock</TableHead>
                <TableHead>Where it stopped</TableHead>
                <TableHead className="text-right">Fundamental</TableHead>
                {technical && <TableHead className="text-right">Technical</TableHead>}
                <TableHead className="text-right">Half-Kelly</TableHead>
                <TableHead className="pr-4">Why</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((c) => (
                <TableRow key={c.ticker}>
                  <TableCell className="pl-4">
                    <Link href={companyHref("IDX", c.ticker)} className="font-medium hover:underline">{c.ticker}</Link>
                    <div className="max-w-48 truncate text-xs text-muted-foreground">{c.sector ?? "Unclassified"}</div>
                  </TableCell>
                  <TableCell className="text-xs">{STAGE_LABEL[c.stage]}</TableCell>
                  <TableCell className="text-right text-xs tabular-nums">
                    {c.fundamentalAi?.verdict ?? EMPTY} · {formatNumber(c.fundamentalAi?.finalScore ?? c.fundamentalScore, 1)}
                  </TableCell>
                  {technical && (
                    <TableCell className="text-right text-xs tabular-nums">
                      {c.technicalAi?.stance ?? EMPTY} · {formatNumber(c.technicalAi?.finalScore ?? c.technicalScore, 1)}
                    </TableCell>
                  )}
                  <TableCell className="text-right text-xs tabular-nums">{pct(c.kelly?.weight)}</TableCell>
                  <TableCell className="w-80 min-w-64 max-w-96 pr-4 text-xs whitespace-normal">
                    <div className="line-clamp-2 break-words">
                      {[...(c.recommendation?.adjustments ?? []), ...(c.thesis ? [c.thesis] : []), ...c.redFlags].join(" · ") || EMPTY}
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      </CardContent>
    </Card>
  )
}

function LeftOutTable({ rows }: { rows: AnalysisCandidate[] }) {
  return (
    <div className="overflow-x-auto">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className="pl-4">Stock</TableHead>
            <TableHead>Step</TableHead>
            <TableHead className="text-right">Fundamental (Score)</TableHead>
            <TableHead className="text-right">Technical (Score)</TableHead>
            <TableHead className="pr-4">Why</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((c) => (
            <TableRow key={c.ticker}>
              <TableCell className="pl-4 font-medium">{c.ticker}</TableCell>
              <TableCell className="text-xs">{c.stage === "FUNDAMENTAL_OUT" ? "1 · Fundamental" : "2 · Technical"}</TableCell>
              <TableCell className="text-right text-xs tabular-nums">
                {formatNumber(c.fundamentalScore, 1)}
                {c.fundamentalRank ? ` · #${c.fundamentalRank}` : ""}
              </TableCell>
              <TableCell className="text-right text-xs tabular-nums">
                {formatNumber(c.technicalScore, 1)}
                {c.technicalRank ? ` · #${c.technicalRank}` : ""}
              </TableCell>
              <TableCell className="min-w-64 max-w-[32rem] pr-4 text-xs break-words whitespace-normal text-muted-foreground">
                {c.redFlags.join(" · ") || EMPTY}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}

function NotesCard({ report }: { report: AnalysisReport }) {
  const n = report.notes!
  const messages = n.messages ?? []
  const learned = n.lessonsLearned ?? []
  const applied = Object.entries(n.lessonsApplied ?? {}).filter(([, l]) => l.length > 0)
  if (messages.length === 0 && learned.length === 0 && applied.length === 0) return null
  return (
    <Card>
      <CardHeader>
        <CardTitle>Notes</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3 text-sm">
        {messages.length > 0 && (
          <ul className="space-y-1">
            {messages.map((m) => (
              <li key={m} className="flex items-start gap-2 text-xs">
                <CircleAlertIcon className="mt-px size-3.5 shrink-0 text-amber-600" />
                {m}
              </li>
            ))}
          </ul>
        )}
        {applied.length > 0 && (
          <div className="text-xs">
            <div className="font-medium">Lessons from earlier runs (Reflexion)</div>
            {applied.map(([agent, lessons]) => (
              <p key={agent} className="text-muted-foreground">
                {agent.toLowerCase()}: {lessons.join(" ")}
              </p>
            ))}
          </div>
        )}
        {learned.length > 0 && (
          <div className="text-xs">
            <div className="font-medium">Learned in this run</div>
            {learned.map((l) => (
              <p key={l.agent + l.lesson} className="text-muted-foreground">
                {l.agent.toLowerCase()} ({l.occurrencesInRun}x): {l.lesson}
              </p>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  )
}
