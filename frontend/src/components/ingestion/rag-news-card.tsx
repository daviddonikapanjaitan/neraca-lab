"use client"

import { useState } from "react"
import { CircleAlertIcon, CircleCheckIcon, LoaderIcon, NewspaperIcon } from "lucide-react"

import { CompanyPicker } from "@/components/ingestion/company-picker"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { requestJson } from "@/lib/client-api"
import {
  daysInRange,
  presetRange,
  RANGE_PRESETS,
  rangeProblem,
  type RangePreset,
} from "@/lib/date-range"
import { formatDate } from "@/lib/format"
import type { CompanySummary, Exchange, IngestionJob } from "@/lib/types"
import { cn } from "@/lib/utils"

type Notice = { tone: "success" | "error"; text: string }

/**
 * Queues the news ingestion of one company for a date range: the backend collects the articles published in the
 * range, reads them and stores them in the RAG vector store (pgvector), linked to the company.
 */
export function RagNewsCard({
  exchanges,
  companiesByExchange,
  tavilyEnabled,
  maxArticles,
  today,
  onSubmitted,
}: {
  exchanges: Exchange[]
  companiesByExchange: Record<string, CompanySummary[]>
  /** the Tavily news search is configured (searches by date range besides the news sites) */
  tavilyEnabled: boolean
  /** articles read per job (neracalab.rag.news-max-articles) */
  maxArticles: number
  /** today in Jakarta (yyyy-mm-dd), from the server so both renders agree */
  today: string
  onSubmitted: (job: IngestionJob) => void
}) {
  const [exchange, setExchange] = useState<string | null>(exchanges[0]?.code ?? null)
  const [ticker, setTicker] = useState<string | null>(null)
  const [preset, setPreset] = useState<RangePreset>("this-month")
  const initial = presetRange("this-month", today)!
  const [from, setFrom] = useState(initial.from)
  const [to, setTo] = useState(initial.to)
  const [pending, setPending] = useState(false)
  const [notice, setNotice] = useState<Notice | null>(null)

  const problem = rangeProblem(from, to, today)

  function choosePreset(value: RangePreset) {
    setPreset(value)
    setNotice(null)
    const range = presetRange(value, today)
    if (range) {
      setFrom(range.from)
      setTo(range.to)
    }
  }

  async function submit() {
    if (!exchange || !ticker || problem || pending) return
    setPending(true)
    setNotice(null)
    try {
      const { status, body: job } = await requestJson<IngestionJob>("/api/rag/news", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ exchange, ticker, from, to }),
      })
      setNotice({
        tone: "success",
        text:
          status === 202
            ? `Queued the ${ticker} news from ${formatDate(from)} to ${formatDate(to)}.`
            : `A news ingestion for ${ticker} is already queued or running. Follow it in the table below.`,
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
          <NewspaperIcon className="size-4 text-primary" />
          News ingestion
        </CardTitle>
        <CardDescription>
          Collects the news of a company published in a date range and stores every article in the RAG vector store
          (pgvector), linked to the company.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <ul className="list-disc space-y-1 pl-4 text-xs text-muted-foreground">
          <li>
            Sources: EmitenNews, Investor.id, IDX Channel and Pasardana
            {tavilyEnabled ? ", plus the Tavily news search" : ""}. IDX Channel and Pasardana list only their latest
            articles, so older ranges come mostly from EmitenNews and Investor.id.
          </li>
          <li>
            Dates are in Jakarta time and include both ends. At most 366 days per job and up to {maxArticles}{" "}
            articles (newest first).
          </li>
          <li>Articles already stored for the company are skipped, so running a range again only adds new ones.</li>
          <li>Runs in the background. Sites are read slowly on purpose, so a month of news takes a few minutes.</li>
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

        <div className="flex flex-col gap-1.5">
          <span className="text-xs font-medium text-muted-foreground">Published</span>
          <div className="flex flex-wrap gap-1.5" role="radiogroup" aria-label="Date range">
            {RANGE_PRESETS.map((p) => (
              <Button
                key={p.value}
                type="button"
                size="sm"
                role="radio"
                aria-checked={preset === p.value}
                variant={preset === p.value ? "default" : "outline"}
                onClick={() => choosePreset(p.value)}
                disabled={pending}
              >
                {p.label}
              </Button>
            ))}
          </div>
        </div>

        <div className="grid gap-2 sm:grid-cols-2">
          <label className="flex flex-col gap-1.5">
            <span className="text-xs font-medium text-muted-foreground">From</span>
            <Input
              type="date"
              value={from}
              max={today}
              onChange={(e) => {
                setFrom(e.target.value)
                setPreset("custom")
                setNotice(null)
              }}
              disabled={pending}
              aria-invalid={problem !== null}
            />
          </label>
          <label className="flex flex-col gap-1.5">
            <span className="text-xs font-medium text-muted-foreground">To</span>
            <Input
              type="date"
              value={to}
              max={today}
              onChange={(e) => {
                setTo(e.target.value)
                setPreset("custom")
                setNotice(null)
              }}
              disabled={pending}
              aria-invalid={problem !== null}
            />
          </label>
        </div>

        <p className={cn("text-xs", problem ? "text-destructive" : "text-muted-foreground")}>
          {problem ??
            `${formatDate(from)} to ${formatDate(to)} · ${daysInRange(from, to)} ${daysInRange(from, to) === 1 ? "day" : "days"}`}
        </p>

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

        <Button onClick={submit} disabled={!exchange || !ticker || problem !== null || pending} className="self-start">
          {pending ? <LoaderIcon className="animate-spin" /> : <NewspaperIcon />}
          {pending ? "Queuing..." : "Ingest news"}
        </Button>
      </CardContent>
    </Card>
  )
}
