"use client"

import { useEffect, useState } from "react"
import Link from "next/link"
import {
  ArrowLeftIcon,
  BookOpenTextIcon,
  BrainCircuitIcon,
  CircleAlertIcon,
  CoinsIcon,
  GaugeIcon,
  LandmarkIcon,
  LoaderIcon,
  ScaleIcon,
  SparklesIcon,
  TimerIcon,
  UsersIcon,
} from "lucide-react"

import { JobStatusBadge } from "@/components/ingestion/job-status-badge"
import { DownloadPdfButton } from "@/components/screening/download-pdf-button"
import { UsageTable } from "@/components/screening/usage-table"
import { StatTile } from "@/components/stat-tile"
import { Badge } from "@/components/ui/badge"
import { buttonVariants } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { requestJson } from "@/lib/client-api"
import { EMPTY, formatDate, formatNumber, formatTimestamp } from "@/lib/format"
import { creatorLabel, formatDuration, isActive } from "@/lib/ingestion"
import { MACRO_ANALYSIS_HREF, MACRO_HREF, type Tone } from "@/lib/macro"
import {
  ACTION_LABEL,
  ACTION_STYLE,
  investabilityClass,
  MACRO_AGENT_SHORT,
  TILT_STYLE,
  verdictText,
  type MacroAgentView,
  type MacroAnalysisReport as Report,
} from "@/lib/macro-analysis"
import { CONVICTION_CLASS, formatScore, formatTokens, formatUsd } from "@/lib/screening"
import { cn } from "@/lib/utils"

const POLL_MS = 3000

const TONE_CLASS: Record<Tone, string> = {
  POSITIVE: "text-emerald-700 dark:text-emerald-400",
  NEGATIVE: "text-rose-700 dark:text-rose-400",
  NEUTRAL: "text-muted-foreground",
}

const TONE_LABEL: Record<Tone, string> = {
  POSITIVE: "supportive",
  NEGATIVE: "headwind",
  NEUTRAL: "neutral",
}

function signed(value: number | null | undefined, digits = 1, suffix = "%"): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return EMPTY
  return `${value > 0 ? "+" : ""}${formatNumber(value, digits)}${suffix}`
}

