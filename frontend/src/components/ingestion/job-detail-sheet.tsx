"use client"

import { useEffect, useState } from "react"
import Link from "next/link"
import { ChevronRightIcon, CircleAlertIcon, LoaderIcon } from "lucide-react"

import { DownloadFileButton } from "@/components/ingestion/download-file-button"
import { JobStatusBadge } from "@/components/ingestion/job-status-badge"
import { buttonVariants } from "@/components/ui/button"
import { Separator } from "@/components/ui/separator"
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from "@/components/ui/sheet"
import { EMPTY, formatDate, formatNumber, formatTimestamp } from "@/lib/format"
import { requestJson } from "@/lib/client-api"
import { creatorLabel, formatBytes, formatDuration, isActive, providerLabel, TYPE_LABEL } from "@/lib/ingestion"
import { companyHref } from "@/lib/links"
import type { IngestionJob } from "@/lib/types"

/** Fields of the financial statement upload result (IngestionResponse) shown here. */
interface FilingResult {
  status?: string
  filing?: {
    ticker?: string
    legalName?: string
    periodType?: string
    fiscalYear?: number
    periodStart?: string
    periodEnd?: string
    audited?: boolean
    currency?: string
  } | null
  company?: { companyId?: number | null; companyName?: string | null } | null
  verification?: { complete?: boolean; pending?: string[]; problems?: string[] } | null
  savedStatements?: Record<string, unknown[]>
  savedSegments?: Record<string, unknown[]>
  metrics?: {
    durationMs?: number
    modelCalls?: number
    toolCalls?: number
    toolErrors?: number
    modelRetries?: number
  } | null
  error?: string | null
}

/** Fields of the price ingestion result (PriceIngestionService.Result). */
interface PriceResult {
  provider?: string
  requests?: number
  requestedFrom?: string | null
  requestedTo?: string | null
  fullHistory?: boolean
  reAdjusted?: boolean
  barsReceived?: number
  barsSkipped?: number
  inserted?: number
  updated?: number
  unchanged?: number
  latestTradingDate?: string | null
  valuation?: { marketSnapshots?: number; valuationSnapshots?: number; valuationMetrics?: number } | null
  /** Set when the listing trades in another currency than the company reports in (INDY: IDR -> USD). */
  conversion?: {
    listingCurrency?: string
    priceCurrency?: string
    fxSource?: string | null
    fxRatesReceived?: number
    barsWithoutRate?: number
  } | null
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="grid grid-cols-[8.5rem_1fr] gap-2 text-sm">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="min-w-0 break-words">{children ?? EMPTY}</dd>
    </div>
  )
}

function count(value: number | undefined | null): string {
  return value === undefined || value === null ? EMPTY : formatNumber(value, 0)
}

function List({ items, tone }: { items: string[]; tone?: "destructive" }) {
  return (
    <ul className={tone === "destructive" ? "list-disc pl-4 text-destructive" : "list-disc pl-4"}>
      {items.map((item, i) => (
        <li key={i}>{item}</li>
      ))}
    </ul>
  )
}

function FilingSummary({ result }: { result: FilingResult }) {
  const filing = result.filing
  const statements = Object.values(result.savedStatements ?? {}).reduce((n, rows) => n + rows.length, 0)
  const segments = Object.values(result.savedSegments ?? {}).reduce((n, rows) => n + rows.length, 0)
  const verification = result.verification
  return (
    <dl className="flex flex-col gap-2">
      {filing && (
        <>
          <Field label="Company">
            {result.company?.companyName ?? filing.legalName ?? filing.ticker}
          </Field>
          <Field label="Period">
            {filing.fiscalYear} {filing.periodType} · {formatDate(filing.periodStart)} to {formatDate(filing.periodEnd)}
            {filing.audited ? " · audited" : " · unaudited"}
          </Field>
          <Field label="Currency">{filing.currency}</Field>
        </>
      )}
      <Field label="Rows written">
        {statements} statement {statements === 1 ? "row" : "rows"}, {segments} segment {segments === 1 ? "row" : "rows"}
      </Field>
      <Field label="Verification">
        {verification ? (verification.complete ? "Complete: everything stored and verified" : "Incomplete") : EMPTY}
      </Field>
      {verification?.pending && verification.pending.length > 0 && (
        <Field label="Pending">
          <List items={verification.pending} />
        </Field>
      )}
      {verification?.problems && verification.problems.length > 0 && (
        <Field label="Problems">
          <List items={verification.problems} tone="destructive" />
        </Field>
      )}
      {result.metrics && (
        <Field label="AI agent">
          {formatDuration(result.metrics.durationMs)} · {count(result.metrics.modelCalls)} model calls ·{" "}
          {count(result.metrics.toolCalls)} tool calls ({count(result.metrics.toolErrors)} errors)
          {(result.metrics.modelRetries ?? 0) > 0 && <> · {count(result.metrics.modelRetries)} model retries</>}
        </Field>
      )}
    </dl>
  )
}

