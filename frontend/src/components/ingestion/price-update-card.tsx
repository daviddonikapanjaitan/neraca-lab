"use client"

import { useState } from "react"
import { CalendarCheckIcon, CircleAlertIcon, CircleCheckIcon, LoaderIcon, RefreshCwIcon } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { requestJson } from "@/lib/client-api"
import { formatDate } from "@/lib/format"
import type { Exchange, IngestionJob, PriceStatus, PriceUpdate } from "@/lib/types"
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
 * to date are skipped. Optionally the IHSG index too. IDX only for now; other exchanges appear here when supported.
 */
export function PriceUpdateCard({
  exchanges,
  statusByExchange,
  onSubmitted,
}: {
  exchanges: Exchange[]
  statusByExchange: Record<string, PriceStatus>
  onSubmitted: (job: IngestionJob) => void
}) {
  const [exchange, setExchange] = useState<string | null>(exchanges[0]?.code ?? null)
  const [withIndex, setWithIndex] = useState(true)
  const [pending, setPending] = useState(false)
  const [notice, setNotice] = useState<Notice | null>(null)

  const status = exchange ? statusByExchange[exchange] : undefined
  const exchangeItems = exchanges.map((e) => ({ value: e.code, label: `${e.code} · ${e.name}` }))
  const toFetch = status ? status.behind + status.withoutPrices : 0
  const indexBehind = !!status?.index && !status.index.upToDate
  const nothingToDo = !!status && toFetch === 0 && (!withIndex || !indexBehind)

  async function submit() {
    if (!exchange || pending) return
    setPending(true)
    setNotice(null)
    try {
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
        parts.push(
          body.index.outcome === "QUEUED"
            ? `${body.index.code} queued.`
            : body.index.outcome === "ALREADY_ACTIVE"
              ? `${body.index.code} is already queued or running.`
              : `${body.index.code} is already up to date.`
        )
      }
      setNotice({ tone: "success", text: parts.join(" ") })
      const watch = body.firstJob ?? body.index?.job ?? null
      if (watch) onSubmitted(watch)
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
          Update all companies to today
        </CardTitle>
        <CardDescription>
          Fetches only the missing days of every company of the exchange: from its latest stored price to today.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <div className="flex flex-col gap-1.5">
          <span className="text-xs font-medium text-muted-foreground">Exchange</span>
          <Select
            items={exchangeItems}
            value={exchange}
            onValueChange={(code) => {
              if (!code || code === exchange) return
              setExchange(code)
              setNotice(null)
            }}
          >
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

        {status && (
          <dl className="grid grid-cols-2 gap-2 text-xs sm:grid-cols-4">
            <Stat label="Companies" value={status.companies} />
            <Stat label="Up to date" value={status.upToDate} tone="good" />
            <Stat label="Behind" value={status.behind} tone={status.behind > 0 ? "warn" : undefined} />
            <Stat label="No prices" value={status.withoutPrices} tone={status.withoutPrices > 0 ? "warn" : undefined} />
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
              Also update the {status.index.code} index
              <span className="block text-xs text-muted-foreground">
                {status.index.lastDate
                  ? `Stored up to ${formatDate(status.index.lastDate)}${status.index.upToDate ? " (up to date)" : ""}.`
                  : "Not stored yet: its whole history will be loaded."}
              </span>
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
                ? `Update ${plural(toFetch, "company")}`
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
