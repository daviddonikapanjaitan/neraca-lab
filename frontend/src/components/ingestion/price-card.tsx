"use client"

import { useState } from "react"
import { CircleAlertIcon, CircleCheckIcon, LineChartIcon, LoaderIcon, RefreshCwIcon } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { formatDate } from "@/lib/format"
import { requestJson } from "@/lib/client-api"
import { providerLabel } from "@/lib/ingestion"
import type { CompanySummary, Exchange, IngestionJob, MarketStatus } from "@/lib/types"
import { cn } from "@/lib/utils"

type Notice = { tone: "success" | "error"; text: string }

/**
 * Queues a price ingestion of one company (an exchange and a ticker from the stored companies), or of one Forex pair or
 * commodity (the market Forex or Commodities: the second dropdown becomes Currency or Commodity).
 */
export function PriceCard({
  exchanges,
  companiesByExchange,
  markets,
  provider,
  onSubmitted,
}: {
  exchanges: Exchange[]
  /** companies stored per exchange code, ordered by ticker */
  companiesByExchange: Record<string, CompanySummary[]>
  /** Forex and Commodities with their instruments */
  markets: MarketStatus[]
  provider: string
  onSubmitted: (job: IngestionJob) => void
}) {
  const [exchange, setExchange] = useState<string | null>(exchanges[0]?.code ?? markets[0]?.market ?? null)
  const [ticker, setTicker] = useState<string | null>(null)
  const [full, setFull] = useState(false)
  const [pending, setPending] = useState(false)
  const [notice, setNotice] = useState<Notice | null>(null)

  const market = markets.find((m) => m.market === exchange) ?? null
  const companies = exchange && !market ? companiesByExchange[exchange] ?? [] : []
  const company = companies.find((c) => c.ticker === ticker)
  const instrument = market?.items.find((i) => i.code === ticker) ?? null
  const source = market ? providerLabel(instrument?.source ?? "yahoo") : providerLabel(provider)
  const exchangeItems = [
    ...exchanges.map((e) => ({ value: e.code, label: `${e.code} · ${e.name}` })),
    ...markets.map((m) => ({ value: m.market, label: m.name })),
  ]
  const tickerItems = market
    ? market.items.map((i) => ({ value: i.code, label: `${i.name} · ${i.unit}` }))
    : companies.map((c) => ({ value: c.ticker, label: `${c.ticker} · ${c.companyName}` }))
  const itemLabel = market ? market.itemLabel : "Ticker"
  const empty = market ? `No ${market.itemsLabel.toLowerCase()} configured` : "No companies stored"

  function changeExchange(code: string | null) {
    if (!code || code === exchange) return
    setExchange(code)
    setTicker(null)
    setNotice(null)
  }

  async function submit() {
    if (!exchange || !ticker || pending) return
    setPending(true)
    setNotice(null)
    try {
      const { status, body: job } = market
        ? await requestJson<IngestionJob>(`/api/markets/${market.market}/ingestions`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ code: ticker, full }),
          })
        : await requestJson<IngestionJob>("/api/prices/ingestions", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ exchange, ticker, full }),
          })
      const name = instrument?.name ?? ticker
      setNotice({
        tone: "success",
        text:
          status === 202
            ? `Queued the ${full ? "full price history" : "latest prices"} of ${name} from ${source}.`
            : `A price ingestion for ${name} is already queued or running. Follow it in the table below.`,
      })
      onSubmitted(job)
    } catch (error) {
      setNotice({ tone: "error", text: error instanceof Error ? error.message : String(error) })
    } finally {
      setPending(false)
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <LineChartIcon className="size-4 text-primary" />
          Price ingestion · {source}
        </CardTitle>
        <CardDescription>
          {market
            ? `Fetches the ${market.name === "Forex" ? "exchange rates" : "prices"} of one ${market.itemLabel.toLowerCase()} (open, high, low, close) for its chart on Companies › Technicals and the macro view.`
            : "Fetches daily prices (open, high, low, close, adjusted close, volume) and recalculates market cap and valuation ratios."}
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <ul className="list-disc space-y-1 pl-4 text-xs text-muted-foreground">
          {market ? (
            <>
              <li>Only the days after the latest stored day are fetched. The first run loads the full history.</li>
              <li>
                Daily bars up to the last completed day (today&apos;s bar is still forming). Coal, LNG, nickel, tin,
                rubber and the fertilizers are the World Bank monthly average, cobalt the IMF&apos;s and wood pulp the US
                producer price index (FRED), one bar per month; palm oil in ringgit is CME CPO=F x USD/MYR (an
                approximation of Bursa Malaysia FCPO).
              </li>
              <li>Bad prints of the source (a price that jumps away and straight back) are not stored.</li>
            </>
          ) : (
            <>
              <li>Only new trading days since the latest stored day are fetched. The first run loads the full history.</li>
              <li>
                The ticker list shows the companies already stored. Upload a financial statement first to add a
                company.
              </li>
              <li>Runs in the background, one company at a time, with a short pause between requests.</li>
            </>
          )}
        </ul>

        <div className="grid gap-2 sm:grid-cols-2">
          <div className="flex flex-col gap-1.5">
            <span className="text-xs font-medium text-muted-foreground">Exchange or market</span>
            <Select items={exchangeItems} value={exchange} onValueChange={changeExchange}>
              <SelectTrigger aria-label="Exchange or market" className="w-full" disabled={pending}>
                <SelectValue placeholder="Choose an exchange or market" />
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
            <span className="text-xs font-medium text-muted-foreground">{itemLabel}</span>
            <Select
              items={tickerItems}
              value={ticker}
              onValueChange={(value) => {
                setTicker(value)
                setNotice(null)
              }}
            >
              <SelectTrigger aria-label={itemLabel} className="w-full" disabled={pending || tickerItems.length === 0}>
                <SelectValue placeholder={tickerItems.length === 0 ? empty : `Choose a ${itemLabel.toLowerCase()}`} />
              </SelectTrigger>
              <SelectContent>
                {tickerItems.map((t) => (
                  <SelectItem key={t.value} value={t.value}>
                    {t.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        </div>

        {company && (
          <p className="text-xs text-muted-foreground">
            {company.latestPriceDate
              ? `Prices stored up to ${formatDate(company.latestPriceDate)}.`
              : "No prices stored yet: the full history will be loaded."}
          </p>
        )}
        {instrument && (
          <p className="text-xs text-muted-foreground">
            {instrument.lastDate
              ? `${instrument.rows.toLocaleString("en-US")} ${instrument.frequency === "MONTHLY" ? "months" : "days"} stored, ${formatDate(instrument.firstDate)} to ${formatDate(instrument.lastDate)}${instrument.upToDate ? " (up to date)" : ""}.`
              : "No prices stored yet: the full history will be loaded."}{" "}
            Source: {providerLabel(instrument.source)} ({instrument.symbol.replace("*", " x ")}).
          </p>
        )}

        <label className="flex items-start gap-2 text-sm">
          <input
            type="checkbox"
            checked={full}
            onChange={(e) => setFull(e.target.checked)}
            disabled={pending}
            className="mt-0.5 size-4 accent-primary"
          />
          <span>
            Re-fetch the full history
            <span className="block text-xs text-muted-foreground">
              Corrects gaps or wrong values in older days. Not needed for a normal daily update.
            </span>
          </span>
        </label>

        {notice && (
          <p
            role={notice.tone === "error" ? "alert" : "status"}
            className={cn(
              "flex items-start gap-2 text-xs",
              notice.tone === "error" ? "text-destructive" : "text-emerald-600 dark:text-emerald-400"
            )}
          >
            {notice.tone === "error" ? (
              <CircleAlertIcon className="mt-px size-3.5 shrink-0" />
            ) : (
              <CircleCheckIcon className="mt-px size-3.5 shrink-0" />
            )}
            <span>{notice.text}</span>
          </p>
        )}

        <Button onClick={submit} disabled={!exchange || !ticker || pending} className="self-start">
          {pending ? <LoaderIcon className="animate-spin" /> : <RefreshCwIcon />}
          {pending ? "Queuing..." : "Fetch prices"}
        </Button>
      </CardContent>
    </Card>
  )
}
