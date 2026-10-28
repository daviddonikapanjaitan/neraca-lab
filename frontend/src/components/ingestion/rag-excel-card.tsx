"use client"

import { useRef, useState } from "react"
import {
  CircleAlertIcon,
  CircleCheckIcon,
  FileSpreadsheetIcon,
  FileUpIcon,
  LoaderIcon,
  SheetIcon,
  XIcon,
} from "lucide-react"

import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { requestJson } from "@/lib/client-api"
import { formatBytes, MAX_UPLOAD_BYTES } from "@/lib/ingestion"
import { dateInFileName, dayLabel, type ExcelStatus } from "@/lib/ownership"
import type { IngestionJob } from "@/lib/types"
import { cn } from "@/lib/utils"

/** Most workbooks chosen at once (one job each). */
const MAX_FILES = 31

type Upload = {
  file: File
  /** the list's date read from the file name (peng-2026-10-27-00104-lima-persen.xlsx), if any */
  date: string | null
  state: "waiting" | "uploading" | "queued" | "already" | "error"
  message?: string
}

/** Why a file cannot be uploaded, or null when it can. */
function fileProblem(file: File): string | null {
  if (!file.name.toLowerCase().endsWith(".xlsx")) {
    return `"${file.name}" is not an .xlsx workbook.`
  }
  if (file.size === 0) return `"${file.name}" is empty.`
  if (file.size > MAX_UPLOAD_BYTES) {
    return `"${file.name}" is ${formatBytes(file.size)}; the limit is ${formatBytes(MAX_UPLOAD_BYTES)}.`
  }
  return null
}

/**
 * Upload of Excel documents of an exchange: choose the exchange, then the document type it offers (IDX: Ownership
 * (>=5%), KSEI's daily list of the shareholders of more than 5%; Ownership (>1%) is a later feature), then one or more
 * workbooks. Each workbook is read by the backend at once and becomes a job of its own; the lists are stored one
 * after the other (oldest date first), for Companies > Ownership (>=5%) and the vector store.
 */
