"use client"

import { useState } from "react"
import { ChartCandlestickIcon, CircleAlertIcon, CircleCheckIcon, LoaderIcon, RefreshCwIcon } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { formatDate } from "@/lib/format"
import { jsonBody, requestJson } from "@/lib/client-api"
import type { IngestionJob, ListingPricesStatus } from "@/lib/types"
import { cn } from "@/lib/utils"

type Notice = { tone: "success" | "error"; text: string }

/**
 * Queues the price history of the screening universe (Yahoo Finance): the daily bars of every listing, the price
 * movements of Screening › Technicals (All). A technical screening of a tier also loads the bars of its listings that
 * are behind before it starts.
 */
export function ListingPricesCard({
  status,
  onSubmitted,
}: {
  status: ListingPricesStatus
  onSubmitted: (job: IngestionJob) => void
}) {
  const [full, setFull] = useState(false)
  const [pending, setPending] = useState(false)
  const [notice, setNotice] = useState<Notice | null>(null)

  async function submit() {
    if (pending) return
    setPending(true)
    setNotice(null)
    try {
      const { status: http, body: job } = await requestJson<IngestionJob>(
        "/api/listing-prices/ingestions",
        jsonBody("POST", { exchange: status.exchange, full })
      )
      setNotice({
        tone: "success",
        text:
          http === 202
            ? `Queued the ${status.exchange} price history${full ? " (every listing again)" : ""}.`
            : "A price history update is already queued or running. Follow it in the table below.",
      })
      onSubmitted(job)
    } catch (e) {
      setNotice({ tone: "error", text: e instanceof Error ? e.message : String(e) })
    } finally {
      setPending(false)
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <ChartCandlestickIcon className="size-4 text-primary" />
          Price history for technical analysis · Yahoo Finance
        </CardTitle>
        <CardDescription>
          Daily bars (open, high, low, close, volume) of every {status.exchange} listing: the price movements, moving
          averages and indicators of Screening › Technicals (All).
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <ul className="list-disc space-y-1 pl-4 text-xs text-muted-foreground">
          <li>
            {status.withBars > 0
              ? `${status.withBars} of ${status.listings} listings with daily bars (${status.rows.toLocaleString(
                  "en-US"
                )} bars), up to ${formatDate(status.latestDate)}.`
              : status.listings > 0
                ? `No daily bars stored yet for the ${status.listings} listings.`
                : "No listings stored yet: run 'Update screening data' first."}
          </li>
          <li>
            A listing&apos;s first load reaches {status.historyYears} years back; later updates fetch only the days since
            its last bar (none when it is up to date).
          </li>
          <li>
            About 1.5 seconds per listing that is behind; the first load of every listing takes about 25 minutes. A
            technical screening of a tier loads the listings it needs itself.
          </li>
        </ul>

        <label className="flex items-start gap-2 text-sm">
          <input
            type="checkbox"
            checked={full}
            onChange={(e) => setFull(e.target.checked)}
            disabled={pending}
            className="mt-0.5 size-4 accent-primary"
          />
          <span>
            Load every listing again
            <span className="block text-xs text-muted-foreground">
              Replaces the stored bars with {status.historyYears} fresh years of each listing.
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

        <Button onClick={submit} disabled={pending} className="self-start">
          {pending ? <LoaderIcon className="animate-spin" /> : <RefreshCwIcon />}
          {pending ? "Queuing..." : "Update price history"}
        </Button>
      </CardContent>
    </Card>
  )
}
