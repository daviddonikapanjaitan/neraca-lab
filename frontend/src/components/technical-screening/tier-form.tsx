"use client"

import { useState } from "react"
import Link from "next/link"
import { useRouter } from "next/navigation"
import { ActivityIcon, CircleAlertIcon, DatabaseIcon, LoaderIcon, PlayIcon } from "lucide-react"

import { AgentPicker } from "@/components/technical-screening/agent-picker"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { formatDate } from "@/lib/format"
import { jsonBody, requestJson } from "@/lib/client-api"
import { formatUsd } from "@/lib/screening"
import {
  technicalReportHref,
  type TechnicalAgentCode,
  type TechnicalScreeningOptions,
  type TechnicalScreeningRun,
} from "@/lib/technical-screening"
import type { MarketCapTier } from "@/lib/types"
import { cn } from "@/lib/utils"

/**
 * Starts a technical screening of a market-cap tier (Large, Mid or Small cap, as Fundamental (All)): how many stocks to
 * keep (top N) and which technical agents read the charts. The run is queued and the report page follows it.
 */
export function TechnicalTierForm({ options }: { options: TechnicalScreeningOptions }) {
  const router = useRouter()
  const [exchange, setExchange] = useState<string | null>(options.exchanges[0]?.code ?? null)
  const [tier, setTier] = useState<MarketCapTier>("LARGE")
  const [topN, setTopN] = useState(String(options.defaultTopN))
  const [agents, setAgents] = useState<TechnicalAgentCode[]>(options.agents.map((a) => a.code as TechnicalAgentCode))
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const n = Number(topN)
  const validTopN = Number.isInteger(n) && n >= 1 && n <= options.maxTopN
  const shortlist = validTopN ? Math.min(options.maxShortlist, Math.max(n, n * options.shortlistMultiplier)) : null
  const exchangeItems = options.exchanges.map((e) => ({ value: e.code, label: `${e.code} · ${e.label}` }))
  const tierItems = options.marketCapTiers.map((t) => ({ value: t.code, label: t.label }))
  const tierInfo = options.marketCapTiers.find((t) => t.code === tier)
  const canSubmit = !!exchange && validTopN && agents.length > 0 && !pending
  const data = options.data

  async function submit() {
    if (!canSubmit) return
    setPending(true)
    setError(null)
    try {
      const { body: run } = await requestJson<TechnicalScreeningRun>(
        "/api/technical-screenings",
        jsonBody("POST", { exchange, marketCapTier: tier, topN: n, agents })
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
          <ActivityIcon className="size-4 text-primary" />
          New technical screening
        </CardTitle>
        <CardDescription>
          Technical analysis only: price movements, moving averages and indicators. Stage 1 reads every listing of the
          tier with the rules of Companies › Technicals (no AI); the best {options.shortlistMultiplier}× top N go to the
          AI agents, which read each chart in their trader&apos;s style, give a stance and a trade plan and review their
          own answers. A final synthesis ranks the top N with where to buy, sell and stop the loss.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div className="grid gap-3 sm:grid-cols-3">
          <div className="flex flex-col gap-1.5">
            <span className="text-xs font-medium text-muted-foreground">Stock exchange</span>
            <Select items={exchangeItems} value={exchange} onValueChange={(v) => v && setExchange(v)}>
              <SelectTrigger aria-label="Stock exchange" className="w-full" disabled={pending}>
                <SelectValue placeholder="Choose an exchange" />
              </SelectTrigger>
              <SelectContent>
                {exchangeItems.map((e) => (
                  <SelectItem key={e.value} value={e.value}>
                    {e.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="flex flex-col gap-1.5">
            <span className="text-xs font-medium text-muted-foreground">Market cap</span>
            <Select items={tierItems} value={tier} onValueChange={(v) => v && setTier(v as MarketCapTier)}>
              <SelectTrigger aria-label="Market cap" className="w-full" disabled={pending}>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {options.marketCapTiers.map((t) => (
                  <SelectItem key={t.code} value={t.code}>
                    {t.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            {tierInfo && <span className="text-[11px] text-muted-foreground">{tierInfo.description}</span>}
          </div>
          <div className="flex flex-col gap-1.5">
            <label htmlFor="technical-top-n" className="text-xs font-medium text-muted-foreground">
              Top stocks to keep
            </label>
            <Input
              id="technical-top-n"
              type="number"
              inputMode="numeric"
              min={1}
              max={options.maxTopN}
              value={topN}
              onChange={(e) => setTopN(e.target.value)}
              disabled={pending}
              aria-invalid={!validTopN}
            />
            <span className={cn("text-[11px]", validTopN ? "text-muted-foreground" : "text-destructive")}>
              {validTopN
                ? `Shortlist of up to ${shortlist} stocks for the AI agents`
                : `Enter a whole number from 1 to ${options.maxTopN}`}
            </span>
          </div>
        </div>

        <AgentPicker agents={options.agents} selected={agents} onChange={(a) => { setAgents(a); setError(null) }} disabled={pending} />

        <div className="flex flex-col gap-1 rounded-lg bg-muted/50 p-2.5 text-xs text-muted-foreground">
          <span className="flex items-center gap-1.5">
            <DatabaseIcon className="size-3.5" />
            {data.withBars > 0
              ? `${data.withBars} of ${data.listings} ${data.exchange} listings with daily price history (up to ${formatDate(
                  data.latestBarDate
                )}); market data of ${formatDate(data.marketDataDate)}.`
              : `No ${data.exchange} price history stored yet.`}
          </span>
          <span>
            The run loads today&apos;s market data and the daily bars of the tier&apos;s listings that are behind from Yahoo
            Finance first ({data.historyYears} years for a listing loaded the first time; about 1.5 s each
            {data.withBars === 0 ? ", so the first run takes several minutes" : ""}). Load every listing at once on{" "}
            <Link href="/ingestion/screening-data" className="underline underline-offset-2 hover:text-foreground">
              Ingestion › Screening Data IDX
            </Link>
            . Cost cap per run: {formatUsd(options.budgetUsd)}; {options.agentModel} analyses, {options.synthesisModel}{" "}
            writes the synthesis.
          </span>
        </div>

        {error && (
          <p role="alert" className="flex items-start gap-2 text-xs text-destructive">
            <CircleAlertIcon className="mt-px size-3.5 shrink-0" />
            <span>{error}</span>
          </p>
        )}

        <Button onClick={submit} disabled={!canSubmit} className="self-start">
          {pending ? <LoaderIcon className="animate-spin" /> : <PlayIcon />}
          {pending ? "Starting..." : "Start technical screening"}
        </Button>
      </CardContent>
    </Card>
  )
}