function PriceSummary({ result }: { result: PriceResult }) {
  return (
    <dl className="flex flex-col gap-2">
      <Field label="Provider">{providerLabel(result.provider)}</Field>
      <Field label="Requested">
        {result.requests === 0
          ? "No request: prices already up to date"
          : `${formatDate(result.requestedFrom)} to ${formatDate(result.requestedTo)} (${count(result.requests)} ${result.requests === 1 ? "request" : "requests"})`}
      </Field>
      {result.reAdjusted && (
        <Field label="Re-adjusted">The provider re-adjusted its history, so the full history was fetched again</Field>
      )}
      <Field label="Price days">
        {count(result.inserted)} new · {count(result.updated)} updated · {count(result.unchanged)} unchanged
      </Field>
      <Field label="Bars">
        {count(result.barsReceived)} received · {count(result.barsSkipped)} skipped
      </Field>
      <Field label="Latest day">{formatDate(result.latestTradingDate)}</Field>
      {result.conversion && (
        <Field label="Currency">
          Quoted in {result.conversion.listingCurrency}, stored in {result.conversion.priceCurrency}
          {result.conversion.fxSource
            ? ` (${result.conversion.fxSource}, ${count(result.conversion.fxRatesReceived)} rates)`
            : ""}
          {(result.conversion.barsWithoutRate ?? 0) > 0 &&
            ` · ${count(result.conversion.barsWithoutRate)} days without a rate not stored`}
        </Field>
      )}
      {result.valuation && (
        <Field label="Valuation">
          {count(result.valuation.marketSnapshots)} market snapshots · {count(result.valuation.valuationSnapshots)}{" "}
          valuation snapshots · {count(result.valuation.valuationMetrics)} metrics
        </Field>
      )}
    </dl>
  )
}

