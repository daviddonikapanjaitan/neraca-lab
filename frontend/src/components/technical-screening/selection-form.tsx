"use client"

import { useMemo, useState } from "react"
import Link from "next/link"
import { useRouter } from "next/navigation"
import { CircleAlertIcon, DatabaseIcon, ListChecksIcon, LoaderIcon, PlayIcon, SearchIcon, XIcon } from "lucide-react"

import { AgentPicker } from "@/components/technical-screening/agent-picker"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
import { Input } from "@/components/ui/input"
import { formatCompact, formatDate } from "@/lib/format"
import { jsonBody, requestJson } from "@/lib/client-api"
import { formatUsd } from "@/lib/screening"
import {
  technicalReportHref,
  type TechnicalAgentCode,
  type TechnicalScreeningOptions,
  type TechnicalScreeningRun,
  type TechnicalSelectableCompanies,
} from "@/lib/technical-screening"
import { cn } from "@/lib/utils"

/**
 * Starts a technical screening of stocks chosen from the companies table, read from the daily prices already stored in
 * the database (as Companies › Technicals reads them): the stocks (multi-select, searchable), how many to keep (top N,
 * at most the number selected) and which technical agents read the charts.
 */
export function TechnicalSelectionForm({
  options,
  companies,
}: {
  options: TechnicalScreeningOptions
  companies: TechnicalSelectableCompanies
}) {
  const router = useRouter()
  const [tickers, setTickers] = useState<string[]>([])
  const [query, setQuery] = useState("")
  // null: follows the selection (default top N, at most the number selected)
  const [topN, setTopN] = useState<string | null>(null)
  const [agents, setAgents] = useState<TechnicalAgentCode[]>(options.agents.map((a) => a.code as TechnicalAgentCode))
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const exchange = options.exchanges.find((e) => e.code === companies.exchange)
  const byTicker = useMemo(() => new Map(companies.companies.map((c) => [c.ticker, c])), [companies])
  const shown = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return companies.companies
    return companies.companies.filter(
      (c) =>
        c.ticker.toLowerCase().includes(q) ||
        c.companyName.toLowerCase().includes(q) ||
        (c.sector ?? "").toLowerCase().includes(q)
    )
  }, [companies, query])

  const maxTopN = Math.min(options.maxTopN, Math.max(1, tickers.length))
  const topNText = topN ?? String(Math.min(options.defaultTopN, maxTopN))
  const n = Number(topNText)
  const validTopN = Number.isInteger(n) && n >= 1 && n <= maxTopN
  const full = tickers.length >= companies.maxSelected
  const tooShort = tickers.filter((t) => (byTicker.get(t)?.tradingDays ?? 0) < companies.minBars)
  const canSubmit = tickers.length > 0 && validTopN && agents.length > 0 && !pending

  function select(ticker: string, checked: boolean) {
    setTickers((current) => {
      if (!checked) return current.filter((t) => t !== ticker)
      if (current.includes(ticker) || current.length >= companies.maxSelected) return current
      return [...current, ticker]
    })
    setError(null)
  }

  function selectShown() {
    setTickers((current) => {
      const next = [...current]
      for (const c of shown) {
        if (next.length >= companies.maxSelected) break
        if (!next.includes(c.ticker)) next.push(c.ticker)
      }
      return next
    })
    setError(null)
  }

  async function submit() {
    if (!canSubmit) return
    setPending(true)
    setError(null)
    try {
      const { body: run } = await requestJson<TechnicalScreeningRun>(
        "/api/technical-screenings",
        jsonBody("POST", { exchange: companies.exchange, tickers, topN: n, agents })
      )
      router.push(technicalReportHref(run))
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      setPending(false)
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <ListChecksIcon className="size-4 text-primary" />
          New technical screening of selected stocks
        </CardTitle>
        <CardDescription>
          The same steps as Technicals (All) on the stocks you choose, read from the daily prices stored in the database:
          the rules of Companies › Technicals score every chart (no AI), the AI agents read each one in their
          trader&apos;s style with a stance and a trade plan, and a final synthesis ranks the top N with where to buy,
          sell and stop the loss. A stock with thin liquidity, stale prices or auto-rejection days is analysed and
          flagged.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div className="grid gap-3 sm:grid-cols-3">
          <div className="flex flex-col gap-1.5">
            <span className="text-xs font-medium text-muted-foreground">Stock exchange</span>
            <Input
              value={exchange ? `${exchange.code} · ${exchange.label}` : companies.exchange}
              readOnly
              aria-label="Stock exchange"
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label htmlFor="technical-selection-top-n" className="text-xs font-medium text-muted-foreground">
              Top stocks to keep
            </label>
            <Input
              id="technical-selection-top-n"
              type="number"
              inputMode="numeric"
              min={1}
              max={maxTopN}
              value={topNText}
              onChange={(e) => setTopN(e.target.value)}
              disabled={pending}
              aria-invalid={tickers.length > 0 && !validTopN}
            />
            <span
              className={cn(
                "text-[11px]",
                tickers.length === 0 || validTopN ? "text-muted-foreground" : "text-destructive"
              )}
            >
              {tickers.length === 0
                ? "Choose the stocks first"
                : validTopN
                  ? `Final ranking keeps ${n} of the ${tickers.length} selected`
                  : `Enter a whole number from 1 to ${maxTopN}`}
            </span>
          </div>
        </div>

        <fieldset className="flex flex-col gap-2" disabled={pending}>
          <div className="flex flex-wrap items-center justify-between gap-2">
            <legend className="text-xs font-medium text-muted-foreground">
              Stocks ({tickers.length} selected, at most {companies.maxSelected}; {companies.companies.length}{" "}
              {companies.exchange} companies in the companies table)
            </legend>
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
              {tickers.map((ticker) => (
                <span
                  key={ticker}
                  className="inline-flex items-center gap-1 rounded-md border border-primary/40 bg-primary/5 py-0.5 pr-1 pl-2 text-xs font-medium"
                  title={byTicker.get(ticker)?.companyName}
                >
                  {ticker}
                  <button
                    type="button"
                    onClick={() => select(ticker, false)}
                    className="rounded-sm p-0.5 text-muted-foreground hover:bg-muted hover:text-foreground"
                    aria-label={`Remove ${ticker}`}
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
            {companies.companies.length === 0 ? (
              <p className="p-4 text-center text-xs text-muted-foreground">
                No {companies.exchange} companies stored yet (Ingestion &gt; IDX XBRL).
              </p>
            ) : shown.length === 0 ? (
              <p className="p-4 text-center text-xs text-muted-foreground">No stock matches “{query.trim()}”.</p>
            ) : (
              <ul className="divide-y">
                {shown.map((c) => {
                  const checked = tickers.includes(c.ticker)
                  const enough = (c.tradingDays ?? 0) >= companies.minBars
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
                          <span className="block truncate">{c.companyName}</span>
                          {c.sector && <span className="block truncate text-xs text-muted-foreground">{c.sector}</span>}
                        </span>
                        <span className="hidden shrink-0 text-right text-xs sm:block">
                          {enough ? (
                            <>
                              <span className="block tabular-nums">
                                {c.marketCap != null ? formatCompact(c.marketCap, "IDR") : `${c.tradingDays} bars`}
                              </span>
                              <span className="block text-muted-foreground">prices to {formatDate(c.lastDate)}</span>
                            </>
                          ) : (
                            <span className="text-amber-700 dark:text-amber-400">
                              {c.tradingDays ? `Only ${c.tradingDays} daily bars` : "No daily prices stored"}
                            </span>
                          )}
                        </span>
                      </label>
                    </li>
                  )
                })}
              </ul>
            )}
          </div>
          {full && (
            <span className="text-xs text-muted-foreground">The maximum of {companies.maxSelected} stocks is selected.</span>
          )}
        </fieldset>

        <AgentPicker agents={options.agents} selected={agents} onChange={(a) => { setAgents(a); setError(null) }} disabled={pending} />

        <div className="flex flex-col gap-1 rounded-lg bg-muted/50 p-2.5 text-xs text-muted-foreground">
          <span className="flex items-center gap-1.5">
            <DatabaseIcon className="size-3.5" />
            The run reads the daily prices stored for the selected companies; it loads nothing from the internet.
          </span>
          <span>
            A stock needs at least {companies.minBars} daily bars; bring the prices up to today on{" "}
            <Link href="/ingestion/prices" className="underline underline-offset-2 hover:text-foreground">
              Ingestion › Price Ingestion
            </Link>
            . Cost cap per run: {formatUsd(options.budgetUsd)}; {options.agentModel} analyses, {options.synthesisModel}{" "}
            writes the synthesis.
          </span>
          {tooShort.length > 0 && (
            <span className="text-amber-700 dark:text-amber-400">
              Too few daily prices for {tooShort.join(", ")}: {tooShort.length === 1 ? "it is" : "they are"} left out of
              the run.
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
          {pending ? "Starting..." : `Start technical screening${tickers.length > 0 ? ` of ${tickers.length}` : ""}`}
        </Button>
      </CardContent>
    </Card>
  )
}
