"use client"

import { useMemo, useState } from "react"
import { useRouter } from "next/navigation"
import { BrainCircuitIcon, CircleAlertIcon, LoaderIcon, PlayIcon, SearchIcon, XIcon } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
import { Input } from "@/components/ui/input"
import { jsonBody, RequestError, requestJson } from "@/lib/client-api"
import { EMPTY, formatNumber, formatPercent } from "@/lib/format"
import {
  analysisReportHref,
  type AnalysisMode,
  type AnalysisOptions,
  type AnalysisRun,
  type AnalysisSteps,
} from "@/lib/portfolio-analysis"
import { formatUsd } from "@/lib/screening"
import { cn } from "@/lib/utils"

/** A typed amount: digits with an optional decimal point; commas are thousands separators. Empty = 0. */
function parseAmount(text: string): number | null {
  const t = text.trim().replace(/,/g, "")
  if (t === "") return 0
  if (!/^\d+(\.\d+)?$/.test(t)) return null
  const n = Number(t)
  return Number.isFinite(n) ? n : null
}

function score(v: number | null): string {
  return v === null ? EMPTY : formatNumber(v, 1)
}

/**
 * Starts a Portfolio › Analysis: the most stocks to hold, the stocks to choose from (every company of Companies ›
 * Fundamentals, or the ones selected here), money to add. The run follows the instruction's order: step 1 the
 * Fundamental (Score), step 2 the Technical (Score) of step 1's stocks, then the AI agents and the synthesis.
 */
