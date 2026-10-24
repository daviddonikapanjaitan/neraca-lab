"use client"

import { useEffect, useState } from "react"
import Link from "next/link"
import {
  ArrowLeftIcon,
  BookOpenTextIcon,
  BrainCircuitIcon,
  CircleAlertIcon,
  CoinsIcon,
  CpuIcon,
  DatabaseIcon,
  ExternalLinkIcon,
  GaugeIcon,
  LoaderIcon,
  SparklesIcon,
  TableIcon,
  TimerIcon,
  UsersIcon,
} from "lucide-react"

import { JobStatusBadge } from "@/components/ingestion/job-status-badge"
import { AgentCard } from "@/components/screening/agent-card"
import { DownloadPdfButton } from "@/components/screening/download-pdf-button"
import { UsageTable } from "@/components/screening/usage-table"
import { StatTile } from "@/components/stat-tile"
import { Badge } from "@/components/ui/badge"
import { buttonVariants } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { analysisTitle, figure, figureRows, RESEARCH_SENTIMENT_CLASS } from "@/lib/analysis"
import { monthLabel, rangeLabel } from "@/lib/registration"
import { EMPTY, formatDate, formatNumber, formatPercent, formatTimestamp } from "@/lib/format"
import { requestJson } from "@/lib/client-api"
import { creatorLabel, formatDuration, isActive } from "@/lib/ingestion"
import {
  AGENT_SHORT,
  CONVICTION_CLASS,
  formatScore,
  formatTokens,
  formatUsd,
  scoreClass,
  verdictLabel,
} from "@/lib/screening"
import type { AnalysisReport as Report } from "@/lib/types"
import { cn } from "@/lib/utils"

const POLL_MS = 3000

