"use client"

import { useRef, useState } from "react"
import {
  CircleAlertIcon,
  CircleCheckIcon,
  ClipboardCopyIcon,
  ExternalLinkIcon,
  FileJsonIcon,
  FileUpIcon,
  IdCardIcon,
  LoaderIcon,
  TriangleAlertIcon,
  UploadIcon,
  XIcon,
} from "lucide-react"

import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { requestJson } from "@/lib/client-api"
import { IDX_HOME, IDX_PROFILE_COLLECTOR, type ProfileStatus, type ProfileUploadResult } from "@/lib/company-profile"
import { EMPTY, formatDate, formatNumber, formatTimestamp } from "@/lib/format"
import { MAX_UPLOAD_BYTES } from "@/lib/ingestion"
import { cn } from "@/lib/utils"

type Notice = { tone: "success" | "error"; text: string }

function fileProblem(file: File): string | null {
  if (!/\.json$/i.test(file.name)) return `${file.name} is not a .json file.`
  if (file.size > MAX_UPLOAD_BYTES) return `${file.name} exceeds the limit of 50 MB.`
  if (file.size === 0) return `${file.name} is empty.`
  return null
}

/**
 * IDX company profiles (idx.co.id): listing date (IPO date), listing board, IDX-IC sector, main business activity
 * and the dividend history. idx.co.id blocks automated clients (Cloudflare), so the data is collected in the
 * user's browser with the collector script and uploaded here; the backend stores it at once.
 */
