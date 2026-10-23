"use client"

import { useRef, useState } from "react"
import { CircleAlertIcon, CircleCheckIcon, FileTextIcon, FileUpIcon, GlobeIcon, LoaderIcon, XIcon } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { requestJson } from "@/lib/client-api"
import { formatBytes, MAX_UPLOAD_BYTES } from "@/lib/ingestion"
import type { IngestionJob, MacroDocumentCountry, MacroDocumentType, MacroDocumentTypeOption } from "@/lib/types"
import { cn } from "@/lib/utils"

type Notice = { tone: "success" | "error"; text: string }

/** Longest title and issuer (as the backend checks). */
const MAX_TITLE = 300
const MAX_ISSUER = 100

/** Publishers of macro documents offered as suggestions (any other can be typed). */
const ISSUERS = [
  "Bank Indonesia",
  "Ministry of Finance",
  "OJK",
  "BPS",
  "LPS",
  "KSSK",
  "Bappenas",
  "IMF",
  "World Bank",
  "ADB",
  "OECD",
  "AMRO",
  "Federal Reserve",
]

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

/** "Monetary-Policy-Review-August-2026.pdf" -> "Monetary Policy Review August 2026" */
function titleOf(fileName: string): string {
  return fileName
    .replace(/\.pdf$/i, "")
    .replace(/[_-]+/g, " ")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, MAX_TITLE)
}

/**
 * Upload of a macro PDF of a country (Bank Indonesia, the Ministry of Finance, the IMF, ...) into the RAG vector
 * store: the backend reads its text, splits it into chunks, embeds them and stores them with the country, its type and
 * publication date. The research agent of Macro › AI Analysis searches the documents of the analysed country that were
 * published by the analysis' as-of date.
 */
