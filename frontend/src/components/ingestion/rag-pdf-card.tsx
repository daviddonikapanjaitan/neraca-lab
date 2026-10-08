"use client"

import { useRef, useState } from "react"
import { CircleAlertIcon, CircleCheckIcon, FileTextIcon, FileUpIcon, LoaderIcon, XIcon } from "lucide-react"

import { CompanyPicker, tickerInFileName } from "@/components/ingestion/company-picker"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { requestJson } from "@/lib/client-api"
import { formatBytes, MAX_UPLOAD_BYTES } from "@/lib/ingestion"
import type { CompanySummary, Exchange, IngestionJob } from "@/lib/types"
import { cn } from "@/lib/utils"

type Notice = { tone: "success" | "error"; text: string }

/** Why a file cannot be uploaded, or null when it can. */
function fileProblem(file: File): string | null {
  if (!file.name.toLowerCase().endsWith(".pdf")) {
    return `"${file.name}" is not a .pdf file. Only PDF documents are accepted here.`
  }
  if (file.size === 0) return `"${file.name}" is empty.`
  if (file.size > MAX_UPLOAD_BYTES) {
    return `"${file.name}" is ${formatBytes(file.size)}; the limit is ${formatBytes(MAX_UPLOAD_BYTES)}.`
  }
  return null
}

/**
 * Upload of a PDF document of a company into the RAG vector store: the backend reads its text, splits it into
 * chunks, embeds them and stores them in pgvector, linked to the company.
 */
export function RagPdfCard({
  exchanges,
  companiesByExchange,
  onSubmitted,
}: {
  exchanges: Exchange[]
  companiesByExchange: Record<string, CompanySummary[]>
  onSubmitted: (job: IngestionJob) => void
}) {
  const input = useRef<HTMLInputElement>(null)
  const [exchange, setExchange] = useState<string | null>(exchanges[0]?.code ?? null)
  const [ticker, setTicker] = useState<string | null>(null)
  const [file, setFile] = useState<File | null>(null)
  const [dragging, setDragging] = useState(false)
  const [pending, setPending] = useState(false)
  const [notice, setNotice] = useState<Notice | null>(null)

  const companies = exchange ? companiesByExchange[exchange] ?? [] : []

  function choose(selected: File | null | undefined) {
    if (!selected) return
    const problem = fileProblem(selected)
    if (problem) {
      setFile(null)
      setNotice({ tone: "error", text: problem })
    } else {
      setFile(selected)
      setNotice(null)
      // IDX file names end with the ticker (FinancialStatement-2025-Tahunan-HRTA.pdf): pick that company
      const named = tickerInFileName(selected.name, companies)
      if (named) setTicker(named)
    }
    // the same file can be chosen again after clearing it
    if (input.current) input.current.value = ""
  }

  async function upload() {
    if (!file || !exchange || !ticker || pending) return
    setPending(true)
    setNotice(null)
    try {
      const body = new FormData()
      body.append("file", file, file.name)
      body.append("exchange", exchange)
      body.append("ticker", ticker)
      const { status, body: job } = await requestJson<IngestionJob>("/api/rag/pdf", { method: "POST", body })
      setNotice({
        tone: "success",
        text:
          status === 202
            ? `Queued ${file.name} for ${ticker}. Its text is embedded and stored in the vector store in the background.`
            : `${file.name} is already being ingested for ${ticker}. Follow the job in the table below.`,
      })
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
          <FileTextIcon className="size-4 text-primary" />
          PDF document upload
        </CardTitle>
        <CardDescription>
          Stores the text of a PDF in the RAG vector store (pgvector), linked to the company, so it can be retrieved
          for AI answers.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <ul className="list-disc space-y-1 pl-4 text-xs text-muted-foreground">
          <li>
            Only <span className="font-medium text-foreground">.pdf</span> files with a text layer, such as the
            financial statement PDFs from idx.co.id. Scanned PDFs (images only) and password-protected PDFs are
            rejected.
          </li>
          <li>
            Choose the company the document belongs to. A file name ending with the ticker (
            <span className="font-mono text-foreground">FinancialStatement-2025-Tahunan-HRTA.pdf</span>) selects it
            automatically.
          </li>
          <li>
            Maximum size {formatBytes(MAX_UPLOAD_BYTES)}. Uploading the same file again for the same company
            replaces its stored chunks.
          </li>
          <li>Processing runs in the background and usually takes under a minute.</li>
        </ul>

        <CompanyPicker
          exchanges={exchanges}
          companiesByExchange={companiesByExchange}
          exchange={exchange}
          ticker={ticker}
          onExchange={(code) => {
            setExchange(code)
            setTicker(null)
            setNotice(null)
          }}
          onTicker={(value) => {
            setTicker(value)
            setNotice(null)
          }}
          disabled={pending}
        />

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
            accept=".pdf,application/pdf"
            className="sr-only"
            onChange={(e) => choose(e.target.files?.[0])}
            disabled={pending}
          />
          <FileUpIcon className="size-6 text-muted-foreground" />
          <span className="text-sm font-medium">Drop a .pdf file here or click to choose</span>
          <span className="text-xs text-muted-foreground">PDF with text, up to 20 MB</span>
        </label>

        {file && (
          <div className="flex items-center gap-2 rounded-lg bg-muted/50 px-3 py-2 text-sm">
            <FileTextIcon className="size-4 shrink-0 text-rose-500" />
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

        <Button onClick={upload} disabled={!file || !exchange || !ticker || pending} className="self-start">
          {pending ? <LoaderIcon className="animate-spin" /> : <FileUpIcon />}
          {pending ? "Uploading..." : "Upload and embed"}
        </Button>
      </CardContent>
    </Card>
  )
}