export function RagExcelCard({
  status,
  onSubmitted,
}: {
  status: ExcelStatus
  onSubmitted: (job: IngestionJob) => void
}) {
  const input = useRef<HTMLInputElement>(null)
  const [exchange, setExchange] = useState<string | null>(null)
  const [documentType, setDocumentType] = useState<string | null>(null)
  const [uploads, setUploads] = useState<Upload[]>([])
  const [dragging, setDragging] = useState(false)
  const [pending, setPending] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)

  const chosenExchange = status.exchanges.find((e) => e.code === exchange) ?? null
  const types = chosenExchange?.documentTypes ?? []
  const chosenType = types.find((t) => t.code === documentType) ?? null
  const ready = chosenType?.available === true
  const exchangeItems = status.exchanges.map((e) => ({ value: e.code, label: e.label }))
  const typeItems = types.map((t) => ({ value: t.code, label: t.available ? t.label : `${t.label} (later feature)` }))
  const waiting = uploads.filter((u) => u.state === "waiting" || u.state === "error")

  function choose(list: FileList | null | undefined) {
    if (!list || list.length === 0) return
    const problems: string[] = []
    const next = [...uploads.filter((u) => u.state === "waiting" || u.state === "error")]
    for (const file of Array.from(list)) {
      const why = fileProblem(file)
      if (why) {
        problems.push(why)
      } else if (!next.some((u) => u.file.name === file.name && u.file.size === file.size)) {
        next.push({ file, date: dateInFileName(file.name), state: "waiting" })
      }
    }
    // the oldest list first: each list is then compared with the day before it, already stored
    next.sort((a, b) => (a.date ?? a.file.name).localeCompare(b.date ?? b.file.name))
    if (next.length > MAX_FILES) problems.push(`At most ${MAX_FILES} workbooks at once; the newest were left out.`)
    setUploads(next.slice(0, MAX_FILES))
    setProblem(problems.length > 0 ? problems.join(" ") : null)
    // the same files can be chosen again after removing them
    if (input.current) input.current.value = ""
  }

  function update(index: number, change: Partial<Upload>) {
    setUploads((list) => list.map((u, i) => (i === index ? { ...u, ...change } : u)))
  }

  async function upload() {
    if (!ready || !exchange || !documentType || pending) return
    setPending(true)
    setProblem(null)
    // one after the other, in date order (the backend stores them in the order received)
    for (let i = 0; i < uploads.length; i++) {
      const u = uploads[i]
      if (u.state !== "waiting" && u.state !== "error") continue
      update(i, { state: "uploading", message: undefined })
      try {
        const body = new FormData()
        body.append("file", u.file, u.file.name)
        body.append("exchange", exchange)
        body.append("documentType", documentType)
        const { status: code, body: job } = await requestJson<IngestionJob>("/api/rag/xlsx", { method: "POST", body })
        update(i, { state: code === 202 ? "queued" : "already" })
        onSubmitted(job)
      } catch (error) {
        update(i, { state: "error", message: error instanceof Error ? error.message : String(error) })
      }
    }
    setPending(false)
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <SheetIcon className="size-4 text-primary" />
          Excel document upload
        </CardTitle>
        <CardDescription>
          Reads an Excel document of an exchange into the database and stores what it says about each stored company
          in the RAG vector store (pgvector), for Screening › Analysis.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <div className="grid gap-3 sm:grid-cols-2">
          <div className="flex flex-col gap-1.5">
            <span className="text-xs font-medium text-muted-foreground">Exchange</span>
            <Select
              items={exchangeItems}
              value={exchange}
              onValueChange={(value) => {
                setExchange(value as string | null)
                setDocumentType(null)
                setProblem(null)
              }}
            >
              <SelectTrigger aria-label="Exchange" className="w-full" disabled={pending}>
                <SelectValue placeholder="Choose the exchange" />
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
          {chosenExchange && (
            <div className="flex flex-col gap-1.5">
              <span className="text-xs font-medium text-muted-foreground">Document type</span>
              <Select
                items={typeItems}
                value={documentType}
                onValueChange={(value) => {
                  setDocumentType(value as string | null)
                  setProblem(null)
                }}
              >
                <SelectTrigger aria-label="Document type" className="w-full" disabled={pending}>
                  <SelectValue placeholder="Choose the document type" />
                </SelectTrigger>
                <SelectContent>
                  {types.map((t) => (
                    <SelectItem key={t.code} value={t.code} disabled={!t.available}>
                      {t.available ? t.label : `${t.label} (later feature)`}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          )}
        </div>

        {!chosenExchange && (
          <p className="text-xs text-muted-foreground">Choose the exchange to see the documents it takes.</p>
        )}
        {chosenType && (
          <p className="text-xs text-muted-foreground">
            {chosenType.examples}
            {!chosenType.available && " This document type is not available yet."}
          </p>
        )}

        {ready && (
          <>
            <ul className="list-disc space-y-1 pl-4 text-xs text-muted-foreground">
              <li>
                The daily list <span className="font-medium text-foreground">Kepemilikan Efek Diatas 5% Berdasarkan SID
                (Publik)</span> published by KSEI on idx.co.id, e.g.{" "}
                <span className="font-mono text-foreground">peng-2026-10-27-00104-lima-persen.xlsx</span>. Each list
                holds two dates: the day and the trading day before.
              </li>
              <li>
                Choose several lists at once: they are uploaded and stored oldest first, one job each. An investor that
                fell below 5% is not in KSEI&apos;s list: it is found by comparing a day with the stored day before, so
                upload consecutive days.
              </li>
              <li>
                Uploading the list of a day again replaces it. Every investor, its securities accounts and its buys and
                sells are shown on Companies › Ownership (&gt;=5%); each stored company&apos;s holders and their
                movements over every stored day become one document in the vector store.
              </li>
            </ul>

            <label
              onDragOver={(e) => {
                e.preventDefault()
                setDragging(true)
              }}
              onDragLeave={() => setDragging(false)}
              onDrop={(e) => {
                e.preventDefault()
                setDragging(false)
                choose(e.dataTransfer.files)
              }}
              className={cn(
                "flex cursor-pointer flex-col items-center justify-center gap-1.5 rounded-xl border border-dashed px-4 py-6 text-center transition-colors hover:bg-muted/50",
                dragging && "border-primary bg-primary/5",
                pending && "pointer-events-none opacity-60"
              )}
            >
              <input
                ref={input}
                type="file"
                multiple
                accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                className="sr-only"
                onChange={(e) => choose(e.target.files)}
                disabled={pending}
              />
              <FileUpIcon className="size-6 text-muted-foreground" />
              <span className="text-sm font-medium">Drop .xlsx lists here or click to choose</span>
              <span className="text-xs text-muted-foreground">One or more workbooks, up to 50 MB each</span>
            </label>
          </>
        )}

        {uploads.length > 0 && (
          <ul className="flex flex-col gap-1.5">
            {uploads.map((u, i) => (
              <li key={`${u.file.name}-${u.file.size}`} className="rounded-lg bg-muted/50 px-3 py-2 text-sm">
                <div className="flex items-center gap-2">
                  <FileSpreadsheetIcon className="size-4 shrink-0 text-emerald-600" />
                  <span className="min-w-0 flex-1 truncate font-medium">{u.file.name}</span>
                  {u.date && (
                    <span className="hidden shrink-0 text-xs text-muted-foreground sm:inline">{dayLabel(u.date)}</span>
                  )}
                  <span className="shrink-0 text-xs text-muted-foreground tabular-nums">{formatBytes(u.file.size)}</span>
                  {u.state === "uploading" && <LoaderIcon className="size-3.5 shrink-0 animate-spin" />}
                  {(u.state === "queued" || u.state === "already") && (
                    <CircleCheckIcon className="size-3.5 shrink-0 text-emerald-600" />
                  )}
                  {u.state === "error" && <CircleAlertIcon className="size-3.5 shrink-0 text-destructive" />}
                  {(u.state === "waiting" || u.state === "error") && (
                    <Button
                      variant="ghost"
                      size="icon-xs"
                      aria-label={`Remove ${u.file.name}`}
                      onClick={() => setUploads((list) => list.filter((_, j) => j !== i))}
                      disabled={pending}
                    >
                      <XIcon />
                    </Button>
                  )}
                </div>
                {u.state === "queued" && (
                  <p className="mt-0.5 text-xs text-emerald-600 dark:text-emerald-400">
                    Queued. Follow the job in the table below.
                  </p>
                )}
                {u.state === "already" && (
                  <p className="mt-0.5 text-xs text-emerald-600 dark:text-emerald-400">
                    Already being stored. Follow the job in the table below.
                  </p>
                )}
                {u.state === "error" && u.message && <p className="mt-0.5 text-xs text-destructive">{u.message}</p>}
              </li>
            ))}
          </ul>
        )}

        {problem && (
          <p role="alert" className="flex items-start gap-2 text-xs text-destructive">
            <CircleAlertIcon className="mt-px size-3.5 shrink-0" />
            <span>{problem}</span>
          </p>
        )}

        <div className="flex flex-wrap gap-2">
          <Button onClick={upload} disabled={!ready || waiting.length === 0 || pending} className="self-start">
            {pending ? <LoaderIcon className="animate-spin" /> : <FileUpIcon />}
            {pending
              ? "Uploading..."
              : waiting.length > 1
                ? `Upload ${waiting.length} lists`
                : "Upload and store"}
          </Button>
          {uploads.length > 0 && waiting.length === 0 && !pending && (
            <Button variant="outline" onClick={() => setUploads([])}>
              Clear the list
            </Button>
          )}
        </div>
      </CardContent>
    </Card>
  )
}
