"use client"

import { useEffect, useState } from "react"
import Link from "next/link"
import {
  ArrowLeftIcon,
  BrainCircuitIcon,
  CircleAlertIcon,
  CoinsIcon,
  CpuIcon,
  FilterIcon,
  LoaderIcon,
  SparklesIcon,
  TimerIcon,
  TrendingDownIcon,
  TrendingUpIcon,
} from "lucide-react"

import { JobStatusBadge } from "@/components/ingestion/job-status-badge"
import { UsageTable } from "@/components/screening/usage-table"
import { StatTile } from "@/components/stat-tile"
import { TechnicalCandidateSheet } from "@/components/technical-screening/candidate-sheet"
import { TechnicalRankingTable } from "@/components/technical-screening/ranking-table"
import { buttonVariants } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Table, TableBody, TableCell, TableRow } from "@/components/ui/table"
import { EMPTY, formatDate, formatNumber, formatTimestamp } from "@/lib/format"
import { requestJson } from "@/lib/client-api"
import { creatorLabel, formatDuration, isActive } from "@/lib/ingestion"
import { formatTokens, formatUsd } from "@/lib/screening"
import {
  formatIdr,
  formatSignedPct,
  TECHNICAL_AGENT_SHORT,
  technicalRunsHref,
  technicalRunTitle,
  type TechnicalCandidate,
  type TechnicalScreeningReport,
} from "@/lib/technical-screening"

const POLL_MS = 3000

