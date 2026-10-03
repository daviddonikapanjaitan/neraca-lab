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
import type { CompanySummary, Exchange, IngestionJob } from "@/lib/types"
import { cn } from "@/lib/utils"

type Notice = { tone: "success" | "error"; text: string }

/** Queues a daily price ingestion for one company (exchange and ticker chosen from the stored companies). */
export function PriceCard({
  exchanges,
  companiesByExchange,
  provider,
  onSubmitted,
}: {
  exchanges: Exchange[]
  /** companies stored per exchange code, ordered by ticker */
  companiesByExchange: Record<string, CompanySummary[]>
  provider: string
  onSubmitted: (job: IngestionJob) => void
}) {
  const [exchange, setExchange] = useState<string | null>(exchanges[0]?.code ?? null)
  const [ticker, setTicker] = useState<string | null>(null)
  const [full, setFull] = useState(false)
  const [pending, setPending] = useState(false)
  const [notice, setNotice] = useState<Notice | null>(null)

  const source = providerLabel(provider)
  const companies = exchange ? companiesByExchange[exchange] ?? [] : []
  const company = companies.find((c) => c.ticker === ticker)
  const exchangeItems = exchanges.map((e) => ({ value: e.code, label: `${e.code} · ${e.name}` }))
  const tickerItems = companies.map((c) => ({ value: c.ticker, label: `${c.ticker} · ${c.companyName}` }))

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
      const { status, body: job } = await requestJson<IngestionJob>("/api/prices/ingestions", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ exchange, ticker, full }),
      })
      setNotice({
        tone: "success",
        text:
          status === 202
            ? `Queued the ${full ? "full price history" : "daily prices"} of ${ticker} from ${source}.`
            : `A price ingestion for ${ticker} is already queued or running. Follow it in the table below.`,
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
          Fetches daily prices (open, high, low, close, adjusted close, volume) and recalculates market cap and
          valuation ratios.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <ul className="list-disc space-y-1 pl-4 text-xs text-muted-foreground">
          <li>Only new trading days since the latest stored day are fetched. The first run loads the full history.</li>
          <li>
            The ticker list shows the companies already stored. Upload a financial statement first to add a
            company.
          </li>
          <li>Runs in the background, one company at a time, with a short pause between requests.</li>
        </ul>

        <div className="grid gap-2 sm:grid-cols-2">
          <div className="flex flex-col gap-1.5">
            <span className="text-xs font-medium text-muted-foreground">Exchange</span>
            <Select items={exchangeItems} value={exchange} onValueChange={changeExchange}>
              <SelectTrigger aria-label="Exchange" className="w-full" disabled={pending}>
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
            <span className="text-xs font-medium text-muted-foreground">Ticker</span>
            <Select
              items={tickerItems}
              value={ticker}
              onValueChange={(value) => {
                setTicker(value)
                setNotice(null)
              }}
            >
              <SelectTrigger aria-label="Ticker" className="w-full" disabled={pending || companies.length === 0}>
                <SelectValue placeholder={companies.length === 0 ? "No companies stored" : "Choose a ticker"} />
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