export function CompanyProfilesCard({ initial }: { initial: ProfileStatus }) {
  const input = useRef<HTMLInputElement>(null)
  const [status, setStatus] = useState(initial)
  const [file, setFile] = useState<File | null>(null)
  const [pending, setPending] = useState(false)
  const [notice, setNotice] = useState<Notice | null>(null)
  const [copied, setCopied] = useState(false)
  const [result, setResult] = useState<ProfileUploadResult | null>(initial.lastUpload)

  function choose(selected: File | null | undefined) {
    if (!selected) return
    const problem = fileProblem(selected)
    setFile(problem ? null : selected)
    setNotice(problem ? { tone: "error", text: problem } : null)
    if (input.current) input.current.value = ""
  }

  async function copyScript() {
    try {
      await navigator.clipboard.writeText(IDX_PROFILE_COLLECTOR)
      setCopied(true)
      setTimeout(() => setCopied(false), 3000)
    } catch {
      setNotice({ tone: "error", text: "The browser did not allow copying. Open the collector script below and copy it by hand." })
    }
  }

  async function upload() {
    if (!file || pending) return
    setPending(true)
    setNotice(null)
    try {
      const body = new FormData()
      body.append("file", file, file.name)
      body.append("exchange", status.exchange)
      const { body: uploaded } = await requestJson<ProfileUploadResult>("/api/companies/profiles/upload", {
        method: "POST",
        body,
      })
      setResult(uploaded)
      setNotice({
        tone: "success",
        text: uploaded.duplicate
          ? `${file.name} was uploaded before (same checksum): nothing changed. Its result is shown below.`
          : `Stored ${file.name}: ${uploaded.updated} companies updated, ${uploaded.listingDates} listing dates, ${uploaded.dividends} dividends.`,
      })
      setFile(null)
      try {
        const { body: fresh } = await requestJson<ProfileStatus>(
          `/api/companies/profiles/status?exchange=${encodeURIComponent(status.exchange)}`
        )
        setStatus(fresh)
      } catch {
        // the counts update on the next page load
      }
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
          <IdCardIcon className="size-4 text-primary" />
          IDX company profiles · idx.co.id
        </CardTitle>
        <CardDescription>
          Listing date (IPO date), listing board, IDX-IC sector, main business activity and the dividend history of
          every {status.exchange} company, from IDX&apos;s company profiles. Shown on each company&apos;s Profile tab.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <ul className="list-disc space-y-1 pl-4 text-xs text-muted-foreground">
          <li>
            {formatNumber(status.active, 0)} active companies · {formatNumber(status.withListingDate, 0)} with a listing
            date · {formatNumber(status.withBoard, 0)} with a board · {formatNumber(status.withIdxSector, 0)} with an
            IDX-IC sector · {formatNumber(status.idxDividends, 0)} IDX dividends of{" "}
            {formatNumber(status.withIdxDividends, 0)} companies.
          </li>
          <li>
            {status.lastUpload
              ? `Last upload ${formatTimestamp(status.lastUpload.uploadedAt)}${status.lastUpload.fileName ? ` · ${status.lastUpload.fileName}` : ""}${status.lastUpload.uploadedBy ? ` · ${status.lastUpload.uploadedBy}` : ""}.`
              : "No profile uploaded yet."}
          </li>
          <li>
            idx.co.id blocks automated clients, so the server cannot fetch it: the data is fetched in your browser by
            the collector script, then uploaded here.
          </li>
        </ul>

        <ol className="flex flex-col gap-2 text-sm">
          <li className="flex flex-wrap items-center gap-2">
            <span className="text-muted-foreground">1.</span>
            <a
              href={IDX_HOME}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1 text-primary hover:underline"
            >
              Open idx.co.id <ExternalLinkIcon className="size-3" />
            </a>
            <span className="text-xs text-muted-foreground">(any page of www.idx.co.id, after it has loaded)</span>
          </li>
          <li className="flex flex-wrap items-center gap-2">
            <span className="text-muted-foreground">2.</span>
            <Button variant="outline" size="sm" onClick={copyScript}>
              {copied ? <CircleCheckIcon /> : <ClipboardCopyIcon />}
              {copied ? "Copied" : "Copy collector script"}
            </Button>
            <span className="text-xs text-muted-foreground">
              paste it into that tab&apos;s console (F12 › Console) and press Enter: it fetches every profile and its
              dividends (about 8 minutes) and saves company-profiles-yyyy-mm-dd.json
            </span>
          </li>
          <li className="flex items-start gap-2">
            <span className="text-muted-foreground">3.</span>
            <span>Upload the saved file:</span>
          </li>
        </ol>

        <label
          className={cn(
            "flex cursor-pointer flex-col items-center justify-center gap-1.5 rounded-xl border border-dashed px-4 py-5 text-center transition-colors hover:bg-muted/50",
            pending && "pointer-events-none opacity-60"
          )}
          onDragOver={(e) => e.preventDefault()}
          onDrop={(e) => {
            e.preventDefault()
            choose(e.dataTransfer.files?.[0])
          }}
        >
          <input
            ref={input}
            type="file"
            accept=".json,application/json"
            className="sr-only"
            onChange={(e) => choose(e.target.files?.[0])}
            disabled={pending}
          />
          <FileUpIcon className="size-6 text-muted-foreground" />
          <span className="text-sm font-medium">Drop the .json file here or click to choose</span>
          <span className="text-xs text-muted-foreground">
            The collector&apos;s file, or IDX&apos;s GetCompanyProfiles / GetCompanyProfilesDetail response, up to 50 MB
          </span>
        </label>

        {file && (
          <div className="flex items-center gap-2 rounded-lg bg-muted/50 px-3 py-2 text-sm">
            <FileJsonIcon className="size-4 shrink-0 text-amber-500" />
            <span className="min-w-0 flex-1 truncate font-medium">{file.name}</span>
            <Button variant="ghost" size="icon-xs" aria-label="Remove file" onClick={() => setFile(null)} disabled={pending}>
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
          {pending ? <LoaderIcon className="animate-spin" /> : <UploadIcon />}
          {pending ? "Uploading..." : "Upload profiles"}
        </Button>

        {result && <UploadSummary result={result} />}

        {status.missingTotal > 0 && (
          <details className="text-xs">
            <summary className="cursor-pointer text-muted-foreground">
              {formatNumber(status.missingTotal, 0)} companies without a listing date
              {status.missingTotal > status.missing.length ? ` (first ${status.missing.length})` : ""}
            </summary>
            <div className="mt-2 overflow-x-auto">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Ticker</TableHead>
                    <TableHead>Company</TableHead>
                    <TableHead>First price</TableHead>
                    <TableHead>Yahoo first trade</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {status.missing.map((m) => (
                    <TableRow key={m.ticker}>
                      <TableCell className="font-mono">
                        {m.ticker}
                        {!m.active && <span className="ml-1 text-muted-foreground">(inactive)</span>}
                      </TableCell>
                      <TableCell className="max-w-[220px] truncate">{m.companyName}</TableCell>
                      <TableCell>{formatDate(m.firstPriceDate)}</TableCell>
                      <TableCell>{formatDate(m.yahooFirstTradeDate)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          </details>
        )}

        <details className="text-xs">
          <summary className="cursor-pointer text-muted-foreground">Collector script</summary>
          <pre className="mt-2 max-h-64 overflow-auto rounded-lg bg-muted/50 p-3 font-mono text-[11px] leading-relaxed whitespace-pre">
            {IDX_PROFILE_COLLECTOR}
          </pre>
        </details>
      </CardContent>
    </Card>
  )
}

function UploadSummary({ result }: { result: ProfileUploadResult }) {
  return (
    <div className="flex flex-col gap-2 rounded-lg border p-3 text-xs">
      <p className="font-medium">
        {result.duplicate ? "Same file as an earlier upload" : "Last result"}
        {result.fileName ? ` · ${result.fileName}` : ""}
      </p>
      <p className="text-muted-foreground tabular-nums">
        {formatNumber(result.profiles, 0)} profiles · {formatNumber(result.details, 0)} details ·{" "}
        {formatNumber(result.updated, 0)} updated · {formatNumber(result.unchanged, 0)} unchanged ·{" "}
        {formatNumber(result.listingDates, 0)} listing dates set · {formatNumber(result.dividends, 0)} dividends ·{" "}
        {formatNumber(result.unknownTickers.length, 0)} unknown tickers · {formatNumber(result.conflicts.length, 0)}{" "}
        conflicts
      </p>
      {result.unknownTickers.length > 0 && (
        <details>
          <summary className="cursor-pointer text-muted-foreground">
            Unknown tickers (not companies in the database, not created)
          </summary>
          <p className="mt-1 font-mono break-words">{result.unknownTickers.join(", ")}</p>
        </details>
      )}
      {result.conflicts.length > 0 && (
        <details open>
          <summary className="cursor-pointer text-amber-600 dark:text-amber-400">
            Conflicts (the stored value was kept)
          </summary>
          <ul className="mt-1 list-disc space-y-0.5 pl-4">
            {result.conflicts.map((c) => (
              <li key={`${c.ticker}-${c.field}`}>
                <span className="font-mono">{c.ticker}</span> {c.field}: stored {c.stored}, IDX {c.file}
              </li>
            ))}
          </ul>
        </details>
      )}
      {result.checks.length > 0 && (
        <details>
          <summary className="flex cursor-pointer items-center gap-1 text-muted-foreground">
            <TriangleAlertIcon className="size-3.5" /> {result.checks.length} listing dates to check
          </summary>
          <ul className="mt-1 list-disc space-y-0.5 pl-4">
            {result.checks.map((c) => (
              <li key={c.ticker}>
                <span className="font-mono">{c.ticker}</span> listed {formatDate(c.listingDate)}: {c.note}
              </li>
            ))}
          </ul>
        </details>
      )}
      {result.warnings.length > 0 && (
        <details>
          <summary className="cursor-pointer text-muted-foreground">{result.warnings.length} warnings</summary>
          <ul className="mt-1 list-disc space-y-0.5 pl-4">
            {result.warnings.map((w, i) => (
              <li key={i}>{w}</li>
            ))}
          </ul>
        </details>
      )}
      {result.uploadedAt && <p className="text-muted-foreground">{formatTimestamp(result.uploadedAt) ?? EMPTY}</p>}
    </div>
  )
}
