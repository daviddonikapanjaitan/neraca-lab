"use client"

import { useState } from "react"
import Link from "next/link"
import { ChartCandlestickIcon, ChevronRightIcon, CircleAlertIcon, CircleCheckIcon, LoaderIcon, RefreshCwIcon } from "lucide-react"

import { Button, buttonVariants } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { requestJson } from "@/lib/client-api"
import { formatDate } from "@/lib/format"
import { providerLabel } from "@/lib/ingestion"
import { technicalsHref } from "@/lib/links"
import type { Exchange, IngestionJob, PriceStatus } from "@/lib/types"
import { cn } from "@/lib/utils"

type Notice = { tone: "success" | "error"; text: string }

/**
 * The exchange's index (IHSG = Yahoo Finance ^JKSE) into index_daily, through the price queue: from its latest stored
 * day to today, or its whole history. Companies > Technicals charts it (search IHSG) and compares every stock with it.
 */
export function IndexPriceCard({
  exchanges,
  statusByExchange,
  onSubmitted,
}: {
  exchanges: Exchange[]
  statusByExchange: Record<string, PriceStatus>
  onSubmitted: (job: IngestionJob) => void
}) {
  // the first exchange with an index (IDX: IHSG)
  const exchange = exchanges.find((e) => statusByExchange[e.code]?.index)?.code ?? null
  const status = exchange ? statusByExchange[exchange] : undefined
  const index = status?.index ?? null
  const [full, setFull] = useState(false)
  const [pending, setPending] = useState(false)
  const [notice, setNotice] = useState<Notice | null>(null)

  if (!exchange || !status || !index) {
    return null
  }
  const source = providerLabel(index.source)

  async function submit() {
    if (pending) return
    setPending(true)
    setNotice(null)
    try {
      const { status: http, body: job } = await requestJson<IngestionJob>("/api/prices/index-ingestions", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ exchange, full }),
      })
      setNotice({
        tone: "success",
        text:
          http === 202
            ? `Queued ${full ? "the full history" : "the daily bars"} of ${index!.code} from ${source}.`
            : `An ingestion of ${index!.code} is already queued or running. Follow it in the table below.`,
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
          <ChartCandlestickIcon className="size-4 text-primary" />
          {index.code} index · {source}
        </CardTitle>
        <CardDescription>
          Daily bars of the {index.name} ({index.symbol}) for the IHSG chart on Companies › Technicals, relative strength
          and the market regime.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <p className="text-xs text-muted-foreground">
          {index.lastDate ? (
            <>
              {index.rows.toLocaleString("en-US")} days stored, {formatDate(index.firstDate)} to{" "}
              <span className="font-medium text-foreground">{formatDate(index.lastDate)}</span>
              {index.upToDate
                ? " (up to date)."
                : `; today's update goes up to ${formatDate(status.lastCompletedTradingDay)}.`}
            </>
          ) : (
            "Not stored yet: the first run loads the whole history (from 1990)."
          )}{" "}
          Only the days after the latest stored day are fetched. The technical refresh also updates it every weekday at
          19:00 WIB.
        </p>

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

        <div className="flex flex-wrap items-center gap-2">
          <Button onClick={submit} disabled={pending}>
            {pending ? <LoaderIcon className="animate-spin" /> : <RefreshCwIcon />}
            {pending ? "Queuing..." : `Fetch ${index.code}`}
          </Button>
          {index.lastDate && (
            <Link href={technicalsHref(exchange, index.code)} className={buttonVariants({ variant: "outline" })}>
              Open the {index.code} chart <ChevronRightIcon />
            </Link>
          )}
        </div>
      </CardContent>
    </Card>
  )
}
