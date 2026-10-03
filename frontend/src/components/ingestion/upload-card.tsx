"use client"

import { useRef, useState } from "react"
import {
  CircleAlertIcon,
  CircleCheckIcon,
  FileSpreadsheetIcon,
  FileUpIcon,
  LoaderIcon,
  XIcon,
} from "lucide-react"

import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { requestJson } from "@/lib/client-api"
import { formatBytes, MAX_UPLOAD_BYTES } from "@/lib/ingestion"
import type { IngestionJob } from "@/lib/types"
import { cn } from "@/lib/utils"

type Notice = { tone: "success" | "error"; text: string }

/** Why a file cannot be uploaded, or null when it can. */
function fileProblem(file: File): string | null {
  if (!file.name.toLowerCase().endsWith(".xlsx")) {
    return `"${file.name}" is not an .xlsx file. Only IDX XBRL financial statement workbooks (.xlsx) are accepted.`
  }
  if (file.size === 0) return `"${file.name}" is empty.`
  if (file.size > MAX_UPLOAD_BYTES) {
    return `"${file.name}" is ${formatBytes(file.size)}; the limit is ${formatBytes(MAX_UPLOAD_BYTES)}.`
  }
  return null
}

/** Upload of an IDX XBRL financial statement workbook; the backend queues the AI ingestion. */
export function UploadCard({ onSubmitted }: { onSubmitted: (job: IngestionJob) => void }) {
  const input = useRef<HTMLInputElement>(null)
  const [file, setFile] = useState<File | null>(null)
  const [dragging, setDragging] = useState(false)
  const [pending, setPending] = useState(false)
  const [notice, setNotice] = useState<Notice | null>(null)

  function choose(selected: File | null | undefined) {
    if (!selected) return
    const problem = fileProblem(selected)
    if (problem) {
      setFile(null)
      setNotice({ tone: "error", text: problem })
    } else {
      setFile(selected)
      setNotice(null)
    }
    // the same file can be chosen again after clearing it
    if (input.current) input.current.value = ""
  }

  async function upload() {
    if (!file || pending) return
    setPending(true)
    setNotice(null)
    try {
      const body = new FormData()
      body.append("file", file, file.name)
      const { status, body: job } = await requestJson<IngestionJob>("/api/financial-statements/upload", {
        method: "POST",
        body,
      })
      let text: string
      if (status === 202) {
        text = job.file?.reused
          ? `Queued ${file.name}. This file was uploaded before (same checksum), so the stored copy is used again.`
          : `Queued ${file.name}. The file is stored and the AI agent ingests it in the background.`
      } else {
        text = `${file.name} is already being ingested. Follow the job in the table below.`
      }
      setNotice({ tone: "success", text })
      setFile(null)
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
          <FileSpreadsheetIcon className="size-4 text-primary" />
          Financial statement upload
        </CardTitle>
        <CardDescription>
          Stores an IDX XBRL financial statement: company, income statement, balance sheet, cash flow,
          revenue segments and share capital.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <ul className="list-disc space-y-1 pl-4 text-xs text-muted-foreground">
          <li>
            Only <span className="font-medium text-foreground">.xlsx</span> files in the{" "}
            <span className="font-medium text-foreground">IDX XBRL format</span>: the financial statement
            workbook from idx.co.id, named like{" "}
            <span className="font-mono text-foreground">FinancialStatement-2026-II-HRTA.xlsx</span>. PDF, CSV
            and other spreadsheets are not supported yet.
          </li>
          <li>Maximum size {formatBytes(MAX_UPLOAD_BYTES)}. The workbook is checked before it is stored.</li>
          <li>
            Each file is stored once. If you upload a file with the same checksum again, the stored copy is used
            and the data is extracted again.
          </li>
          <li>
            Processing runs in the background and usually takes 1 to 4 minutes. Its progress shows in the table
            below.
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
            choose(e.dataTransfer.files?.[0])
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
            accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            className="sr-only"
            onChange={(e) => choose(e.target.files?.[0])}
            disabled={pending}
          />
          <FileUpIcon className="size-6 text-muted-foreground" />
          <span className="text-sm font-medium">Drop an .xlsx file here or click to choose</span>
          <span className="text-xs text-muted-foreground">IDX XBRL financial statement workbook, up to 20 MB</span>
        </label>

        {file && (
          <div className="flex items-center gap-2 rounded-lg bg-muted/50 px-3 py-2 text-sm">
            <FileSpreadsheetIcon className="size-4 shrink-0 text-emerald-500" />
            <span className="min-w-0 flex-1 truncate font-medium">{file.name}</span>
            <span className="shrink-0 text-xs text-muted-foreground tabular-nums">{formatBytes(file.size)}</span>
            <Button
              variant="ghost"
              size="icon-xs"
              aria-label="Remove file"
              onClick={() => setFile(null)}
              disabled={pending}
            >
              <XIcon />
            </Button>
          </div>
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

        <Button onClick={upload} disabled={!file || pending} className="self-start">
          {pending ? <LoaderIcon className="animate-spin" /> : <FileUpIcon />}
          {pending ? "Uploading..." : "Upload and ingest"}
        </Button>
      </CardContent>
    </Card>
  )
}
