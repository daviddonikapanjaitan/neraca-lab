"use client"

import { useCallback, useEffect, useState } from "react"
import Link from "next/link"
import { useRouter } from "next/navigation"
import { BrainCircuitIcon, ChevronRightIcon, CircleAlertIcon, HistoryIcon, LoaderIcon, PlayIcon } from "lucide-react"

import { JobStatusBadge } from "@/components/ingestion/job-status-badge"
import { Badge } from "@/components/ui/badge"
import { Button, buttonVariants } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { jsonBody, requestJson } from "@/lib/client-api"
import { EMPTY, formatDate, formatTimestamp } from "@/lib/format"
import { isActive } from "@/lib/ingestion"
import { MACRO_ANALYSIS_HREF } from "@/lib/macro"
import {
  ACTION_LABEL,
  ACTION_STYLE,
  investabilityClass,
  macroAnalysisHref,
  verdictText,
  type MacroAnalysisPage,
  type MacroAnalysisReport,
  type MacroAnalysisRun,
} from "@/lib/macro-analysis"
import { CONVICTION_CLASS, formatScore, formatUsd } from "@/lib/screening"
import { cn } from "@/lib/utils"

const POLL_MS = 3000

/**
 * The AI side of the Macro page (its right column): the latest AI macro analysis of the chosen index - investability,
 * verdict, action, each agent's stance and the thesis, beside the rules' score - and a button that starts a new one.
 * Polls every 3 s while an analysis is queued or running.
 */
