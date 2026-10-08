"use client"

import { useState } from "react"
import { useRouter } from "next/navigation"
import {
  CircleAlertIcon,
  DatabaseIcon,
  FileTextIcon,
  LineChartIcon,
  LoaderIcon,
  MicroscopeIcon,
  NewspaperIcon,
  PlayIcon,
  TriangleAlertIcon,
} from "lucide-react"

import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { formatDate } from "@/lib/format"
import { jsonBody, requestJson } from "@/lib/client-api"
import { formatUsd } from "@/lib/screening"
import type { AnalysisOptions, AnalysisRun, InvestorAgentCode } from "@/lib/types"
import { cn } from "@/lib/utils"

/**
 * Starts an analysis of one stock: a company of the companies table (what the database holds for it is shown) and
 * the investor agents (all six by default). The analysis is queued and the report page follows it.
 */
export function AnalysisForm({ options }: { options: AnalysisOptions }) {
  const router = useRouter()
  const [ticker, setTicker] = useState<string | null>(null)
  const [agents, setAgents] = useState<InvestorAgentCode[]>(options.agents.map((a) => a.code as InvestorAgentCode))
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const company = options.companies.find((c) => c.ticker === ticker) ?? null
  const tickerItems = options.companies.map((c) => ({ value: c.ticker, label: `${c.ticker} · ${c.companyName}` }))
  const canSubmit = company !== null && agents.length > 0 && !pending

  function toggle(code: InvestorAgentCode, checked: boolean) {
    setAgents((current) =>
      checked
        ? options.agents.map((a) => a.code as InvestorAgentCode).filter((c) => c === code || current.includes(c))
        : current.filter((c) => c !== code)
    )
    setError(null)
  }

  async function submit() {
    if (!canSubmit || !company) return
    setPending(true)
    setError(null)
    try {
      const { body: run } = await requestJson<AnalysisRun>(
        "/api/analyses",
        jsonBody("POST", { exchange: company.exchange, ticker: company.ticker, agents })
      )
      router.push(`/screening/analysis/${run.id}`)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      setPending(false)
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <MicroscopeIcon className="size-4 text-primary" />
          New analysis
        </CardTitle>
        <CardDescription>
          A research agent reads the stock&apos;s own filings and news in the database; then each investor agent judges
          it from the stored financial statements, market data and that research, the answers are reviewed, and a
          final synthesis writes the overall view.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div className="flex flex-col gap-1.5 sm:max-w-md">
          <span className="text-xs font-medium text-muted-foreground">Stock</span>
          <Select items={tickerItems} value={ticker} onValueChange={(v) => setTicker(v)}>
            <SelectTrigger aria-label="Stock" className="w-full" disabled={pending || tickerItems.length === 0}>
              <SelectValue placeholder={tickerItems.length === 0 ? "No companies stored" : "Choose a stock"} />
            </SelectTrigger>
            <SelectContent>
              {tickerItems.map((t) => (
                <SelectItem key={t.value} value={t.value}>
                  {t.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        {company && (
          <div className="grid gap-2 rounded-lg bg-muted/50 p-2.5 text-xs sm:grid-cols-2">
            <DataLine
              icon={DatabaseIcon}
              ok={company.periods > 0}
              text={
                company.periods > 0
                  ? `${company.periods} reporting periods, latest ${company.latestPeriod}`
                  : "No financial statements stored (Ingestion > IDX XBRL)"
              }
            />
            <DataLine
              icon={LineChartIcon}
              ok={company.marketDataDate !== null}
              text={
                company.marketDataDate
                  ? `Market data of ${formatDate(company.marketDataDate)} (quantitative scorecards)`
                  : "No market data: no quantitative scorecards (Ingestion > Screening Data IDX)"
              }
            />
            <DataLine
              icon={FileTextIcon}
              ok={company.pdfDocuments > 0}
              text={
                company.pdfDocuments > 0
                  ? `${company.pdfDocuments} PDF ${company.pdfDocuments === 1 ? "document" : "documents"} to research`
                  : "No PDF documents (Ingestion > PDF Documents (RAG))"
              }
            />
            <DataLine
              icon={NewspaperIcon}
              ok={company.newsDocuments > 0}
              text={
                company.newsDocuments > 0
                  ? `${company.newsDocuments} news ${company.newsDocuments === 1 ? "article" : "articles"} to research`
                  : "No news articles (Ingestion > News (RAG))"
              }
            />
          </div>
        )}

        <fieldset className="flex flex-col gap-2" disabled={pending}>
          <div className="flex items-center justify-between gap-2">
            <legend className="text-xs font-medium text-muted-foreground">
              Investor agents ({agents.length} of {options.agents.length} selected)
            </legend>
            <div className="flex gap-1">
              <Button
                type="button"
                variant="ghost"
                size="xs"
                onClick={() => setAgents(options.agents.map((a) => a.code as InvestorAgentCode))}
              >
                Select all
              </Button>
              <Button type="button" variant="ghost" size="xs" onClick={() => setAgents([])}>
                Clear
              </Button>
            </div>
          </div>
          <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
            {options.agents.map((agent) => {
              const code = agent.code as InvestorAgentCode
              const checked = agents.includes(code)
              return (
                <label
                  key={code}
                  className={cn(
                    "flex cursor-pointer items-start gap-2.5 rounded-lg border p-2.5 text-sm transition-colors",
                    checked ? "border-primary/50 bg-primary/5" : "hover:bg-muted/50"
                  )}
                >
                  <Checkbox
                    checked={checked}
                    onCheckedChange={(value) => toggle(code, value)}
                    className="mt-0.5"
                    aria-label={agent.label}
                  />
                  <span className="min-w-0">
                    <span className="block font-medium">{agent.label}</span>
                    <span className="block text-xs text-muted-foreground">{agent.description}</span>
                  </span>
                </label>
              )
            })}
          </div>
          {agents.length === 0 && <span className="text-xs text-destructive">Choose at least one agent.</span>}
        </fieldset>

        <p className="rounded-lg bg-muted/50 p-2.5 text-xs text-muted-foreground">
          Cost cap per analysis: {formatUsd(options.budgetUsd)}.{" "}
          {options.researchModel === options.agentModel
            ? `${options.researchModel} researches and analyses`
            : `${options.researchModel} researches, ${options.agentModel} analyses`}
          , {options.synthesisModel} writes the synthesis; the report records every call and its cost.
        </p>

        {error && (
          <p role="alert" className="flex items-start gap-2 text-xs text-destructive">
            <CircleAlertIcon className="mt-px size-3.5 shrink-0" />
            <span>{error}</span>
          </p>
        )}

        <Button onClick={submit} disabled={!canSubmit} className="self-start">
          {pending ? <LoaderIcon className="animate-spin" /> : <PlayIcon />}
          {pending ? "Starting..." : "Start analysis"}
        </Button>
      </CardContent>
    </Card>
  )
}

function DataLine({ icon: Icon, ok, text }: { icon: typeof DatabaseIcon; ok: boolean; text: string }) {
  return (
    <span className={cn("flex items-start gap-1.5", ok ? "text-foreground" : "text-amber-700 dark:text-amber-400")}>
      {ok ? <Icon className="mt-px size-3.5 shrink-0 text-primary" /> : <TriangleAlertIcon className="mt-px size-3.5 shrink-0" />}
      {text}
    </span>
  )
}
