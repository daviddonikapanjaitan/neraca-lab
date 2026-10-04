"use client"

import { useState } from "react"
import { CircleAlertIcon, CircleCheckIcon, DatabaseZapIcon, LoaderIcon, RefreshCwIcon } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { formatDate, formatTimestamp } from "@/lib/format"
import { jsonBody, requestJson } from "@/lib/client-api"
import type { FundamentalsStatus, IngestionJob } from "@/lib/types"
import { cn } from "@/lib/utils"

type Notice = { tone: "success" | "error"; text: string }

/**
 * Queues the screening data ETL (Yahoo Finance): today's market data of every listing and the
 * fundamentals that are due. It also runs every weekday evening and before each screening.
 */
export function FundamentalsCard({
  status,
  onSubmitted,
}: {
  status: FundamentalsStatus
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
        "/api/fundamentals/ingestions",
        jsonBody("POST", { exchange: status.exchange, full })
      )
      setNotice({
        tone: "success",
        text:
          http === 202
            ? `Queued the ${status.exchange} screening data update${full ? " with every fundamental" : ""}.`
            : "A screening data update is already queued or running. Follow it in the table below.",
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
          <DatabaseZapIcon className="size-4 text-primary" />
          Screening data · Yahoo Finance
        </CardTitle>
        <CardDescription>
          Daily ETL of the AI screening: market data of every {status.exchange} listing and their fundamentals (ratios
          and four fiscal years).
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <ul className="list-disc space-y-1 pl-4 text-xs text-muted-foreground">
          <li>
            {status.listings > 0
              ? `${status.listings} listings stored, market data of ${formatDate(status.latestSnapshotDate)}, ${
                  status.withFundamentals
                } with fundamentals (oldest ${formatTimestamp(status.oldestFundamentalsAt)}).`
              : "No screening data stored yet."}
          </li>
          <li>Runs every weekday after the close and before each screening; fundamentals are refreshed weekly.</li>
          <li>About 3 seconds per stock whose fundamentals are due; the first full load takes about 45 minutes.</li>
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
            Refresh every fundamental
            <span className="block text-xs text-muted-foreground">
              Not only those older than a week. Takes about 45 minutes for IDX.
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
          {pending ? "Queuing..." : "Update screening data"}
        </Button>
      </CardContent>
    </Card>
  )
}