export function MacroAiPanel({
  country,
  index,
  rulesScore,
}: {
  country: string
  index: string
  /** the page's rules score, compared with the AI's */
  rulesScore: number | null
}) {
  const router = useRouter()
  const [latest, setLatest] = useState<MacroAnalysisRun | null>(null)
  const [report, setReport] = useState<MacroAnalysisReport | null>(null)
  /** the country and index of the analysis shown; differs from the chosen ones while it loads */
  const key = `${country}|${index}`
  const [loadedKey, setLoadedKey] = useState<string | null>(null)
  const loading = loadedKey !== key
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    const query = new URLSearchParams({ country, index, limit: "1" })
    const { body } = await requestJson<MacroAnalysisPage>(`/api/macro/analyses?${query}`)
    const run = body.analyses[0] ?? null
    let full: MacroAnalysisReport | null = null
    if (run && !isActive(run.status)) {
      full = (await requestJson<MacroAnalysisReport>(`/api/macro/analyses/${encodeURIComponent(run.id)}`)).body
    }
    return { run, full }
  }, [country, index])

  useEffect(() => {
    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | undefined
    async function refresh() {
      try {
        const { run, full } = await load()
        if (cancelled) return
        setLatest(run)
        setReport(full)
        setError(null)
        if (run && isActive(run.status)) timer = setTimeout(refresh, POLL_MS)
      } catch (e) {
        if (cancelled) return
        setError(e instanceof Error ? e.message : String(e))
      } finally {
        if (!cancelled) setLoadedKey(key)
      }
    }
    refresh()
    return () => {
      cancelled = true
      if (timer) clearTimeout(timer)
    }
  }, [load, key])

  async function start() {
    if (pending) return
    setPending(true)
    setError(null)
    try {
      const { body: run } = await requestJson<MacroAnalysisRun>("/api/macro/analyses", jsonBody("POST", { country, index }))
      router.push(macroAnalysisHref(run.id))
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      setPending(false)
    }
  }

  const active = latest !== null && isActive(latest.status)
  const syn = report?.synthesis

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <BrainCircuitIcon className="size-4 text-primary" /> AI macro analysis
          {loading && <LoaderIcon className="size-3.5 animate-spin text-muted-foreground" aria-label="Loading" />}
        </CardTitle>
        <CardDescription>
          Four AI macro agents - Tudor Jones, Druckenmiller, Soros, Dalio - read {index} from the same data, its history,
          the commodities and currencies, and score how investable it is (0 – 100).
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3 text-sm">
        {error && (
          <p role="alert" className="flex items-start gap-2 text-xs text-destructive">
            <CircleAlertIcon className="mt-px size-3.5 shrink-0" />
            <span>{error}</span>
          </p>
        )}

        {!loading && latest === null && (
          <p className="text-muted-foreground">No AI analysis of {index} yet. Start one: it takes about a minute.</p>
        )}

        {latest && active && (
          <div className="flex flex-col gap-1.5 rounded-lg border p-2.5">
            <JobStatusBadge status={latest.status} />
            <span className="flex items-center gap-2 text-xs text-muted-foreground">
              <LoaderIcon className="size-3.5 animate-spin" /> {latest.stage}
            </span>
            <Link href={macroAnalysisHref(latest.id)} className={buttonVariants({ variant: "outline", size: "sm", className: "self-start" })}>
              Follow it <ChevronRightIcon />
            </Link>
          </div>
        )}

        {latest && !active && (
          <div className="flex flex-col gap-3">
            <div className="flex items-end justify-between gap-2">
              <div>
                <p className="text-xs text-muted-foreground">Investability (AI)</p>
                <p className="flex items-baseline gap-1">
                  <span className={cn("font-mono text-4xl font-semibold tabular-nums", investabilityClass(latest.overallScore))}>
                    {latest.overallScore === null ? EMPTY : Math.round(latest.overallScore)}
                  </span>
                  <span className="text-muted-foreground">/100</span>
                </p>
                <p className={cn("text-xs font-medium", investabilityClass(latest.overallScore))}>{verdictText(latest.verdict)}</p>
              </div>
              <div className="flex flex-col items-end gap-1">
                {latest.action && (
                  <Badge variant="outline" className={cn("font-semibold", ACTION_STYLE[latest.action])}>
                    {ACTION_LABEL[latest.action]}
                  </Badge>
                )}
                {latest.conviction && (
                  <Badge variant="outline" className={cn("border-transparent", CONVICTION_CLASS[latest.conviction])}>
                    {latest.conviction.toLowerCase()} conviction
                  </Badge>
                )}
              </div>
            </div>
            <p className="text-xs text-muted-foreground">
              Rules today {rulesScore ?? EMPTY} · rules then {formatScore(latest.rulesScore)} · data as of{" "}
              {formatDate(latest.asOfDate)} · {formatTimestamp(latest.requestedAt)} · {formatUsd(latest.costUsd)}
            </p>
            {latest.status !== "SUCCEEDED" && (
              <div className="flex items-center gap-2">
                <JobStatusBadge status={latest.status} />
                {latest.message && <span className="truncate text-xs text-amber-600 dark:text-amber-400">{latest.message}</span>}
              </div>
            )}
            {syn?.thesis && <p className="font-medium">{syn.thesis}</p>}
            {report && report.agents.length > 0 && (
              <ul className="flex flex-col gap-1">
                {report.agents.map((a) => (
                  <li key={a.agent} className="flex items-center justify-between gap-2 text-xs">
                    <span>{a.label}</span>
                    <span className="flex items-center gap-1.5">
                      <span className={cn("font-mono font-semibold tabular-nums", investabilityClass(a.finalScore))}>
                        {formatScore(a.finalScore)}
                      </span>
                      {a.stance && (
                        <Badge variant="outline" className={cn("px-1.5 py-0 text-[10px]", ACTION_STYLE[a.stance])}>
                          {ACTION_LABEL[a.stance]}
                        </Badge>
                      )}
                    </span>
                  </li>
                ))}
              </ul>
            )}
            {syn && syn.sectorTilts.length > 0 && (
              <p className="text-xs text-muted-foreground">
                Tilts:{" "}
                {syn.sectorTilts.map((t, i) => (
                  <span key={t.sector}>
                    {i > 0 && "; "}
                    {t.sector} {t.tilt.toLowerCase()}
                  </span>
                ))}
              </p>
            )}
            <Link href={macroAnalysisHref(latest.id)} className={buttonVariants({ variant: "outline", size: "sm", className: "self-start" })}>
              Open the report <ChevronRightIcon />
            </Link>
          </div>
        )}

        <div className="flex flex-wrap gap-2 border-t pt-3">
          <Button size="sm" onClick={start} disabled={pending || active}>
            {pending ? <LoaderIcon className="animate-spin" /> : <PlayIcon />}
            {pending ? "Starting..." : active ? "Running" : "Run AI analysis"}
          </Button>
          <Link href={MACRO_ANALYSIS_HREF} className={buttonVariants({ variant: "ghost", size: "sm" })}>
            <HistoryIcon /> All analyses
          </Link>
        </div>
      </CardContent>
    </Card>
  )
}