/** One technical screening report; polls every 3 s while the run is queued or running. */
export function TechnicalReportView({ initial }: { initial: TechnicalScreeningReport }) {
  const [report, setReport] = useState(initial)
  const [error, setError] = useState<string | null>(null)
  const [selected, setSelected] = useState<TechnicalCandidate | null>(null)
  const [now, setNow] = useState<number | null>(null)
  const run = report.run
  const active = isActive(run.status)

  useEffect(() => {
    if (!active) return
    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | undefined
    async function load() {
      try {
        const { body } = await requestJson<TechnicalScreeningReport>(
          `/api/technical-screenings/${encodeURIComponent(run.id)}`
        )
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

  const selectedList = report.candidates.filter((c) => c.selected)
  const others = report.candidates.filter((c) => !c.selected)
  const started = run.startedAt ? new Date(run.startedAt).getTime() : null
  const elapsed =
    started === null ? null : run.finishedAt ? new Date(run.finishedAt).getTime() - started : now === null ? null : now - started
  const messages = report.notes?.messages ?? []
  const learned = report.notes?.lessonsLearned ?? []
  const applied = report.notes?.lessonsApplied ?? {}
  const market = report.market
  // the shown candidate follows the latest report data
  const shown = selected ? report.candidates.find((c) => c.id === selected.id) ?? selected : null

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-1">
        <Link
          href={technicalRunsHref(run)}
          className="inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
        >
          <ArrowLeftIcon className="size-3.5" /> All technical screenings
        </Link>
        <h1 className="text-xl font-semibold tracking-tight">Technical screening · {technicalRunTitle(run)}</h1>
        <p className="text-sm text-muted-foreground">
          {run.agents.map((a) => TECHNICAL_AGENT_SHORT[a]).join(", ")} · requested {formatTimestamp(run.requestedAt)} by{" "}
          {creatorLabel(run.createdBy)}
          {run.asOfDate && ` · prices of ${formatDate(run.asOfDate)}`}
        </p>
        {run.tickers && <p className="text-xs text-muted-foreground">Selected stocks: {run.tickers.join(", ")}</p>}
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

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
        <StatTile
          label="Funnel"
          icon={FilterIcon}
          value={`${run.universeCount ?? EMPTY} → ${run.eligibleCount ?? EMPTY} → ${run.shortlistCount ?? EMPTY} → ${
            run.selectedCount ?? EMPTY
          }`}
          hint="universe → eligible → shortlist → selected"
        />
        <StatTile
          label={market?.index ? `${market.index} vs SMA 200` : "Market"}
          icon={market?.indexAboveSma200 === false ? TrendingDownIcon : TrendingUpIcon}
          tone={market?.indexAboveSma200 === undefined ? "muted" : market.indexAboveSma200 ? "positive" : "negative"}
          value={market?.indexVsSma200Pct != null ? formatSignedPct(market.indexVsSma200Pct) : EMPTY}
          hint={
            market?.breadthAbove200Pct != null
              ? `${formatIdr(market.indexClose)} · breadth ${formatNumber(market.breadthAbove200Pct, 0)}% above SMA 200`
              : "IHSG trend and breadth"
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

      {report.synthesis?.executiveSummary && (
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <SparklesIcon className="size-4 text-primary" /> Executive summary
            </CardTitle>
            <CardDescription>
              {report.synthesis.model
                ? `Synthesis by ${report.synthesis.model}`
                : `Written without the synthesis model: ${report.synthesis.fallback ?? "unknown reason"}`}
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-2 text-sm">
            <p className="whitespace-pre-line">{report.synthesis.executiveSummary}</p>
            {(report.synthesis.marketNotes?.length ?? 0) > 0 && (
              <ul className="list-disc space-y-1 pl-4 text-muted-foreground">
                {report.synthesis.marketNotes?.map((n) => <li key={n}>{n}</li>)}
              </ul>
            )}
          </CardContent>
        </Card>
      )}

      <Card>
        <CardHeader>
          <CardTitle>Final ranking and trade plans</CardTitle>
          <CardDescription>
            Where to buy, where to sell (the target) and where to stop the loss for each stock. Agent score = 60% its
            preset&apos;s rules summary (Companies › Technicals) + 40% its AI judgement (after reflection); overall = the
            average of the agents. A buy marked ↑ is a buy-stop: at or above the price (a breakout). Click a stock for
            every agent&apos;s view and plan.
          </CardDescription>
        </CardHeader>
        <CardContent className="px-0">
          {selectedList.length > 0 ? (
            <TechnicalRankingTable candidates={selectedList} agents={run.agents} onSelect={setSelected} />
          ) : (
            <p className="px-4 text-sm text-muted-foreground">
              {active ? "The ranking appears when the agents have finished." : "No stock was selected."}
            </p>
          )}
        </CardContent>
      </Card>

      {others.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle>Rest of the shortlist</CardTitle>
            <CardDescription>
              {active
                ? "Stage 1 shortlist, by rules score while the agents work."
                : "Read by the agents but outside the top N."}
            </CardDescription>
          </CardHeader>
          <CardContent className="px-0">
            <TechnicalRankingTable candidates={others} agents={run.agents} onSelect={setSelected} />
          </CardContent>
        </Card>
      )}

      <div className="grid items-start gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <FilterIcon className="size-4 text-primary" /> Stage 1 filters (rules, no AI)
            </CardTitle>
          </CardHeader>
          <CardContent className="px-0">
            {report.funnel && report.funnel.length > 0 ? (
              <Table>
                <TableBody>
                  {report.funnel.map((step) => (
                    <TableRow key={step.key}>
                      <TableCell className="pl-4 text-sm">{step.label}</TableCell>
                      <TableCell className="pr-4 text-right tabular-nums">{step.remaining}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            ) : (
              <p className="px-4 text-sm text-muted-foreground">{active ? "Running..." : EMPTY}</p>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <BrainCircuitIcon className="size-4 text-primary" /> Notes, reflection and Reflexion memory
            </CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-3 text-sm">
            {messages.length === 0 && learned.length === 0 && Object.keys(applied).length === 0 && (
              <p className="text-muted-foreground">{active ? "Notes appear when the run has finished." : "Nothing to note."}</p>
            )}
            {messages.length > 0 && (
              <ul className="list-disc space-y-1 pl-4">
                {messages.map((m) => <li key={m}>{m}</li>)}
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
                <p className="text-xs font-medium text-muted-foreground">Lessons learned in this run</p>
                <ul className="list-disc space-y-0.5 pl-4 text-xs">
                  {learned.map((l) => (
                    <li key={l.agent + l.lesson}>
                      <span className="font-medium">{l.agent}</span> ({l.occurrencesInRun}×): {l.lesson}
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </CardContent>
        </Card>
      </div>

      <UsageTable usage={report.usage} />

      <p className="text-xs text-muted-foreground">
        Technical analysis only, generated automatically from daily prices (Yahoo Finance) and AI models. It can contain
        errors and is not investment advice.{" "}
        <Link href={technicalRunsHref(run)} className={buttonVariants({ variant: "link", size: "xs", className: "px-0" })}>
          Back to technical screenings
        </Link>
      </p>

      <TechnicalCandidateSheet candidate={shown} exchange={run.exchange} onClose={() => setSelected(null)} />
    </div>
  )
}