export function AnalysisForm({ options, canStart }: { options: AnalysisOptions; canStart: boolean }) {
  const router = useRouter()
  const [mode, setMode] = useState<AnalysisMode>("BULK")
  const [steps, setSteps] = useState<AnalysisSteps>("FUNDAMENTAL_TECHNICAL")
  const technical = steps === "FUNDAMENTAL_TECHNICAL"
  const [maxText, setMaxText] = useState(String(options.defaultMaxStocks))
  const [addedText, setAddedText] = useState("")
  const [tickers, setTickers] = useState<string[]>([])
  const [query, setQuery] = useState("")
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const byTicker = useMemo(() => new Map(options.companies.map((c) => [c.ticker, c])), [options.companies])
  const shown = useMemo(() => {
    const q = query.trim().toLowerCase()
    const list = q
      ? options.companies.filter(
          (c) =>
            c.ticker.toLowerCase().includes(q) ||
            c.companyName.toLowerCase().includes(q) ||
            (c.sector ?? "").toLowerCase().includes(q)
        )
      : options.companies
    // best Fundamental (Score) first: the order of step 1
    return [...list].sort((a, b) => (b.fundamentalScore ?? -1) - (a.fundamentalScore ?? -1) || a.ticker.localeCompare(b.ticker))
  }, [options.companies, query])

  const max = Number(maxText)
  const validMax = Number.isInteger(max) && max >= 1 && max <= options.maxStocksLimit
  const added = parseAmount(addedText)
  const full = tickers.length >= options.maxSelected
  const fundamentalKeep = validMax ? Math.min(max * options.fundamentalMultiplier, options.maxFundamentalShortlist) : null
  const technicalKeep = validMax ? Math.min(max * options.technicalMultiplier, options.maxTechnicalShortlist) : null
  const capital = options.portfolio.total + (added ?? 0)
  const canSubmit = canStart && validMax && added !== null && (mode === "BULK" || tickers.length > 0) && !pending
    && capital > 0

  function select(ticker: string, checked: boolean) {
    setTickers((current) => {
      if (!checked) return current.filter((t) => t !== ticker)
      if (current.includes(ticker) || current.length >= options.maxSelected) return current
      return [...current, ticker]
    })
    setError(null)
  }

  function selectShown() {
    setTickers((current) => {
      const next = [...current]
      for (const c of shown) {
        if (next.length >= options.maxSelected) break
        if (!next.includes(c.ticker)) next.push(c.ticker)
      }
      return next
    })
  }

  async function submit() {
    if (!canSubmit) return
    setPending(true)
    setError(null)
    try {
      const { body } = await requestJson<AnalysisRun>(
        "/api/portfolio/analyses",
        jsonBody("POST", {
          exchange: options.exchange,
          mode,
          steps,
          maxStocks: max,
          tickers: mode === "SELECTED" ? tickers : null,
          addedCapital: added ?? 0,
        })
      )
      router.push(analysisReportHref(body.id))
    } catch (e) {
      setError(e instanceof RequestError || e instanceof Error ? e.message : String(e))
      setPending(false)
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <BrainCircuitIcon className="size-4 text-primary" />
          New portfolio analysis
        </CardTitle>
        <CardDescription>
          {technical
            ? <>Step 1 keeps the stocks with the best Fundamental (Score), step 2 keeps, of those, the best Technical
                (Score) - the two scores are never combined. Then the fundamental agent ({options.agentModel}) judges
                each business, the technical agent judges the timing of the ones it passed, and {options.synthesisModel}{" "}
                builds the portfolio: sell, decrease, hold, increase, buy and the cash to keep.</>
            : <>Fundamental analysis only: step 1 keeps the stocks with the best Fundamental (Score), the fundamental
                agent ({options.agentModel}) judges each business, and {options.synthesisModel} builds the portfolio:
                sell, decrease, hold, increase, buy and the cash to keep. No chart or timing is read.</>}{" "}
          Your holdings are always analysed.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div className="grid gap-3 sm:grid-cols-3">
          <div className="flex flex-col gap-1.5">
            <label htmlFor="analysis-max" className="text-xs font-medium text-muted-foreground">
              Most stocks to hold
            </label>
            <Input
              id="analysis-max"
              type="number"
              inputMode="numeric"
              min={1}
              max={options.maxStocksLimit}
              value={maxText}
              onChange={(e) => setMaxText(e.target.value)}
              disabled={pending}
              aria-invalid={!validMax}
            />
            <span className={cn("text-[11px]", validMax ? "text-muted-foreground" : "text-destructive")}>
              {!validMax
                ? `A whole number from 1 to ${options.maxStocksLimit}`
                : technical
                  ? `Step 1 keeps ${fundamentalKeep}, step 2 keeps ${technicalKeep}; the AI optimizes up to ${max} stocks`
                  : `Step 1 keeps ${fundamentalKeep}; the AI optimizes up to ${max} stocks`}
            </span>
          </div>
          <div className="flex flex-col gap-1.5">
            <label htmlFor="analysis-added" className="text-xs font-medium text-muted-foreground">
              Capital to add (Rp, optional)
            </label>
            <Input
              id="analysis-added"
              inputMode="decimal"
              autoComplete="off"
              placeholder="0"
              value={addedText}
              onChange={(e) => setAddedText(e.target.value)}
              disabled={pending}
              aria-invalid={added === null}
            />
            <span className={cn("text-[11px]", added === null ? "text-destructive" : "text-muted-foreground")}>
              {added === null ? "Enter an amount, e.g. 10,000,000" : "Invested with the cash you hold"}
            </span>
          </div>
          <div className="flex flex-col gap-1.5">
            <span className="text-xs font-medium text-muted-foreground">Your capital</span>
            <div className="rounded-md border px-3 py-1.5 text-sm tabular-nums">
              Rp {formatNumber(capital, 0)}
            </div>
            <span className="text-[11px] text-muted-foreground">
              Market value Rp {formatNumber(options.portfolio.marketValue, 0)} + cash Rp{" "}
              {formatNumber(options.portfolio.cash, 0)}
              {added ? ` + Rp ${formatNumber(added, 0)}` : ""} · {options.portfolio.positions} stocks held
            </span>
          </div>
        </div>

        <fieldset className="flex flex-col gap-2" disabled={pending}>
          <legend className="mb-1 text-xs font-medium text-muted-foreground">Analysis steps</legend>
          <div role="radiogroup" aria-label="Analysis steps" className="grid gap-2 sm:grid-cols-2">
            {(
              [
                {
                  value: "FUNDAMENTAL_TECHNICAL" as const,
                  title: "1. Fundamental, then 2. Technical",
                  text: "The best Fundamental (Score) first, then the best Technical (Score) of those; the fundamental agent, then the technical agent (timing, entry, stop loss, target)",
                },
                {
                  value: "FUNDAMENTAL" as const,
                  title: "Fundamental only",
                  text: "The best Fundamental (Score) and the fundamental agent only: a fundamental PASS is enough to buy; no chart, timing or trade plan",
                },
              ]
            ).map((o) => (
              <button
                key={o.value}
                type="button"
                role="radio"
                aria-checked={steps === o.value}
                onClick={() => setSteps(o.value)}
                className={cn(
                  "rounded-lg border p-3 text-left transition-colors",
                  steps === o.value ? "border-primary bg-primary/5" : "hover:bg-muted/50"
                )}
              >
                <span className="block text-sm font-medium">{o.title}</span>
                <span className="block text-xs text-muted-foreground">{o.text}</span>
              </button>
            ))}
          </div>
        </fieldset>

        <fieldset className="flex flex-col gap-2" disabled={pending}>
          <legend className="mb-1 text-xs font-medium text-muted-foreground">Stocks to choose from</legend>
          <div role="radiogroup" aria-label="Stocks to choose from" className="grid gap-2 sm:grid-cols-2">
            {(
              [
                {
                  value: "BULK" as const,
                  title: "All companies (bulk)",
                  text: technical
                    ? `Every company of Companies › Fundamentals (${options.scores.fundamentalScored} with a Fundamental (Score), ${options.scores.technicalScored} with a Technical (Score))`
                    : `Every company of Companies › Fundamentals (${options.scores.fundamentalScored} with a Fundamental (Score))`,
                },
                {
                  value: "SELECTED" as const,
                  title: "Selected stocks",
                  text: `Only the stocks you choose below (at most ${options.maxSelected})`,
                },
              ]
            ).map((o) => (
              <button
                key={o.value}
                type="button"
                role="radio"
                aria-checked={mode === o.value}
                onClick={() => setMode(o.value)}
                className={cn(
                  "rounded-lg border p-3 text-left transition-colors",
                  mode === o.value ? "border-primary bg-primary/5" : "hover:bg-muted/50"
                )}
              >
                <span className="block text-sm font-medium">{o.title}</span>
                <span className="block text-xs text-muted-foreground">{o.text}</span>
              </button>
            ))}
          </div>

          {mode === "SELECTED" && (
            <div className="flex flex-col gap-2">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span className="text-xs text-muted-foreground">
                  {tickers.length} selected, at most {options.maxSelected}; sorted by Fundamental (Score)
                </span>
                <div className="flex gap-1">
                  <Button type="button" variant="ghost" size="xs" onClick={selectShown} disabled={shown.length === 0 || full}>
                    {query.trim() ? "Select shown" : "Select all"}
                  </Button>
                  <Button type="button" variant="ghost" size="xs" onClick={() => setTickers([])} disabled={tickers.length === 0}>
                    Clear
                  </Button>
                </div>
              </div>
              {tickers.length > 0 && (
                <div className="flex flex-wrap gap-1.5">
                  {tickers.map((t) => (
                    <span
                      key={t}
                      className="inline-flex items-center gap-1 rounded-md border border-primary/40 bg-primary/5 py-0.5 pr-1 pl-2 text-xs font-medium"
                      title={byTicker.get(t)?.companyName}
                    >
                      {t}
                      <button
                        type="button"
                        onClick={() => select(t, false)}
                        className="rounded-sm p-0.5 text-muted-foreground hover:bg-muted hover:text-foreground"
                        aria-label={`Remove ${t}`}
                      >
                        <XIcon className="size-3" />
                      </button>
                    </span>
                  ))}
                </div>
              )}
              <div className="relative">
                <SearchIcon className="pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2 text-muted-foreground" />
                <Input
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                  placeholder="Search ticker, company or sector"
                  aria-label="Search stocks"
                  className="pl-8"
                />
              </div>
              <div className="max-h-80 overflow-y-auto rounded-lg border">
                {shown.length === 0 ? (
                  <p className="p-4 text-center text-xs text-muted-foreground">No stock matches “{query.trim()}”.</p>
                ) : (
                  <ul className="divide-y">
                    {shown.map((c) => {
                      const checked = tickers.includes(c.ticker)
                      return (
                        <li key={c.ticker}>
                          <label
                            className={cn(
                              "flex cursor-pointer items-center gap-2.5 px-3 py-2 text-sm transition-colors",
                              checked ? "bg-primary/5" : "hover:bg-muted/50",
                              !checked && full && "cursor-not-allowed opacity-60"
                            )}
                          >
                            <Checkbox
                              checked={checked}
                              onCheckedChange={(value) => select(c.ticker, value)}
                              disabled={!checked && full}
                              aria-label={`${c.ticker} · ${c.companyName}`}
                            />
                            <span className="w-14 shrink-0 font-medium">{c.ticker}</span>
                            <span className="min-w-0 flex-1">
                              <span className="block truncate">
                                {c.companyName}
                                {c.held && <span className="ml-1.5 text-xs text-primary">held</span>}
                              </span>
                              {c.sector && <span className="block truncate text-xs text-muted-foreground">{c.sector}</span>}
                            </span>
                            <span className="shrink-0 text-right text-xs tabular-nums">
                              <span className="block">F {score(c.fundamentalScore)}</span>
                              <span className="block text-muted-foreground">T {score(c.technicalScore)}</span>
                            </span>
                          </label>
                        </li>
                      )
                    })}
                  </ul>
                )}
              </div>
            </div>
          )}
        </fieldset>

        <div className="flex flex-col gap-1 rounded-lg bg-muted/50 p-2.5 text-xs text-muted-foreground">
          <span>
            Limits: at most {formatPercent(options.maxPositionWeight, 0)} of the capital in one stock and{" "}
            {formatPercent(options.maxSectorWeight, 0)} in one sector; each idea sized at{" "}
            {options.kellyFraction === 0.5 ? "half" : formatPercent(options.kellyFraction, 0)} of its Kelly fraction
            (win probability from the fundamental step, payoff{" "}
            {technical ? "from the technical plan" : "assumed even without a technical plan"}); buys in lots of{" "}
            {options.lotSize} shares; Fundamental (Score) at least {formatNumber(options.minFundamentalScore, 0)} at
            step 1.
          </span>
          <span>Cost cap per run: {formatUsd(options.budgetUsd)}. A run takes a few minutes; its report is saved.</span>
          {!canStart && (
            <span className="text-amber-700 dark:text-amber-400">
              Starting a run needs the SCREENING permission too (the AI models); ask an administrator.
            </span>
          )}
          {capital <= 0 && (
            <span className="text-amber-700 dark:text-amber-400">
              Nothing to invest: set your cash on Portfolio › Position, add a position or add capital.
            </span>
          )}
        </div>

        {error && (
          <p role="alert" className="flex items-start gap-2 text-xs text-destructive">
            <CircleAlertIcon className="mt-px size-3.5 shrink-0" />
            <span>{error}</span>
          </p>
        )}

        <Button onClick={submit} disabled={!canSubmit} className="self-start">
          {pending ? <LoaderIcon className="animate-spin" /> : <PlayIcon />}
          {pending ? "Starting..." : mode === "BULK" ? "Analyse with all companies" : `Analyse with ${tickers.length} selected`}
        </Button>
      </CardContent>
    </Card>
  )
}
