"use client"

import { useState } from "react"
import { CalendarCheckIcon, CircleAlertIcon, CircleCheckIcon, LoaderIcon, RefreshCwIcon } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { requestJson } from "@/lib/client-api"
import { formatDate } from "@/lib/format"
import { indexesOf, indexNames } from "@/lib/ingestion"
import type { Exchange, IngestionJob, MarketStatus, MarketUpdate, PriceStatus, PriceUpdate } from "@/lib/types"
import { cn } from "@/lib/utils"

type Notice = { tone: "success" | "error"; text: string }

/** 1 company, 2 companies */
function plural(n: number, word: string): string {
  const form = n === 1 ? word : word.endsWith("y") ? `${word.slice(0, -1)}ies` : `${word}s`
  return `${n.toLocaleString("en-US")} ${form}`
}

/**
 * Brings every company of an exchange up to today: each company whose prices end before the last completed trading
 * day is queued and only its missing days are fetched (ANTM stored up to 9 Oct: 9 Oct to today); companies already up
 * to date are skipped. Optionally the IHSG index too. The same for a market: every Forex pair or commodity whose bars
 * end before its last completed day.
 */
export function PriceUpdateCard({
  exchanges,
  statusByExchange,
  markets,
  onSubmitted,
}: {
  exchanges: Exchange[]
  statusByExchange: Record<string, PriceStatus>
  /** Forex and Commodities with their instruments */
  markets: MarketStatus[]
  onSubmitted: (job: IngestionJob) => void
}) {
  const [exchange, setExchange] = useState<string | null>(exchanges[0]?.code ?? markets[0]?.market ?? null)
  const [withIndex, setWithIndex] = useState(true)
  const [pending, setPending] = useState(false)
  const [notice, setNotice] = useState<Notice | null>(null)

  const market = markets.find((m) => m.market === exchange) ?? null
  const status = exchange && !market ? statusByExchange[exchange] : undefined
  const exchangeItems = [
    ...exchanges.map((e) => ({ value: e.code, label: `${e.code} · ${e.name}` })),
    ...markets.map((m) => ({ value: m.market, label: m.name })),
  ]
  const toFetch = market ? market.behind + market.withoutPrices : status ? status.behind + status.withoutPrices : 0
  const indexBehind = !!status?.index && !status.index.upToDate
  const nothingToDo = market ? toFetch === 0 : !!status && toFetch === 0 && (!withIndex || !indexBehind)
  const noun = market ? market.itemLabel.toLowerCase() : "company"

  async function submitExchange() {
    const { body } = await requestJson<PriceUpdate>("/api/prices/updates", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ exchange, index: withIndex }),
    })
    const parts = [
      body.queued > 0
        ? `Queued ${plural(body.queued, "company")}: each from its latest stored day up to ${formatDate(body.lastCompletedTradingDay)}${
            body.withoutPrices > 0 ? ` (${body.withoutPrices} without prices: their whole history)` : ""
          }.`
        : "No company needed new prices.",
    ]
    if (body.upToDate > 0) parts.push(`${plural(body.upToDate, "company")} already up to date.`)
    if (body.alreadyActive > 0) parts.push(`${plural(body.alreadyActive, "company")} already queued or running.`)
    if (body.index) {
      // the index job stores IHSG and LQ45 together
      const names = status?.index ? indexNames(status.index) : body.index.code
      const plural = names.includes(" and ")
      parts.push(
        body.index.outcome === "QUEUED"
          ? `${names} queued.`
          : body.index.outcome === "ALREADY_ACTIVE"
            ? `${names} ${plural ? "are" : "is"} already queued or running.`
            : `${names} ${plural ? "are" : "is"} already up to date.`
      )
    }
    setNotice({ tone: "success", text: parts.join(" ") })
    const watch = body.firstJob ?? body.index?.job ?? null
    if (watch) onSubmitted(watch)
  }

  async function submitMarket(m: MarketStatus) {
    const { body } = await requestJson<MarketUpdate>(`/api/markets/${m.market}/updates`, { method: "POST" })
    const item = m.itemLabel.toLowerCase()
    const parts = [
      body.queued > 0
        ? `Queued ${plural(body.queued, item)}: each from its latest stored day up to its last completed day${
            body.withoutPrices > 0 ? ` (${body.withoutPrices} without prices: their whole history)` : ""
          }.`
        : `No ${item} needed new prices.`,
    ]
    if (body.upToDate > 0) parts.push(`${plural(body.upToDate, item)} already up to date.`)
    if (body.alreadyActive > 0) parts.push(`${plural(body.alreadyActive, item)} already queued or running.`)
    setNotice({ tone: "success", text: parts.join(" ") })
    if (body.firstJob) onSubmitted(body.firstJob)
  }

  async function submit() {
    if (!exchange || pending) return
    setPending(true)
    setNotice(null)
    try {
      if (market) {
        await submitMarket(market)
      } else {
        await submitExchange()
      }
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
          <CalendarCheckIcon className="size-4 text-primary" />
          {market ? `Update all ${market.itemsLabel.toLowerCase()} to today` : "Update all companies to today"}
        </CardTitle>
        <CardDescription>
          {market
            ? `Fetches only the missing days of every ${noun}: from its latest stored price to its last completed day.`
            : "Fetches only the missing days of every company of the exchange: from its latest stored price to today."}
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <div className="flex flex-col gap-1.5">
          <span className="text-xs font-medium text-muted-foreground">Exchange or market</span>
          <Select
            items={exchangeItems}
            value={exchange}
            onValueChange={(code) => {
              if (!code || code === exchange) return
              setExchange(code)
              setNotice(null)
            }}
          >
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

        {status && (
          <dl className="grid grid-cols-2 gap-2 text-xs sm:grid-cols-4">
            <Stat label="Companies" value={status.companies} />
            <Stat label="Up to date" value={status.upToDate} tone="good" />
            <Stat label="Behind" value={status.behind} tone={status.behind > 0 ? "warn" : undefined} />
            <Stat label="No prices" value={status.withoutPrices} tone={status.withoutPrices > 0 ? "warn" : undefined} />
          </dl>
        )}
        {market && (
          <dl className="grid grid-cols-2 gap-2 text-xs sm:grid-cols-4">
            <Stat label={market.itemsLabel} value={market.instruments} />
            <Stat label="Up to date" value={market.upToDate} tone="good" />
            <Stat label="Behind" value={market.behind} tone={market.behind > 0 ? "warn" : undefined} />
            <Stat label="No prices" value={market.withoutPrices} tone={market.withoutPrices > 0 ? "warn" : undefined} />
          </dl>
        )}
        {status && (
          <ul className="list-disc space-y-1 pl-4 text-xs text-muted-foreground">
            <li>
              Today&apos;s prices are final from 17:00 WIB: the update goes up to{" "}
              <span className="font-medium text-foreground">{formatDate(status.lastCompletedTradingDay)}</span>.
            </li>
            {status.oldestLatestDate && status.behind > 0 && (
              <li>
                The oldest stored prices end on {formatDate(status.oldestLatestDate)}: that company is fetched from{" "}
                {formatDate(status.oldestLatestDate)} to {formatDate(status.lastCompletedTradingDay)}.
              </li>
            )}
            {status.withoutPrices > 0 && <li>Companies without prices get their whole history.</li>}
            <li>Runs in the background with the other price jobs; follow them in the table below.</li>
          </ul>
        )}
        {market && (
          <ul className="space-y-1 text-xs text-muted-foreground">
            {market.items.map((i) => (
              <li key={i.code} className="flex flex-wrap items-baseline justify-between gap-x-3">
                <span>
                  <span className="font-medium text-foreground">{i.name}</span>{" "}
                  {i.frequency === "MONTHLY" ? "(monthly)" : ""}
                </span>
                <span className={cn("tabular-nums", !i.upToDate && "text-amber-700 dark:text-amber-400")}>
                  {i.lastDate ? `up to ${formatDate(i.lastDate)}` : "no prices"}
                  {i.lastDate && !i.upToDate ? ` · due ${formatDate(i.lastCompleted)}` : ""}
                </span>
              </li>
            ))}
            <li className="pt-1">
              A day is final once it is over in the market&apos;s time zone (London for currencies, New York for
              futures); a monthly series (World Bank, IMF or FRED: coal, LNG, nickel, tin, cobalt, rubber, pulp, fertilizers) is
              final a month after.
            </li>
          </ul>
        )}

        {status?.index && (
          <label className="flex items-start gap-2 text-sm">
            <input
              type="checkbox"
              checked={withIndex}
              onChange={(e) => setWithIndex(e.target.checked)}
              disabled={pending}
              className="mt-0.5 size-4 accent-primary"
            />
            <span>
              Also update the {indexNames(status.index)} {indexesOf(status.index).length > 1 ? "indexes" : "index"}
              {indexesOf(status.index).map((i) => (
                <span key={i.code} className="block text-xs text-muted-foreground">
                  {i.code}:{" "}
                  {i.lastDate
                    ? `stored up to ${formatDate(i.lastDate)}${i.upToDate ? " (up to date)" : ""}.`
                    : "not stored yet: its whole history will be loaded."}
                </span>
              ))}
            </span>
          </label>
        )}

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

        <Button onClick={submit} disabled={!exchange || pending || nothingToDo} className="self-start">
          {pending ? <LoaderIcon className="animate-spin" /> : <RefreshCwIcon />}
          {pending
            ? "Queuing..."
            : nothingToDo
              ? "Everything is up to date"
              : toFetch > 0
                ? `Update ${plural(toFetch, noun)}`
                : "Update"}
        </Button>
      </CardContent>
    </Card>
  )
}

function Stat({ label, value, tone }: { label: string; value: number; tone?: "good" | "warn" }) {
  return (
    <div className="rounded-lg border bg-muted/30 px-2.5 py-1.5">
      <dt className="text-[11px] text-muted-foreground">{label}</dt>
      <dd
        className={cn(
          "font-mono text-sm font-semibold tabular-nums",
          tone === "good" && "text-emerald-700 dark:text-emerald-400",
          tone === "warn" && "text-amber-700 dark:text-amber-400"
        )}
      >
        {value.toLocaleString("en-US")}
      </dd>
    </div>
  )
}