export function RagMacroPdfCard({
  countries,
  documentTypes,
  today,
  onSubmitted,
}: {
  countries: MacroDocumentCountry[]
  documentTypes: MacroDocumentTypeOption[]
  /** today in Jakarta (yyyy-mm-dd): the latest publication date */
  today: string
  onSubmitted: (job: IngestionJob) => void
}) {
  const input = useRef<HTMLInputElement>(null)
  const [country, setCountry] = useState<string | null>(countries.find((c) => c.available)?.code ?? null)
  const [documentType, setDocumentType] = useState<MacroDocumentType | null>(null)
  const [publishedOn, setPublishedOn] = useState("")
  const [title, setTitle] = useState("")
  /** the title was typed, not taken from the file name */
  const [titleTyped, setTitleTyped] = useState(false)
  const [issuer, setIssuer] = useState("")
  const [file, setFile] = useState<File | null>(null)
  const [dragging, setDragging] = useState(false)
  const [pending, setPending] = useState(false)
  const [notice, setNotice] = useState<Notice | null>(null)

  const chosenCountry = countries.find((c) => c.code === country) ?? null
  const chosenType = documentTypes.find((t) => t.code === documentType) ?? null
  const dateProblem =
    publishedOn && publishedOn > today ? `The publication date is in the future (today is ${today}).` : null
  const ready =
    file !== null &&
    chosenCountry?.available === true &&
    documentType !== null &&
    publishedOn !== "" &&
    dateProblem === null &&
    title.trim().length > 0

  function choose(selected: File | null | undefined) {
    if (!selected) return
    const problem = fileProblem(selected)
    if (problem) {
      setFile(null)
      setNotice({ tone: "error", text: problem })
    } else {
      setFile(selected)
      setNotice(null)
      if (!titleTyped) setTitle(titleOf(selected.name))
    }
    // the same file can be chosen again after clearing it
    if (input.current) input.current.value = ""
  }

  function reset() {
    setFile(null)
    setTitle("")
    setTitleTyped(false)
    setPublishedOn("")
  }

  async function upload() {
    if (!ready || !file || !country || !documentType || pending) return
    setPending(true)
    setNotice(null)
    try {
      const body = new FormData()
      body.append("file", file, file.name)
      body.append("country", country)
      body.append("documentType", documentType)
      body.append("publishedOn", publishedOn)
      body.append("title", title.trim())
      if (issuer.trim()) body.append("issuer", issuer.trim())
      const { status, body: job } = await requestJson<IngestionJob>("/api/rag/macro/pdf", { method: "POST", body })
      const name = chosenCountry?.name ?? country
      setNotice({
        tone: "success",
        text:
          status === 202
            ? `Queued ${file.name} for ${name}. Its text is embedded and stored in the vector store in the background.`
            : `${file.name} is already being ingested for ${name}. Follow the job in the table below.`,
      })
      reset()
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
          <GlobeIcon className="size-4 text-primary" />
          Macro document upload
        </CardTitle>
        <CardDescription>
          Stores the text of a macro PDF of a country in the RAG vector store, so the research agent of Macro › AI
          Analysis can cite it.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <ul className="list-disc space-y-1 pl-4 text-xs text-muted-foreground">
          <li>
            Central bank, finance ministry and IMF / World Bank / ADB reports of the country, such as the Bank Indonesia
            Monetary Policy Review, the APBN KiTa or an IMF Article IV report. The PDF needs a text layer.
          </li>
          <li>
            The <span className="font-medium text-foreground">publication date</span> is the day the document was
            released, not the period it covers. An analysis reads only the documents published by its as-of date.
          </li>
          <li>
            Only Indonesia for now; the United States and China come later. Uploading the same file again for the
            same country replaces its details and chunks.
          </li>
        </ul>

        <div className="grid gap-2 sm:grid-cols-2">
          <div className="flex flex-col gap-1.5">
            <span className="text-xs font-medium text-muted-foreground">Country</span>
            <Select
              items={countries.map((c) => ({ value: c.code, label: c.name }))}
              value={country}
              onValueChange={(v) => {
                if (v && countries.find((c) => c.code === v)?.available) {
                  setCountry(v)
                  setNotice(null)
                }
              }}
            >
              <SelectTrigger aria-label="Country" className="w-full" disabled={pending}>
                <SelectValue placeholder="Choose a country" />
              </SelectTrigger>
              <SelectContent>
                {countries.map((c) => (
                  <SelectItem key={c.code} value={c.code} disabled={!c.available}>
                    {c.name}
                    {!c.available && <span className="ml-1 text-xs text-muted-foreground">(coming later)</span>}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="flex flex-col gap-1.5">
            <span className="text-xs font-medium text-muted-foreground">Document type</span>
            <Select
              items={documentTypes.map((t) => ({ value: t.code, label: t.label }))}
              value={documentType}
              onValueChange={(v) => {
                setDocumentType((v as MacroDocumentType | null) ?? null)
                setNotice(null)
              }}
            >
              <SelectTrigger aria-label="Document type" className="w-full" disabled={pending}>
                <SelectValue placeholder="Choose a type" />
              </SelectTrigger>
              <SelectContent>
                {documentTypes.map((t) => (
                  <SelectItem key={t.code} value={t.code}>
                    {t.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        </div>
        {chosenType && <p className="-mt-1 text-xs text-muted-foreground">For example: {chosenType.examples}.</p>}

        <div className="grid gap-2 sm:grid-cols-2">
          <label className="flex flex-col gap-1.5">
            <span className="text-xs font-medium text-muted-foreground">Publication date</span>
            <Input
              type="date"
              value={publishedOn}
              max={today}
              onChange={(e) => {
                setPublishedOn(e.target.value)
                setNotice(null)
              }}
              disabled={pending}
              aria-invalid={dateProblem !== null}
            />
          </label>
          <label className="flex flex-col gap-1.5">
            <span className="text-xs font-medium text-muted-foreground">Issuer (optional)</span>
            <Input
              value={issuer}
              list="macro-issuers"
              placeholder="e.g. Bank Indonesia"
              maxLength={MAX_ISSUER}
              onChange={(e) => setIssuer(e.target.value)}
              disabled={pending}
            />
            <datalist id="macro-issuers">
              {ISSUERS.map((name) => (
                <option key={name} value={name} />
              ))}
            </datalist>
          </label>
        </div>
        {dateProblem && (
          <p role="alert" className="-mt-1 text-xs text-destructive">
            {dateProblem}
          </p>
        )}

        <label className="flex flex-col gap-1.5">
          <span className="text-xs font-medium text-muted-foreground">Title</span>
          <Input
            value={title}
            placeholder="Taken from the file name, e.g. Monetary Policy Review August 2026"
            maxLength={MAX_TITLE}
            onChange={(e) => {
              setTitle(e.target.value)
              setTitleTyped(e.target.value.trim() !== "")
            }}
            disabled={pending}
          />
        </label>

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
          <span className="text-xs text-muted-foreground">PDF with text, up to 50 MB</span>
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

        <Button onClick={upload} disabled={!ready || pending} className="self-start">
          {pending ? <LoaderIcon className="animate-spin" /> : <FileUpIcon />}
          {pending ? "Uploading..." : "Upload and embed"}
        </Button>
      </CardContent>
    </Card>
  )
}