/** One macro analysis report; polls every 3 s while the analysis is queued or running. */
export function MacroAnalysisReport({ initial }: { initial: Report }) {
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
        const { body } = await requestJson<Report>(`/api/macro/analyses/${encodeURIComponent(run.id)}`)
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
            href={MACRO_ANALYSIS_HREF}
            className="inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
          >
            <ArrowLeftIcon className="size-3.5" /> All macro analyses
          </Link>
          <h1 className="text-xl font-semibold tracking-tight">Is {run.countryName ?? run.country} investable? · {run.indexCode}</h1>
          <p className="text-sm text-muted-foreground">
            {run.agents.map((a) => MACRO_AGENT_SHORT[a]).join(", ")} · requested {formatTimestamp(run.requestedAt)} by{" "}
            {creatorLabel(run.createdBy)}
            {run.asOfDate && ` · data as of ${formatDate(run.asOfDate)}`}
          </p>
        </div>
        <DownloadPdfButton
          href={`/api/macro/analyses/${encodeURIComponent(run.id)}/pdf`}
          fileName={`macro-analysis-${run.country}-${run.indexCode}-${run.id}.pdf`}
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

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
        <StatTile
          label="Investability"
          icon={GaugeIcon}
          tone={run.overallScore == null ? "muted" : run.overallScore >= 60 ? "positive" : run.overallScore >= 40 ? "primary" : "negative"}
          value={<span className={investabilityClass(run.overallScore)}>{formatScore(run.overallScore)}</span>}
          hint={run.verdict ? `${verdictText(run.verdict)} · 0 – 100` : active ? "after the agents and the synthesis" : "no score"}
        />
        <StatTile
          label="Action"
          icon={ScaleIcon}
          tone="muted"
          value={
            run.action ? (
              <Badge variant="outline" className={cn("font-semibold", ACTION_STYLE[run.action])}>
                {ACTION_LABEL[run.action]}
              </Badge>
            ) : (
              EMPTY
            )
          }
          hint={run.conviction ? `${run.conviction.toLowerCase()} conviction` : "from the score and the trend"}
        />
        <StatTile
          label="Rules score"
          icon={LandmarkIcon}
          tone="muted"
          value={<span className={investabilityClass(run.rulesScore)}>{formatScore(run.rulesScore)}</span>}
          hint={syn?.agentsMean != null ? `agents' mean ${formatScore(syn.agentsMean)}` : "the Macro page, no AI"}
        />
        <StatTile
          label="AI cost"
          icon={CoinsIcon}
          tone={run.costUsd > run.budgetUsd ? "negative" : "positive"}
          value={formatUsd(run.costUsd)}
          hint={`budget ${formatUsd(run.budgetUsd)} · ${formatTokens(run.promptTokens + run.completionTokens)} tokens`}
        />
        <StatTile label="Duration" icon={TimerIcon} tone="muted" value={formatDuration(elapsed)} hint={active ? "running" : `${run.modelCalls} model calls`} />
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
              {syn.model ? `Synthesis by ${syn.model}` : `Written without the synthesis model: ${syn.fallback ?? "unknown reason"}`}
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
            {syn.sectorTilts.length > 0 && (
              <div>
                <p className="text-xs font-medium text-muted-foreground">Sector tilts for {run.indexCode}</p>
                <ul className="mt-1 grid gap-1.5 sm:grid-cols-2">
                  {syn.sectorTilts.map((t) => (
                    <li key={t.sector} className="rounded-lg border px-2.5 py-1.5 text-xs">
                      <span className="font-medium">{t.sector}</span>{" "}
                      <span className={cn("font-semibold", TILT_STYLE[t.tilt])}>{t.tilt.toLowerCase()}</span>
                      {t.why && <span className="block text-muted-foreground">{t.why}</span>}
                    </li>
                  ))}
                </ul>
              </div>
            )}
            {syn.dataGaps.length > 0 && <p className="text-xs text-muted-foreground">Data gaps: {syn.dataGaps.join("; ")}</p>}
          </CardContent>
        </Card>
      )}

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <UsersIcon className="size-4 text-primary" /> Macro agents
          </CardTitle>
          <CardDescription>
            Agent score = 60% its preset of the rules (the prior) + 40% its AI judgement after reflection; investability =
            80% the agents&apos; mean + 20% the rules score, plus the synthesis adjustment (at most ±5).
          </CardDescription>
        </CardHeader>
        <CardContent>
          {report.agents.length > 0 ? (
            <div className="grid gap-3 lg:grid-cols-2">
              {report.agents.map((a) => (
                <AgentCard key={a.agent} agent={a} />
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

      <div className="grid items-start gap-4 xl:grid-cols-2">
        <RulesSnapshot report={report} />
        <MarketsSnapshot report={report} />
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
              <p className="text-xs font-medium text-muted-foreground">Lessons from earlier analyses used in the prompts</p>
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
        Generated automatically from the stored macro series, index, commodity and currency data, fixed rules and AI
        models. Macro data lag the market; it can contain errors and is not investment advice.{" "}
        <Link href={MACRO_HREF} className={buttonVariants({ variant: "link", size: "xs", className: "px-0" })}>
          Open the Macro outlook
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

function AgentCard({ agent: a }: { agent: MacroAgentView }) {
  const issues = a.reflection?.issues ?? []
  return (
    <section aria-label={`${a.label} view`} className="flex min-w-0 flex-col gap-2 rounded-xl border p-3">
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <h3 className="font-semibold">{a.label}</h3>
          <p className="text-xs text-muted-foreground">{a.focus}</p>
        </div>
        <div className="text-right">
          <span className={cn("font-mono text-2xl font-semibold tabular-nums", investabilityClass(a.finalScore))}>
            {formatScore(a.finalScore)}
          </span>
          {a.stance && (
            <div>
              <Badge variant="outline" className={cn("font-semibold", ACTION_STYLE[a.stance])}>
                {ACTION_LABEL[a.stance]}
              </Badge>
            </div>
          )}
        </div>
      </div>
      <p className="text-xs text-muted-foreground">
        Prior {formatScore(a.priorScore)}
        {a.priorDetail && ` (preset ${a.priorDetail.preset}${a.priorDetail.stance ? `, ${a.priorDetail.stance.toLowerCase()}` : ""})`}
        {" · "}AI {formatScore(a.aiScore)}
        {a.status === "REVISED" && " · revised after reflection"}
        {a.status === "PRIOR_ONLY" && " · rules prior only (no model answer)"}
      </p>
      {a.thesis && <p className="text-sm">{a.thesis}</p>}
      <div className="grid gap-2 text-xs sm:grid-cols-2">
        <MiniList title="Supports" items={a.supports} tone="positive" />
        <MiniList title="Risks" items={a.risks} tone="negative" />
        <MiniList title="Buy when" items={a.buyWhen} />
        <MiniList title="Sell when" items={a.sellWhen} />
      </div>
      {(issues.length > 0 || a.reflection?.error) && (
        <p className="rounded-md bg-muted/50 px-2 py-1 text-[11px] text-muted-foreground">
          {issues.length > 0 && `Reflection flagged: ${issues.map((i) => i.code.toLowerCase()).join(", ")}`}
          {a.reflection?.revised &&
            (a.reflection.changed
              ? ` · revised ${Math.round(a.reflection.original?.score ?? 0)} → ${Math.round(a.reflection.revised.score)}`
              : " · kept after review")}
          {a.reflection?.note && ` (${a.reflection.note})`}
          {a.reflection?.error && `No usable answer: ${a.reflection.error}`}
        </p>
      )}
    </section>
  )
}

function MiniList({ title, items, tone }: { title: string; items: string[] | null; tone?: "positive" | "negative" }) {
  if (!items || items.length === 0) return null
  return (
    <div>
      <p
        className={cn(
          "font-medium",
          tone === "positive" ? "text-emerald-700 dark:text-emerald-400" : tone === "negative" ? "text-rose-700 dark:text-rose-400" : "text-muted-foreground"
        )}
      >
        {title}
      </p>
      <ul className="list-disc space-y-0.5 pl-4">
        {items.map((i) => (
          <li key={i}>{i}</li>
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
        <CardTitle className="flex items-center gap-2">
          <BookOpenTextIcon className="size-4 text-primary" /> Research brief
        </CardTitle>
        <CardDescription>
          The research agent read the stored history of the macro series, commodities, currencies and indexes and
          searched the country&apos;s macro documents published by the as-of date (ReAct with tools); refs point to the
          tool results it read (T1, T2, ...) and the document excerpts (D1, D2, ...).
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
                  ["Regime", brief.regime],
                  ["Liquidity", brief.liquidity],
                  ["Inflation and rates", brief.inflationRates],
                  ["External", brief.external],
                  ["Growth", brief.growth],
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
              Tool results read ({research.retrieved.length})
            </summary>
            <div className="overflow-x-auto border-t">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead className="pl-3">Ref</TableHead>
                    <TableHead>Tool</TableHead>
                    <TableHead>Arguments</TableHead>
                    <TableHead className="pr-3">Result</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {research.retrieved.map((r) => (
                    <TableRow key={r.ref}>
                      <TableCell className="pl-3 font-mono">{r.ref}</TableCell>
                      <TableCell>{r.tool}</TableCell>
                      <TableCell className="whitespace-nowrap">{r.arguments}</TableCell>
                      <TableCell className="min-w-[280px] pr-3 text-muted-foreground">{r.summary}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          </details>
        )}
        {research?.documents && research.documents.length > 0 && (
          <details className="rounded-lg border text-xs">
            <summary className="cursor-pointer px-3 py-1.5 font-medium text-muted-foreground">
              Macro documents the research could search ({research.documents.length})
            </summary>
            <ul className="flex flex-col gap-1 border-t px-3 py-2">
              {research.documents.map((d) => (
                <li key={d.documentId}>
                  <span className="tabular-nums text-muted-foreground">{formatDate(d.publishedOn)}</span>{" "}
                  {d.issuer && <span className="font-medium">{d.issuer}: </span>}
                  {d.title} <span className="text-muted-foreground">({d.documentTypeLabel})</span>
                </li>
              ))}
            </ul>
          </details>
        )}
        {research && research.trace.length > 0 && (
          <details className="rounded-lg border text-xs">
            <summary className="cursor-pointer px-3 py-1.5 font-medium text-muted-foreground">Research agent steps (ReAct)</summary>
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

function RulesSnapshot({ report }: { report: Report }) {
  const ctx = report.context
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <LandmarkIcon className="size-4 text-primary" /> The rules the agents read
        </CardTitle>
        <CardDescription>The Macro page as of {formatDate(ctx?.asOfDate)} (fixed rules, no AI).</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3 text-sm">
        {!ctx ? (
          <p className="text-muted-foreground">{EMPTY}</p>
        ) : (
          <>
            {ctx.rulesConclusion && (
              <p>
                <span className={cn("font-mono font-semibold", investabilityClass(ctx.rulesConclusion.score))}>
                  {ctx.rulesConclusion.score ?? EMPTY}
                </span>
                /100 · {ctx.rulesConclusion.verdictLabel} · {ctx.rulesConclusion.actionLabel}: {ctx.rulesConclusion.reason}
              </p>
            )}
            <ul className="space-y-1 text-xs">
              {(["liquidity", "risk"] as const).map((key) => {
                const r = ctx.regimes[key]
                return (
                  <li key={key}>
                    <span className="font-medium">{key === "liquidity" ? "Liquidity (Druckenmiller)" : "Risk (Tudor Jones)"}:</span>{" "}
                    {r?.state ? `${r.state.replace("_", " ").toLowerCase()} (${signed(r.score, 0, "")} of ${r.signals})` : "unknown"}
                    {r?.stateThen && <span className="text-muted-foreground"> · 3 months ago {r.stateThen.replace("_", " ").toLowerCase()}</span>}
                  </li>
                )
              })}
            </ul>
            <ul className="space-y-1 text-xs">
              {ctx.indexes.map((i) => (
                <li key={i.code}>
                  <span className="font-medium">{i.code}</span> {formatNumber(i.close, 2)} · 3M {signed(i.change3mPct)} · 1Y{" "}
                  {signed(i.change1yPct)} · {signed(i.vsSma200Pct)} vs its 200-day average
                </li>
              ))}
            </ul>
            <div className="grid gap-2 sm:grid-cols-2">
              {ctx.presets.map((p) => (
                <div key={p.preset} className="rounded-lg border px-2.5 py-1.5 text-xs">
                  <span className="font-medium">{p.name}</span>{" "}
                  <Badge variant="outline" className={cn("ml-1", ACTION_STYLE[p.stance])}>
                    {ACTION_LABEL[p.stance]}
                  </Badge>
                  <span className="mt-0.5 block text-muted-foreground">{p.stanceLabel}</span>
                </div>
              ))}
            </div>
          </>
        )}
      </CardContent>
    </Card>
  )
}

function MarketsSnapshot({ report }: { report: Report }) {
  const markets = report.context?.markets
  const rows = markets ? [...markets.commodities, ...markets.currencyPairs] : []
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <CoinsIcon className="size-4 text-primary" /> Commodities and currencies
        </CardTitle>
        <CardDescription>{markets?.termsOfTrade.summary ?? "No commodity or currency prices were stored."}</CardDescription>
      </CardHeader>
      <CardContent className="px-0">
        {rows.length > 0 && (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="pl-4">Instrument</TableHead>
                  <TableHead className="text-right">3M</TableHead>
                  <TableHead className="text-right">1Y</TableHead>
                  <TableHead className="pr-4">For the country</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((r) => (
                  <TableRow key={r.code}>
                    <TableCell className="pl-4 text-xs">
                      {r.name}
                      {r.stale && <span className="ml-1 text-amber-600 dark:text-amber-400">(stale)</span>}
                    </TableCell>
                    <TableCell className="text-right font-mono text-xs tabular-nums">{signed(r.change3mPct)}</TableCell>
                    <TableCell className="text-right font-mono text-xs tabular-nums">{signed(r.change1yPct)}</TableCell>
                    <TableCell className={cn("pr-4 text-xs font-medium", TONE_CLASS[r.tone])}>{TONE_LABEL[r.tone]}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </CardContent>
    </Card>
  )
}
