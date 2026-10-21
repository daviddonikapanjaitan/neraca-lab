"use client"

import { useState } from "react"
import { useRouter } from "next/navigation"
import {
  BrainCircuitIcon,
  CircleAlertIcon,
  CoinsIcon,
  DatabaseIcon,
  LandmarkIcon,
  LoaderIcon,
  PlayIcon,
  TriangleAlertIcon,
} from "lucide-react"

import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { jsonBody, requestJson } from "@/lib/client-api"
import { formatDate } from "@/lib/format"
import { macroAnalysisHref, type MacroAgentCode, type MacroAnalysisOptions, type MacroAnalysisRun } from "@/lib/macro-analysis"
import { formatUsd } from "@/lib/screening"
import { cn } from "@/lib/utils"

/**
 * Starts an AI macro analysis: a country (Indonesia; the United States and China later), one of its indexes and the
 * macro agents (all four by default). The analysis is queued and the report page follows it.
 */
export function MacroAnalysisForm({ options }: { options: MacroAnalysisOptions }) {
  const router = useRouter()
  const available = options.countries.filter((c) => c.available)
  const [country, setCountry] = useState<string | null>(available[0]?.code ?? null)
  const indexes = options.countries.find((c) => c.code === country)?.indexes ?? []
  const [index, setIndex] = useState<string | null>(indexes[0]?.code ?? null)
  const [agents, setAgents] = useState<MacroAgentCode[]>(options.agents.map((a) => a.code as MacroAgentCode))
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const chosen = indexes.find((i) => i.code === index) ?? null
  const canSubmit = country !== null && chosen !== null && agents.length > 0 && !pending
  const data = options.data

  function toggle(code: MacroAgentCode, checked: boolean) {
    setAgents((current) =>
      checked
        ? options.agents.map((a) => a.code as MacroAgentCode).filter((c) => c === code || current.includes(c))
        : current.filter((c) => c !== code)
    )
    setError(null)
  }

  async function submit() {
    if (!canSubmit || !chosen) return
    setPending(true)
    setError(null)
    try {
      const { body: run } = await requestJson<MacroAnalysisRun>(
        "/api/macro/analyses",
        jsonBody("POST", { country, index: chosen.code, agents })
      )
      router.push(macroAnalysisHref(run.id))
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      setPending(false)
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <BrainCircuitIcon className="size-4 text-primary" />
          New macro analysis
        </CardTitle>
        <CardDescription>
          A research agent reads the history of the stored macro series, commodities, currencies and indexes; then
          each macro agent judges the country through its own lens, from the Macro page&apos;s rules and that research,
          the answers are reviewed, and a final synthesis writes the investability score and the overall view.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div className="grid gap-3 sm:max-w-xl sm:grid-cols-2">
          <div className="flex flex-col gap-1.5">
            <span className="text-xs font-medium text-muted-foreground">Country</span>
            <Select
              items={options.countries.map((c) => ({ value: c.code, label: c.name }))}
              value={country}
              onValueChange={(v) => {
                const next = options.countries.find((c) => c.code === v)
                if (v && next?.available) {
                  setCountry(v)
                  setIndex(next.indexes[0]?.code ?? null)
                }
              }}
            >
              <SelectTrigger aria-label="Country" className="w-full" disabled={pending}>
                <SelectValue placeholder="Choose a country" />
              </SelectTrigger>
              <SelectContent>
                {options.countries.map((c) => (
                  <SelectItem key={c.code} value={c.code} disabled={!c.available}>
                    {c.name}
                    {!c.available && <span className="ml-1 text-xs text-muted-foreground">(coming later)</span>}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="flex flex-col gap-1.5">
            <span className="text-xs font-medium text-muted-foreground">Index</span>
            <Select
              items={indexes.map((i) => ({ value: i.code, label: i.name }))}
              value={index}
              onValueChange={(v) => v && setIndex(v)}
            >
              <SelectTrigger aria-label="Index" className="w-full" disabled={pending || indexes.length === 0}>
                <SelectValue placeholder="Choose an index" />
              </SelectTrigger>
              <SelectContent>
                {indexes.map((i) => (
                  <SelectItem key={i.code} value={i.code}>
                    {i.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        </div>

        <div className="grid gap-2 rounded-lg bg-muted/50 p-2.5 text-xs sm:grid-cols-2">
          <DataLine
            icon={LandmarkIcon}
            ok={chosen !== null && chosen.rows > 0}
            text={
              chosen && chosen.rows > 0
                ? `${chosen.code}: ${chosen.rows.toLocaleString("en-US")} days, latest ${formatDate(chosen.lastDate)}`
                : "No index data (Ingestion > Price Ingestion)"
            }
          />
          <DataLine
            icon={DatabaseIcon}
            ok={data.macroSeries > 0}
            text={
              data.macroSeries > 0
                ? `${data.macroSeries} of ${data.macroSeriesTotal} macro series stored`
                : "No macro series stored (Ingestion > Macro)"
            }
          />
          <DataLine
            icon={CoinsIcon}
            ok={data.commodities > 0}
            text={
              data.commodities > 0
                ? `${data.commodities} commodities with prices (terms of trade)`
                : "No commodity prices (Ingestion > Price Ingestion, Commodities)"
            }
          />
          <DataLine
            icon={CoinsIcon}
            ok={data.currencyPairs > 0}
            text={
              data.currencyPairs > 0
                ? `${data.currencyPairs} currency ${data.currencyPairs === 1 ? "pair" : "pairs"} with prices`
                : "No currency pairs (Ingestion > Price Ingestion, Forex)"
            }
          />
        </div>

        <fieldset className="flex flex-col gap-2" disabled={pending}>
          <div className="flex items-center justify-between gap-2">
            <legend className="text-xs font-medium text-muted-foreground">
              Macro agents ({agents.length} of {options.agents.length} selected)
            </legend>
            <div className="flex gap-1">
              <Button
                type="button"
                variant="ghost"
                size="xs"
                onClick={() => setAgents(options.agents.map((a) => a.code as MacroAgentCode))}
              >
                Select all
              </Button>
              <Button type="button" variant="ghost" size="xs" onClick={() => setAgents([])}>
                Clear
              </Button>
            </div>
          </div>
          <div className="grid gap-2 sm:grid-cols-2">
            {options.agents.map((agent) => {
              const code = agent.code as MacroAgentCode
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
          {pending ? "Starting..." : "Start macro analysis"}
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