/** One job with its result; refreshed every 2 seconds while the job is active. */
export function JobDetailSheet({
  jobId,
  onOpenChange,
}: {
  jobId: string | null
  onOpenChange: (open: boolean) => void
}) {
  const [job, setJob] = useState<IngestionJob | null>(null)
  const [failure, setFailure] = useState<{ id: string; message: string } | null>(null)

  useEffect(() => {
    if (!jobId) return
    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | undefined
    const id = jobId

    async function load() {
      try {
        const { body } = await requestJson<IngestionJob>(`/api/ingestions/${encodeURIComponent(id)}`)
        if (cancelled) return
        setJob(body)
        setFailure(null)
        if (isActive(body.status)) timer = setTimeout(load, 2000)
      } catch (e) {
        if (cancelled) return
        setFailure({ id, message: e instanceof Error ? e.message : String(e) })
        timer = setTimeout(load, 5000)
      }
    }
    load()
    return () => {
      cancelled = true
      if (timer) clearTimeout(timer)
    }
  }, [jobId])

  // state of an earlier job is never shown while the selected one loads
  const shown = job && job.id === jobId ? job : null
  const error = failure && failure.id === jobId ? failure.message : null
  const duration =
    shown?.startedAt && shown.finishedAt
      ? new Date(shown.finishedAt).getTime() - new Date(shown.startedAt).getTime()
      : null

  return (
    <Sheet open={jobId !== null} onOpenChange={onOpenChange}>
      <SheetContent className="w-full overflow-y-auto data-[side=right]:sm:max-w-xl">
        <SheetHeader>
          <SheetTitle>{shown ? TYPE_LABEL[shown.type] : "Ingestion job"}</SheetTitle>
          <SheetDescription className="font-mono text-xs break-all">{jobId}</SheetDescription>
        </SheetHeader>

        <div className="flex flex-col gap-4 px-4 pb-6">
          {!shown && !error && (
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <LoaderIcon className="size-4 animate-spin" /> Loading...
            </div>
          )}
          {error && (
            <p role="alert" className="flex items-start gap-2 text-sm text-destructive">
              <CircleAlertIcon className="mt-0.5 size-4 shrink-0" /> {error}
            </p>
          )}

          {shown && (
            <>
              <div className="flex flex-wrap items-center gap-2">
                <JobStatusBadge status={shown.status} />
                {shown.stage && <span className="text-sm">{shown.stage}</span>}
              </div>
              {shown.message && (
                <p
                  className={
                    shown.status === "FAILED"
                      ? "rounded-lg bg-destructive/10 px-3 py-2 text-sm text-destructive"
                      : "rounded-lg bg-muted px-3 py-2 text-sm"
                  }
                >
                  {shown.message}
                </p>
              )}

              <dl className="flex flex-col gap-2">
                {shown.ticker && (
                  <Field label="Company">
                    <span className="font-mono">{shown.exchange} · {shown.ticker}</span>
                  </Field>
                )}
                {shown.file && (
                  <>
                    <Field label="File">{shown.file.fileName}</Field>
                    <Field label="Size">{formatBytes(shown.file.sizeBytes)}</Field>
                    <Field label="SHA-256">
                      <span className="font-mono text-xs break-all">{shown.file.checksumSha256}</span>
                    </Field>
                    <Field label="Stored file">
                      {shown.file.reused
                        ? `Reused (#${shown.file.fileId}, uploaded before with the same checksum)`
                        : `New (#${shown.file.fileId})`}
                    </Field>
                    <DownloadFileButton jobId={shown.id} fileName={shown.file.fileName} />
                  </>
                )}
                {shown.type === "PRICE" && (
                  <Field label="Mode">{shown.fullHistory ? "Full history" : "New trading days"}</Field>
                )}
                {shown.type === "FUNDAMENTALS" && (
                  <Field label="Mode">{shown.fullHistory ? "Every fundamental" : "Fundamentals due"}</Field>
                )}
                <Field label="Started by">{creatorLabel(shown.createdBy)}</Field>
                <Field label="Requested">{formatTimestamp(shown.requestedAt)}</Field>
                <Field label="Started">{formatTimestamp(shown.startedAt)}</Field>
                <Field label="Finished">{formatTimestamp(shown.finishedAt)}</Field>
                {shown.resumeAt && <Field label="Resumes">{formatTimestamp(shown.resumeAt)}</Field>}
                <Field label="Duration">{formatDuration(duration)}</Field>
                <Field label="Attempts">{shown.attempts}</Field>
              </dl>

              {shown.result != null && (
                <>
                  <Separator />
                  <h3 className="text-sm font-semibold">Result</h3>
                  {shown.type === "FINANCIAL_STATEMENT" && <FilingSummary result={shown.result as FilingResult} />}
                  {shown.type === "PRICE" && <PriceSummary result={shown.result as PriceResult} />}
                  <details className="rounded-lg border">
                    <summary className="cursor-pointer px-3 py-2 text-xs font-medium text-muted-foreground">
                      {shown.type === "FINANCIAL_STATEMENT"
                        ? "Full audit trail (plan, tool calls, verification) as JSON"
                        : "Result as JSON"}
                    </summary>
                    <pre className="max-h-96 overflow-auto border-t px-3 py-2 text-[11px] leading-relaxed">
                      {JSON.stringify(shown.result, null, 2)}
                    </pre>
                  </details>
                </>
              )}

              {shown.type === "SCREENING" && (
                <Link
                  href={`/screening/${shown.id}`}
                  className={buttonVariants({ variant: "outline", size: "sm", className: "self-start" })}
                >
                  Open the screening report <ChevronRightIcon />
                </Link>
              )}

              {shown.ticker && shown.exchange && !isActive(shown.status) && shown.status !== "FAILED" && (
                <Link
                  href={companyHref(shown.exchange, shown.ticker)}
                  className={buttonVariants({ variant: "outline", size: "sm", className: "self-start" })}
                >
                  Open {shown.ticker} <ChevronRightIcon />
                </Link>
              )}
            </>
          )}
        </div>
      </SheetContent>
    </Sheet>
  )
}
