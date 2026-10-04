"use client"

import { useState } from "react"
import { useRouter } from "next/navigation"
import { CircleAlertIcon, DatabaseIcon, LoaderIcon, PlayIcon, ScanSearchIcon } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
import { Input } from "@/components/ui/input"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { formatDate } from "@/lib/format"
import { jsonBody, requestJson } from "@/lib/client-api"
import { formatUsd } from "@/lib/screening"
import type { InvestorAgentCode, MarketCapTier, ScreeningOptions, ScreeningRun } from "@/lib/types"
import { cn } from "@/lib/utils"

/**
 * Starts a screening: exchange (IDX for now), market-cap tier, how many stocks to keep (top N) and
 * which investor agents analyse them (multi-select). The run is queued and the report page follows it.
 */
export function ScreeningForm({ options }: { options: ScreeningOptions }) {
  const router = useRouter()
  const [exchange, setExchange] = useState<string | null>(options.exchanges[0]?.code ?? null)
  const [tier, setTier] = useState<MarketCapTier>("LARGE")
  const [topN, setTopN] = useState(String(options.defaultTopN))
  const [agents, setAgents] = useState<InvestorAgentCode[]>(
    options.agents.map((a) => a.code as InvestorAgentCode)
  )
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const n = Number(topN)
  const validTopN = Number.isInteger(n) && n >= 1 && n <= options.maxTopN
  const shortlist = validTopN ? Math.min(options.maxShortlist, Math.max(n, n * options.shortlistMultiplier)) : null
  const exchangeItems = options.exchanges.map((e) => ({ value: e.code, label: `${e.code} · ${e.label}` }))
  const tierItems = options.marketCapTiers.map((t) => ({ value: t.code, label: t.label }))
  const tierInfo = options.marketCapTiers.find((t) => t.code === tier)
  const canSubmit = !!exchange && validTopN && agents.length > 0 && !pending

  function toggle(code: InvestorAgentCode, checked: boolean) {
    setAgents((current) =>
      checked ? options.agents.map((a) => a.code as InvestorAgentCode).filter((c) => c === code || current.includes(c))
        : current.filter((c) => c !== code)
    )
    setError(null)
  }

  async function submit() {
    if (!canSubmit) return
    setPending(true)
    setError(null)
    try {
      const { body: run } = await requestJson<ScreeningRun>(
        "/api/screenings",
        jsonBody("POST", { exchange, marketCapTier: tier, topN: n, agents })
      )
      router.push(`/screening/${run.id}`)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      setPending(false)
    }
  }

  const data = options.data

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <ScanSearchIcon className="size-4 text-primary" />
          New screening
        </CardTitle>
        <CardDescription>
          Stage 1 filters and scores every listing in Java (no AI). The best {options.shortlistMultiplier}× top N go to
          the AI agents, which read the news, score each stock from their investor&apos;s perspective and review their
          own answers. A final synthesis ranks the top N.
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
            <label htmlFor="screening-top-n" className="text-xs font-medium text-muted-foreground">
              Top stocks to keep
            </label>
            <Input
              id="screening-top-n"
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

        <fieldset className="flex flex-col gap-2" disabled={pending}>
          <div className="flex items-center justify-between gap-2">
            <legend className="text-xs font-medium text-muted-foreground">
              Investor agents ({agents.length} of {options.agents.length} selected)
            </legend>
            <div className="flex gap-1">
              <Button
                type="button"
                variant="ghost"
                size="xs"
                onClick={() => setAgents(options.agents.map((a) => a.code as InvestorAgentCode))}
              >
                Select all
              </Button>
              <Button type="button" variant="ghost" size="xs" onClick={() => setAgents([])}>
                Clear
              </Button>
            </div>
          </div>
          <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
            {options.agents.map((agent) => {
              const code = agent.code as InvestorAgentCode
              const checked = agents.includes(code)
              return (
                <label
                  key={code}
                  className={cn(
                    "flex cursor-pointer items-start gap-2.5 rounded-lg border p-2.5 text-sm transition-colors",
                    checked ? "border-primary/50 bg-primary/5" : "hover:bg-muted/50"
                  )}
                >
                  <Checkbox
                    checked={checked}
                    onCheckedChange={(value) => toggle(code, value)}
                    className="mt-0.5"
                    aria-label={agent.label}
                  />
                  <span className="min-w-0">
                    <span className="block font-medium">{agent.label}</span>
                    <span className="block text-xs text-muted-foreground">{agent.description}</span>
                  </span>
                </label>
              )
            })}
          </div>
          {agents.length === 0 && <span className="text-xs text-destructive">Choose at least one agent.</span>}
        </fieldset>

        <div className="flex flex-col gap-1 rounded-lg bg-muted/50 p-2.5 text-xs text-muted-foreground">
          <span className="flex items-center gap-1.5">
            <DatabaseIcon className="size-3.5" />
            {data.listings > 0
              ? `${data.listings} ${data.exchange} listings stored (market data of ${formatDate(
                  data.latestSnapshotDate
                )}), ${data.withFundamentals} with fundamentals.`
              : `No ${data.exchange} screening data stored yet.`}
          </span>
          <span>
            The run refreshes today&apos;s market data and the fundamentals it needs from Yahoo Finance first
            {data.withFundamentals === 0 ? " (the first run takes longer)" : ""}. Cost cap per run:{" "}
            {formatUsd(options.budgetUsd)}; DeepSeek analyses, Opus writes the synthesis.
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
          {pending ? "Starting..." : "Start screening"}
        </Button>
      </CardContent>
    </Card>
  )
}