/** One analysis report; polls every 3 s while the analysis is queued or running. */
export function AnalysisReport({ initial }: { initial: Report }) {
  const [report, setReport] = useState(initial)
  const [error, setError] = useState<string | null>(null)
  const [now, setNow] = useState<number | null>(null)
  const run = report.run
  const active = isActive(run.status)

  useEffect(() => {
    if (!active) return
    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | undefined
    async function load() {
      try {
        const { body } = await requestJson<Report>(`/api/analyses/${encodeURIComponent(run.id)}`)
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
  const syn = report.synthesis
  const messages = report.notes?.messages ?? []
  const learned = report.notes?.lessonsLearned ?? []
  const applied = report.notes?.lessonsApplied ?? {}

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex min-w-0 flex-col gap-1">
          <Link
            href="/screening/analysis"
            className="inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
          >
            <ArrowLeftIcon className="size-3.5" /> All analyses
          </Link>
          <h1 className="text-xl font-semibold tracking-tight">{analysisTitle(run)}</h1>
          <p className="text-sm text-muted-foreground">
            {run.agents.map((a) => AGENT_SHORT[a]).join(", ")} · requested {formatTimestamp(run.requestedAt)} by{" "}
            {creatorLabel(run.createdBy)}
            {run.marketDataDate && ` · market data of ${formatDate(run.marketDataDate)}`}
          </p>
        </div>
        <DownloadPdfButton
          href={`/api/analyses/${encodeURIComponent(run.id)}/pdf`}
          fileName={`analysis-${run.ticker}-${run.id}.pdf`}
          disabled={active}
        />
      </div>

      {error && (
        <p role="alert" className="flex items-center gap-2 text-xs text-destructive">
          <CircleAlertIcon className="size-3.5" /> {error}
        </p>
      )}

      <Card size="sm">
        <CardContent className="flex flex-wrap items-center gap-3">
          <JobStatusBadge status={run.status} />
          <span className="flex items-center gap-2 text-sm">
            {active && <LoaderIcon className="size-4 animate-spin text-primary" />}
            {run.stage ?? EMPTY}
          </span>
          {run.message && <span className="text-xs text-amber-600 dark:text-amber-400">{run.message}</span>}
        </CardContent>
      </Card>

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatTile
          label="Overall score"
          icon={GaugeIcon}
          tone={run.overallScore == null ? "muted" : run.overallScore >= 65 ? "positive" : run.overallScore >= 45 ? "primary" : "negative"}
          value={<span className={scoreClass(run.overallScore)}>{formatScore(run.overallScore)}</span>}
          hint={
            run.verdict
              ? `${verdictLabel(run.verdict)}${report.quantOverall != null ? ` · quantitative ${formatScore(report.quantOverall)}` : ""}`
              : active
                ? "after the agents and the synthesis"
                : "no score"
          }
        />
        <StatTile
          label="AI cost"
          icon={CoinsIcon}
          tone={run.costUsd > run.budgetUsd ? "negative" : "positive"}
          value={formatUsd(run.costUsd)}
          hint={`budget ${formatUsd(run.budgetUsd)}`}
        />
        <StatTile
          label="Tokens"
          icon={CpuIcon}
          value={formatTokens(run.promptTokens + run.completionTokens)}
          hint={`${formatTokens(run.promptTokens)} in · ${formatTokens(run.completionTokens)} out · ${run.modelCalls} calls`}
        />
        <StatTile label="Duration" icon={TimerIcon} tone="muted" value={formatDuration(elapsed)} hint={active ? "running" : "total"} />
      </div>

      {syn?.executiveSummary && (
        <Card>
          <CardHeader>
            <CardTitle className="flex flex-wrap items-center gap-2">
              <SparklesIcon className="size-4 text-primary" /> Executive summary
              {syn.conviction && (
                <Badge variant="outline" className={cn("border-transparent", CONVICTION_CLASS[syn.conviction])}>
                  {syn.conviction.toLowerCase()} conviction
                </Badge>
              )}
            </CardTitle>
            <CardDescription>
              {syn.model
                ? `Synthesis by ${syn.model}`
                : `Written without the synthesis model: ${syn.fallback ?? "unknown reason"}`}
              {report.synthesisAdjustment != null &&
                ` · score ${report.synthesisAdjustment > 0 ? "+" : ""}${report.synthesisAdjustment}${
                  syn.adjustmentReason ? ` (${syn.adjustmentReason})` : ""
                }`}
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-3 text-sm">
            {syn.thesis && <p className="font-medium">{syn.thesis}</p>}
            <p className="whitespace-pre-line">{syn.executiveSummary}</p>
            <div className="grid gap-3 sm:grid-cols-2">
              <Points title="Bull case" items={syn.bullCase} tone="positive" />
              <Points title="Bear case" items={syn.bearCase} tone="negative" />
              <Points title="Key risks" items={syn.keyRisks} />
              <Points title="Monitor" items={syn.monitor} />
            </div>
            {syn.dataGaps.length > 0 && (
              <p className="text-xs text-muted-foreground">Data gaps: {syn.dataGaps.join("; ")}</p>
            )}
          </CardContent>
        </Card>
      )}

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <UsersIcon className="size-4 text-primary" /> Investor agents
          </CardTitle>
          <CardDescription>
            Agent score = 60% quantitative scorecard + 40% AI judgement (after reflection), the AI judgement alone without
            market data; overall = average of the investor agents, blended 20% with the Risk agent (safety).
          </CardDescription>
        </CardHeader>
        <CardContent>
          {report.agents.length > 0 ? (
            <div className="grid gap-3 lg:grid-cols-2">
              {report.agents.map((a) => (
                <AgentCard key={a.agent} score={a} />
              ))}
            </div>
          ) : (
            <p className="text-sm text-muted-foreground">
              {active ? "The agents' views appear when they have finished." : "No agent produced a view."}
            </p>
          )}
        </CardContent>
      </Card>

      <Research report={report} active={active} />

      <InsiderOwnership report={report} active={active} />

      <div className="grid items-start gap-4 lg:grid-cols-2">
        <Figures report={report} active={active} />
        <DataUsed report={report} />
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <BrainCircuitIcon className="size-4 text-primary" /> Notes, reflection and Reflexion memory
          </CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-3 text-sm">
          {messages.length === 0 && learned.length === 0 && Object.keys(applied).length === 0 && (
            <p className="text-muted-foreground">{active ? "Notes appear when the analysis has finished." : "Nothing to note."}</p>
          )}
          {messages.length > 0 && (
            <ul className="list-disc space-y-1 pl-4">
              {messages.map((m) => (
                <li key={m}>{m}</li>
              ))}
            </ul>
          )}
          {Object.keys(applied).length > 0 && (
            <div>
              <p className="text-xs font-medium text-muted-foreground">Lessons from earlier runs used in the prompts</p>
              <ul className="list-disc space-y-0.5 pl-4 text-xs">
                {Object.entries(applied).flatMap(([agent, lessons]) =>
                  lessons.map((l) => (
                    <li key={agent + l}>
                      <span className="font-medium">{agent}:</span> {l}
                    </li>
                  ))
                )}
              </ul>
            </div>
          )}
          {learned.length > 0 && (
            <div>
              <p className="text-xs font-medium text-muted-foreground">Lessons learned in this analysis</p>
              <ul className="list-disc space-y-0.5 pl-4 text-xs">
                {learned.map((l) => (
                  <li key={l.agent + l.lesson}>
                    <span className="font-medium">{l.agent}:</span> {l.lesson}
                  </li>
                ))}
              </ul>
            </div>
          )}
        </CardContent>
      </Card>

      <UsageTable usage={report.usage} />

      <p className="text-xs text-muted-foreground">
        Generated automatically from the company&apos;s stored filings, prices and news, Yahoo Finance data and AI
        models. It can contain errors and is not investment advice.{" "}
        <Link href="/screening/analysis" className={buttonVariants({ variant: "link", size: "xs", className: "px-0" })}>
          Back to analyses
        </Link>
      </p>
    </div>
  )
}

function Points({ title, items, tone }: { title: string; items: string[]; tone?: "positive" | "negative" }) {
  if (items.length === 0) return null
  return (
    <div>
      <p
        className={cn(
          "text-xs font-medium",
          tone === "positive"
            ? "text-emerald-700 dark:text-emerald-400"
            : tone === "negative"
              ? "text-rose-700 dark:text-rose-400"
              : "text-muted-foreground"
        )}
      >
        {title}
      </p>
      <ul className="list-disc space-y-0.5 pl-4">
        {items.map((item) => (
          <li key={item}>{item}</li>
        ))}
      </ul>
    </div>
  )
}

function Research({ report, active }: { report: Report; active: boolean }) {
  const research = report.research
  const brief = research?.brief
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex flex-wrap items-center gap-2">
          <BookOpenTextIcon className="size-4 text-primary" /> Research brief
          {brief && (
            <Badge variant="outline" className={cn("border-transparent", RESEARCH_SENTIMENT_CLASS[brief.newsSentiment])}>
              news {brief.newsSentiment === "NONE" ? "none" : brief.newsSentiment.toLowerCase()}
            </Badge>
          )}
        </CardTitle>
        <CardDescription>
          The research agent searched the company&apos;s stored financial statement PDFs, monthly registration reports
          and news (ReAct with tools); refs point to the excerpts it read (F = filing, R = registration report, N =
          news).
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3 text-sm">
        {!brief ? (
          <p className="text-muted-foreground">{active ? "The brief appears when the research agent has finished." : EMPTY}</p>
        ) : (
          <>
            <dl className="grid gap-2 sm:grid-cols-2">
              {(
                [
                  ["Business", brief.business],
                  ["Moat", brief.moat],
                  ["Management", brief.management],
                  ["Growth", brief.growth],
                  ["Ownership", brief.ownership ?? null],
                ] as const
              )
                .filter(([, text]) => text)
                .map(([label, text]) => (
                  <div key={label}>
                    <dt className="text-xs font-medium text-muted-foreground">{label}</dt>
                    <dd>{text}</dd>
                  </div>
                ))}
            </dl>
            {(brief.risks.length > 0 || brief.catalysts.length > 0) && (
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
            {brief.newsSummary && (
              <p>
                <span className="text-xs font-medium text-muted-foreground">News: </span>
                {brief.newsSummary}
              </p>
            )}
            {brief.evidence.length > 0 && (
              <div>
                <p className="text-xs font-medium text-muted-foreground">Evidence</p>
                <ul className="flex flex-col gap-0.5 text-xs">
                  {brief.evidence.map((e) => (
                    <li key={e.ref + e.fact}>
                      <Badge variant="secondary" className="mr-1.5 font-mono">
                        {e.ref}
                      </Badge>
                      {e.fact}
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </>
        )}
        {research?.note && <p className="text-xs text-muted-foreground">{research.note}</p>}
        {research && research.retrieved.length > 0 && (
          <details className="rounded-lg border text-xs">
            <summary className="cursor-pointer px-3 py-1.5 font-medium text-muted-foreground">
              Excerpts read ({research.retrieved.length})
            </summary>
            <div className="overflow-x-auto border-t">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead className="pl-3">Ref</TableHead>
                    <TableHead>Document</TableHead>
                    <TableHead>Where</TableHead>
                    <TableHead className="pr-3">Query</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {research.retrieved.map((r) => (
                    <TableRow key={r.ref}>
                      <TableCell className="pl-3 font-mono" title={SOURCE_LABEL[r.source] ?? r.source}>
                        {r.ref}
                      </TableCell>
                      <TableCell className="max-w-[320px]">
                        {r.url && /^https?:\/\//i.test(r.url) ? (
                          <a
                            href={r.url}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="inline-flex items-center gap-1 hover:underline"
                          >
                            <span className="truncate">{r.title}</span>
                            <ExternalLinkIcon className="size-3 shrink-0" />
                          </a>
                        ) : (
                          <span className="block truncate">{r.title ?? EMPTY}</span>
                        )}
                      </TableCell>
                      <TableCell className="whitespace-nowrap">{r.where ?? EMPTY}</TableCell>
                      <TableCell className="max-w-[220px] truncate pr-3 text-muted-foreground">{r.query}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          </details>
        )}
        {research && research.trace.length > 0 && (
          <details className="rounded-lg border text-xs">
            <summary className="cursor-pointer px-3 py-1.5 font-medium text-muted-foreground">
              Research agent steps (ReAct)
            </summary>
            <ol className="flex flex-col gap-1 border-t px-3 py-2">
              {research.trace.map((step) => (
                <li key={step.iteration}>
                  <span className="font-medium">Turn {step.iteration}:</span> {step.thought ?? "(answer)"}
                  {step.tools.length > 0 && <span className="text-muted-foreground"> → {step.tools.join(", ")}</span>}
                </li>
              ))}
            </ol>
          </details>
        )}
      </CardContent>
    </Card>
  )
}

function Figures({ report, active }: { report: Report; active: boolean }) {
  const sheet = report.context?.factSheet
  const periods = sheet?.periods ?? []
  const rows = figureRows(periods)
  const valuation = sheet?.latestValuation
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <TableIcon className="size-4 text-primary" /> Key figures
        </CardTitle>
        <CardDescription>
          From the stored filings{sheet ? ` (${sheet.amountUnit.split(";")[0]})` : ""}. H1 and 9M are year-to-date.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3 px-0">
        {periods.length === 0 ? (
          <p className="px-4 text-sm text-muted-foreground">
            {active && !sheet ? "Loading..." : "No financial statements are stored for this company."}
          </p>
        ) : (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="pl-4" />
                  {periods.map((p) => (
                    <TableHead key={p.period} className="text-right last:pr-4">
                      {p.period}
                    </TableHead>
                  ))}
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((row) => (
                  <TableRow key={row.key}>
                    <TableCell className="pl-4 text-xs whitespace-nowrap">{row.label}</TableCell>
                    {periods.map((p) => (
                      <TableCell key={p.period} className="text-right text-xs tabular-nums last:pr-4">
                        {figure(p, row)}
                      </TableCell>
                    ))}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
        {valuation && (
          <p className="px-4 text-xs text-muted-foreground">
            Valuation of {formatDate(String(valuation.date))}: price {formatNumber(num(valuation.sharePrice), 2)} · P/E{" "}
            {formatNumber(num(valuation.pe), 2)} · P/B {formatNumber(num(valuation.pb), 2)} · FCF yield{" "}
            {formatPercent(num(valuation.fcfYield), 1)} · market cap {formatNumber(num(valuation.marketCap), 1)}{" "}
            {sheet?.amountUnit.split(" (")[0]}
          </p>
        )}
      </CardContent>
    </Card>
  )
}

/** The source of an excerpt (RetrievedExcerpt.source). */
const SOURCE_LABEL: Record<string, string> = {
  PDF: "Financial statement",
  REGISTRATION: "Registration report",
  NEWS: "News article",
}

/** "+2,500,000" in green, "-1,000,000" in red, "0" muted. */
function SignedShares({ value }: { value: number | null | undefined }) {
  if (value === null || value === undefined) return <span className="text-muted-foreground">{EMPTY}</span>
  if (value === 0) return <span className="text-muted-foreground">0</span>
  return (
    <span className={value > 0 ? "text-emerald-700 dark:text-emerald-400" : "text-rose-700 dark:text-rose-400"}>
      {value > 0 ? "+" : ""}
      {formatNumber(value, 0)}
    </span>
  )
}

/** The directors' and commissioners' shares the agents saw (monthly registration reports, Companies > Registration). */
function InsiderOwnership({ report, active }: { report: Report; active: boolean }) {
  const insiders = report.context?.insiderOwnership
  const ticker = report.run.ticker
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <UsersIcon className="size-4 text-primary" /> Insider ownership
        </CardTitle>
        <CardDescription>
          The shares of the directors and commissioners from the monthly registration reports
          {insiders ? ` (${rangeLabel(insiders.months[0], insiders.latestMonth)})` : ""}, given to every agent.{" "}
          <Link
            href={`/companies/registration?tickers=${encodeURIComponent(ticker)}`}
            className="text-primary hover:underline"
          >
            Companies &gt; Registration
          </Link>
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3 px-0 text-sm">
        {!insiders ? (
          <p className="px-4 text-muted-foreground">
            {active && !report.context
              ? "Loading..."
              : "No monthly registration reports are stored for this company: upload them on Ingestion > PDF Documents (RAG) with the Registration type."}
          </p>
        ) : (
          <>
            <p className="px-4">{insiders.reading}</p>
            <div className="overflow-x-auto">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead className="pl-4">Director / commissioner</TableHead>
                    <TableHead>Position</TableHead>
                    <TableHead className="text-right">Shares ({monthLabel(insiders.latestMonth)})</TableHead>
                    <TableHead className="text-right">Ownership</TableHead>
                    <TableHead className="text-right">Latest month</TableHead>
                    <TableHead className="pr-4 text-right">Net change</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {insiders.insiders.map((i) => (
                    <TableRow key={i.name}>
                      <TableCell className="pl-4">
                        <div className="font-medium">{i.name}</div>
                        {(i.controller || i.controllerAffiliate) && (
                          <div className="text-xs text-muted-foreground">
                            {i.controller ? "Controller" : "Affiliate of the controller"}
                          </div>
                        )}
                      </TableCell>
                      <TableCell className="text-xs">{i.position}</TableCell>
                      <TableCell className="text-right tabular-nums">{formatNumber(i.shares, 0)}</TableCell>
                      <TableCell className="text-right tabular-nums">{formatPercent(i.ownership, 3)}</TableCell>
                      <TableCell className="text-right tabular-nums">
                        <SignedShares value={i.changeLatestMonth} />
                      </TableCell>
                      <TableCell className="pr-4 text-right tabular-nums">
                        <SignedShares value={i.netChange} />
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
            {insiders.transactions.length > 0 && (
              <div className="px-4">
                <p className="text-xs font-medium text-muted-foreground">Buys and sells</p>
                <ul className="flex flex-col gap-0.5 text-xs">
                  {insiders.transactions.map((t) => (
                    <li key={`${t.month}-${t.name}`}>
                      <span className="tabular-nums">{monthLabel(t.month)}</span> · {t.name}{" "}
                      {t.change > 0 ? "bought" : "sold"} {formatNumber(Math.abs(t.change), 0)} shares (
                      {formatNumber(t.sharesAfter, 0)} after)
                    </li>
                  ))}
                </ul>
              </div>
            )}
            {(insiders.missingMonths?.length ?? 0) > 0 && (
              <p className="px-4 text-xs text-muted-foreground">
                Months without a stored report: {insiders.missingMonths!.map(monthLabel).join(", ")}.
              </p>
            )}
            {(insiders.notInLatestReport?.length ?? 0) > 0 && (
              <p className="px-4 text-xs text-muted-foreground">
                In an earlier report but not in the latest: {insiders.notInLatestReport!.join(", ")}.
              </p>
            )}
            {insiders.reportWarnings && <p className="px-4 text-xs text-amber-600">{insiders.reportWarnings}.</p>}
          </>
        )}
      </CardContent>
    </Card>
  )
}

function num(value: number | string | undefined): number | null {
  return typeof value === "number" ? value : null
}

function DataUsed({ report }: { report: Report }) {
  const context = report.context
  const documents = context?.documents
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <DatabaseIcon className="size-4 text-primary" /> Data the agents used
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-2 text-sm">
        {!context ? (
          <p className="text-muted-foreground">{EMPTY}</p>
        ) : (
          <>
            <p>
              <span className="font-medium">Statements:</span>{" "}
              {context.periods.length > 0
                ? `${context.periods.length} stored periods (${context.periods.slice(0, 3).join(", ")}${
                    context.periods.length > 3 ? ", ..." : ""
                  })`
                : "none"}
            </p>
            <p>
              <span className="font-medium">Market data:</span>{" "}
              {context.marketData
                ? `Yahoo Finance of ${formatDate(context.marketData.date)}${
                    context.quantitativeScorecards ? ", with quantitative scorecards" : ", no quantitative scorecards"
                  }`
                : "none (not in the screening data)"}
            </p>
            <p>
              <span className="font-medium">PDF documents:</span> {documents?.pdfDocuments ?? 0}
              {documents && documents.pdfTitles.length > 0 && (
                <span className="text-muted-foreground"> ({documents.pdfTitles.join("; ")})</span>
              )}
            </p>
            <p>
              <span className="font-medium">Registration reports:</span> {documents?.registrationReports ?? 0}
              {documents?.registrationTitles && documents.registrationTitles.length > 0 && (
                <span className="text-muted-foreground"> ({documents.registrationTitles.join("; ")})</span>
              )}
            </p>
            <p>
              <span className="font-medium">News articles:</span> {documents?.newsArticles ?? 0}
            </p>
            {documents && documents.latestNews.length > 0 && (
              <ul className="list-disc pl-4 text-xs text-muted-foreground">
                {documents.latestNews.slice(0, 5).map((t) => (
                  <li key={t}>{t}</li>
                ))}
              </ul>
            )}
          </>
        )}
      </CardContent>
    </Card>
  )
}
