"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import { useRouter } from "next/navigation"
import { CartesianGrid, Line, LineChart, ReferenceLine, XAxis, YAxis } from "recharts"
import {
  CheckCircle2Icon,
  CircleAlertIcon,
  CircleIcon,
  CloudRainIcon,
  LoaderIcon,
  MinusIcon,
  TrendingDownIcon,
  TrendingUpIcon,
} from "lucide-react"

import { EmptyState } from "@/components/empty-state"
import { MacroAiPanel } from "@/components/macro/macro-ai-panel"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { ChartContainer, ChartTooltip, ChartTooltipContent, type ChartConfig } from "@/components/ui/chart"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { requestJson, RequestError } from "@/lib/client-api"
import { EMPTY, formatDate, formatNumber } from "@/lib/format"
import {
  MACRO_HREF,
  MACRO_RANGES,
  macroOutlookUrl,
  type Condition,
  type MacroAction,
  type MacroIndexRow,
  type MacroOutlook,
  type MacroPanel,
  type MacroPreset,
  type MacroRange,
  type MacroRegimeThen,
  type MacroStance,
  type MacroWeather,
  type MarketRow,
  type RegionalCurrencies,
  type SpiState,
  type Tone,
  type Verdict,
  type WeatherChart,
  type WeatherRegion,
  type WeatherRisk,
  unitLabel,
} from "@/lib/macro"
import { cn } from "@/lib/utils"

const STANCE_STYLE: Record<MacroStance | MacroAction, string> = {
  BUY: "border-emerald-600/30 bg-emerald-600/10 text-emerald-700 dark:text-emerald-400",
  ACCUMULATE: "border-sky-600/30 bg-sky-600/10 text-sky-700 dark:text-sky-400",
  WAIT: "border-amber-600/30 bg-amber-600/10 text-amber-700 dark:text-amber-400",
  AVOID: "border-rose-600/30 bg-rose-600/10 text-rose-700 dark:text-rose-400",
  SELL: "border-rose-600/30 bg-rose-600/10 text-rose-700 dark:text-rose-400",
}

const STANCE_LABEL: Record<MacroStance, string> = {
  BUY: "Buy",
  ACCUMULATE: "Accumulate",
  WAIT: "Wait",
  AVOID: "Avoid",
}

const VERDICT_STYLE: Record<Verdict, string> = {
  INVESTABLE: "text-emerald-700 dark:text-emerald-400",
  SELECTIVE: "text-amber-700 dark:text-amber-400",
  NOT_INVESTABLE: "text-rose-700 dark:text-rose-400",
}

const TONE_STYLE: Record<Tone, string> = {
  POSITIVE: "border-emerald-600/30 text-emerald-700 dark:text-emerald-400",
  NEGATIVE: "border-rose-600/30 text-rose-700 dark:text-rose-400",
  NEUTRAL: "text-muted-foreground",
}

const TONE_LABEL: Record<Tone, string> = {
  POSITIVE: "Supportive",
  NEGATIVE: "Headwind",
  NEUTRAL: "Neutral",
}

const lineConfig = {
  value: { label: "Value", theme: { light: "oklch(0.371 0 0)", dark: "oklch(0.87 0 0)" } },
} satisfies ChartConfig

function dateOf(t: number) {
  return new Date(t * 1000).toISOString().slice(0, 10)
}

function signed(value: number | null | undefined, digits = 1, suffix = "%"): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return EMPTY
  return `${value > 0 ? "+" : ""}${formatNumber(value, digits)}${suffix}`
}

/**
 * The Macro page: the country and index dropdowns and the range, the index as a plain line, the macro panel (a chart and
 * a summary per series, the liquidity and risk regimes), the commodities and currencies read for the country, the Paul
 * Tudor Jones, Druckenmiller, George Soros and Ray Dalio summaries and the conclusion; on the right the AI macro analysis
 * of the index and the country's indexes side by side. A change reloads the outlook and keeps the choice in the URL.
 */
export function MacroPageClient({ initial }: { initial: MacroOutlook }) {
  const router = useRouter()
  const [data, setData] = useState<MacroOutlook>(initial)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const controller = useRef<AbortController | null>(null)

  useEffect(() => () => controller.current?.abort(), [])

  const load = useCallback(
    async (country: string, index: string, range: MacroRange) => {
      controller.current?.abort()
      const abort = new AbortController()
      controller.current = abort
      setLoading(true)
      setError(null)
      try {
        const { body } = await requestJson<MacroOutlook>(macroOutlookUrl(country, index, range), { signal: abort.signal })
        if (abort.signal.aborted) return
        setData(body)
        router.replace(`${MACRO_HREF}?${new URLSearchParams({ country, index, range })}`, { scroll: false })
      } catch (e) {
        if (abort.signal.aborted) return
        setError(e instanceof RequestError ? e.message : "The macro outlook could not be loaded.")
      } finally {
        if (!abort.signal.aborted) setLoading(false)
      }
    },
    [router]
  )

  const country = data.countries.find((c) => c.code === data.country.code) ?? data.countries[0]
  const indexes = country?.indexes ?? []

  return (
    <div className="flex flex-col gap-4">
      {/* Controls */}
      <div className="flex flex-col gap-2 sm:flex-row sm:flex-wrap sm:items-center">
        <Select
          items={data.countries.map((c) => ({ value: c.code, label: c.name }))}
          value={data.country.code}
          onValueChange={(v) => {
            const next = data.countries.find((c) => c.code === v)
            if (v && v !== data.country.code && next?.available) load(v, next.indexes[0]?.code ?? "", data.range)
          }}
        >
          <SelectTrigger aria-label="Country" className="w-full sm:w-[200px]">
            <SelectValue placeholder="Country" />
          </SelectTrigger>
          <SelectContent>
            {data.countries.map((c) => (
              <SelectItem key={c.code} value={c.code} disabled={!c.available}>
                {c.name}
                {!c.available && <span className="ml-1 text-xs text-muted-foreground">(coming later)</span>}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Select
          items={indexes.map((i) => ({ value: i.code, label: i.name }))}
          value={data.index.code}
          onValueChange={(v) => v && v !== data.index.code && load(data.country.code, v, data.range)}
        >
          <SelectTrigger aria-label="Index" className="w-full sm:w-[260px]">
            <SelectValue placeholder="Index" />
          </SelectTrigger>
          <SelectContent>
            {indexes.map((i) => (
              <SelectItem key={i.code} value={i.code}>
                {i.name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <div role="group" aria-label="Range" className="flex flex-wrap gap-1">
          {MACRO_RANGES.map((r) => (
            <Button
              key={r}
              size="sm"
              variant={r === data.range ? "secondary" : "ghost"}
              aria-pressed={r === data.range}
              onClick={() => r !== data.range && load(data.country.code, data.index.code, r)}
            >
              {r}
            </Button>
          ))}
        </div>
        <span className="flex items-center gap-2 text-xs text-muted-foreground sm:ml-auto">
          {loading && <LoaderIcon className="size-3.5 animate-spin" aria-label="Loading" />}
          As of {formatDate(data.asOfDate)}
        </span>
      </div>

      {error && (
        <p role="alert" className="rounded-lg border border-rose-600/30 bg-rose-600/5 px-3 py-2 text-sm text-rose-700 dark:text-rose-400">
          {error}
        </p>
      )}

      <div className={cn("grid items-start gap-4 transition-opacity xl:grid-cols-[minmax(0,1fr)_340px]", loading && "opacity-60")}>
        <div className="flex min-w-0 flex-col gap-4">
          <IndexCard data={data} />
          <MacroPanelCard data={data} />
          <MarketsCard data={data} />
          {data.weather && <WeatherCard weather={data.weather} country={data.country.name} />}
          <PresetsCard data={data} />
          <ConclusionCard data={data} />
          {data.notes.length > 0 && (
            <ul role="note" className="space-y-1 rounded-lg border border-amber-600/30 bg-amber-600/5 px-3 py-2 text-xs">
              {data.notes.map((n) => (
                <li key={n} className="flex items-start gap-2 text-amber-800 dark:text-amber-300">
                  <CircleAlertIcon className="mt-px size-3.5 shrink-0" />
                  <span>{n}</span>
                </li>
              ))}
            </ul>
          )}
          <p className="text-xs text-muted-foreground">
            {data.disclaimer} Rules version {data.rulesVersion}.
          </p>
        </div>
        <aside aria-label="AI macro analysis" className="flex min-w-0 flex-col gap-4 xl:sticky xl:top-4">
          <MacroAiPanel country={data.country.code} index={data.index.code} rulesScore={data.conclusion.score} />
          <IndexesCard indexes={data.indexes ?? []} current={data.index.code} />
        </aside>
      </div>
    </div>
  )
}

// ------------------------------------------------------------------ indexes

/** The country's indexes side by side: the broad market (IHSG) against its most liquid stocks (LQ45). */
function IndexesCard({ indexes, current }: { indexes: MacroIndexRow[]; current: string }) {
  if (indexes.length === 0) return null
  return (
    <Card size="sm">
      <CardHeader>
        <CardTitle>Indexes</CardTitle>
        <CardDescription>Breadth: the broad market against its most liquid stocks.</CardDescription>
      </CardHeader>
      <CardContent className="px-0">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead className="pl-4">Index</TableHead>
              <TableHead className="text-right">3M</TableHead>
              <TableHead className="text-right">1Y</TableHead>
              <TableHead className="pr-4 text-right">vs 200d</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {indexes.map((i) => (
              <TableRow key={i.code} className={cn(i.code === current && "bg-muted/40")}>
                <TableCell className="pl-4">
                  <span className="font-mono text-xs font-medium">{i.code}</span>
                  <div className="font-mono text-[11px] text-muted-foreground tabular-nums">{formatNumber(i.close, 2)}</div>
                </TableCell>
                <TableCell className={cn("text-right font-mono text-xs tabular-nums", toneOf(i.change3mPct))}>
                  {signed(i.change3mPct)}
                </TableCell>
                <TableCell className={cn("text-right font-mono text-xs tabular-nums", toneOf(i.change1yPct))}>
                  {signed(i.change1yPct)}
                </TableCell>
                <TableCell className={cn("pr-4 text-right font-mono text-xs tabular-nums", toneOf(i.vsSma200Pct))}>
                  {signed(i.vsSma200Pct)}
                  {i.sma200Slope && <div className="text-[10px] text-muted-foreground">avg {i.sma200Slope.toLowerCase()}</div>}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  )
}

function toneOf(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value) || value === 0) return ""
  return value > 0 ? "text-emerald-700 dark:text-emerald-400" : "text-rose-700 dark:text-rose-400"
}

// ------------------------------------------------------------------ commodities and currencies

const ROLE_GROUPS: { title: string; roles: MarketRow["role"][] }[] = [
  { title: "What Indonesia exports", roles: ["EXPORT"] },
  { title: "What Indonesia imports", roles: ["IMPORT"] },
  { title: "Currencies", roles: ["CURRENCY"] },
  { title: "Havens and references", roles: ["MIXED", "REFERENCE"] },
]

const REGIONAL_LABEL: Record<NonNullable<RegionalCurrencies["state"]>, string> = {
  UNDERPERFORMING: "Weaker than the region",
  IN_LINE: "In line with the region",
  OUTPERFORMING: "Firmer than the region",
}

/** The commodities and currency pairs of the price ingestion, read for the country, and its terms of trade. */
function MarketsCard({ data }: { data: MacroOutlook }) {
  const markets = data.markets
  const rows = markets ? [...markets.commodities, ...markets.forex] : []
  const terms = markets?.termsOfTrade
  const regional = markets?.regionalCurrencies
  return (
    <Card>
      <CardHeader>
        <CardTitle>Commodities and currencies</CardTitle>
        <CardDescription>
          {data.country.name} exports coal, nickel, palm oil, tin, gold and LNG and imports crude oil, wheat and soybeans:
          what their prices mean for its trade balance, the rupiah and the resource sectors; the rupiah read against its
          Asian peers, the dollar and risk appetite · data from Ingestion › Price Ingestion
        </CardDescription>
        {terms?.state && (
          <CardAction>
            <Badge
              variant="outline"
              className={cn(
                terms.state === "FAVOURABLE" && "border-emerald-600/40 text-emerald-700 dark:text-emerald-400",
                terms.state === "UNFAVOURABLE" && "border-rose-600/40 text-rose-700 dark:text-rose-400"
              )}
            >
              Terms of trade {terms.state.toLowerCase()} ({terms.score !== null && terms.score > 0 ? "+" : ""}
              {terms.score} of {terms.signals})
            </Badge>
          </CardAction>
        )}
      </CardHeader>
      <CardContent className="space-y-5">
        {terms && <p className="text-sm leading-relaxed">{terms.summary}</p>}
        {regional && (
          <div className="space-y-1.5 rounded-lg border px-3 py-2">
            <div className="flex flex-wrap items-center gap-2">
              <h3 className="text-sm font-semibold">The rupiah against its Asian peers</h3>
              {regional.state && (
                <Badge variant="outline" className={cn(TONE_STYLE[regional.tone])}>
                  {REGIONAL_LABEL[regional.state]}
                </Badge>
              )}
            </div>
            <p className="text-sm leading-relaxed">{regional.summary}</p>
          </div>
        )}
        {rows.length === 0 ? (
          <EmptyState
            variant="chart"
            title="No commodity or currency prices"
            description="Load them on Ingestion › Price Ingestion with the market Commodities or Forex."
          />
        ) : (
          ROLE_GROUPS.map((group) => {
            const items = rows.filter((r) => group.roles.includes(r.role))
            if (items.length === 0) return null
            return (
              <section key={group.title} aria-label={group.title} className="space-y-3">
                <h3 className="text-sm font-semibold">{group.title}</h3>
                <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
                  {items.map((r) => (
                    <MarketTile key={r.code} row={r} />
                  ))}
                </div>
              </section>
            )
          })
        )}
      </CardContent>
    </Card>
  )
}

function MarketTile({ row: r }: { row: MarketRow }) {
  const points = r.points.map(([t, v]) => ({ date: dateOf(t), value: v }))
  return (
    <div className="flex min-w-0 flex-col gap-1.5 rounded-xl border p-3">
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="truncate text-sm font-medium" title={r.name}>
            {r.name}
          </p>
          <p className="text-xs text-muted-foreground">
            <span className="font-mono text-foreground tabular-nums">{formatNumber(r.last, r.last >= 100 ? 2 : 4)}</span>{" "}
            {r.unit} · {formatDate(r.lastDate)}
            {r.stale && <span className="text-amber-600 dark:text-amber-400"> · stale</span>}
          </p>
        </div>
        <Badge variant="outline" className={cn("shrink-0", TONE_STYLE[r.tone])}>
          {TONE_LABEL[r.tone]}
        </Badge>
      </div>
      {points.length > 1 && (
        <ChartContainer config={lineConfig} className="aspect-auto h-[64px] w-full">
          <LineChart data={points} margin={{ left: 0, right: 0, top: 4, bottom: 0 }}>
            <XAxis dataKey="date" hide />
            <YAxis hide domain={["auto", "auto"]} />
            <ChartTooltip content={<ChartTooltipContent labelFormatter={(v) => formatDate(String(v))} />} />
            <Line dataKey="value" stroke="var(--color-value)" dot={false} strokeWidth={1.5} isAnimationActive={false} />
          </LineChart>
        </ChartContainer>
      )}
      <p className="font-mono text-[11px] text-muted-foreground tabular-nums">
        1M {signed(r.change1mPct)} · 3M {signed(r.change3mPct)} · 1Y {signed(r.change1yPct)}
        {r.rangePct !== null && ` · ${formatNumber(r.rangePct, 0)}% of its 52-week range`}
      </p>
      {r.relevance && <p className="text-xs leading-relaxed">{r.relevance}</p>}
    </div>
  )
}

// ------------------------------------------------------------------ index

function IndexCard({ data }: { data: MacroOutlook }) {
  const ix = data.index
  const points = ix.points.map(([t, v]) => ({ date: dateOf(t), value: v }))
  const c = data.conclusion
  return (
    <Card>
      <CardHeader>
        <CardTitle>
          {ix.name}
          <span className="ml-2 font-mono text-lg tabular-nums">{ix.close === null ? EMPTY : formatNumber(ix.close, 2)}</span>
        </CardTitle>
        <CardDescription>
          {ix.date ? `Close of ${formatDate(ix.date)}` : "Not stored yet"} · 1M {signed(ix.change1mPct)} · 3M{" "}
          {signed(ix.change3mPct)} · YTD {signed(ix.ytdPct)} · 1Y {signed(ix.change1yPct)} · 52-week range{" "}
          {formatNumber(ix.low52w, 0)} – {formatNumber(ix.high52w, 0)} ({signed(ix.fromHighPct)} from the high)
        </CardDescription>
        <CardAction className="flex flex-wrap items-center gap-2">
          {c.score !== null && (
            <span className="text-sm">
              Investability <span className={cn("font-mono font-semibold tabular-nums", c.verdict && VERDICT_STYLE[c.verdict])}>{c.score}</span>
              <span className="text-muted-foreground">/100</span>
            </span>
          )}
          <Badge variant="outline" className={cn("font-semibold", STANCE_STYLE[c.action])}>
            {c.actionLabel}
          </Badge>
        </CardAction>
      </CardHeader>
      <CardContent>
        {points.length < 2 ? (
          <EmptyState
            variant="chart"
            title="No index data"
            description="The index is loaded with the prices: Ingestion › Price Ingestion, “Update all companies to today”."
          />
        ) : (
          <ChartContainer config={lineConfig} className="aspect-auto h-[360px] w-full">
            <LineChart data={points} margin={{ left: 4, right: 8, top: 8 }}>
              <CartesianGrid vertical={false} strokeDasharray="3 3" />
              <XAxis dataKey="date" tickLine={false} axisLine={false} minTickGap={48} tick={{ fontSize: 11 }} tickFormatter={(v: string) => formatDate(v)} />
              <YAxis tickLine={false} axisLine={false} width={56} domain={["auto", "auto"]} tick={{ fontSize: 11 }} tickFormatter={(v: number) => formatNumber(v, 0)} />
              <ChartTooltip content={<ChartTooltipContent labelFormatter={(v) => formatDate(String(v))} />} />
              <Line dataKey="value" name={ix.code} stroke="var(--color-value)" dot={false} strokeWidth={1.75} isAnimationActive={false} />
            </LineChart>
          </ChartContainer>
        )}
      </CardContent>
    </Card>
  )
}

// ------------------------------------------------------------------ macro panel

function MacroPanelCard({ data }: { data: MacroOutlook }) {
  const groups = ["Indonesia", "Global"] as const
  return (
    <Card>
      <CardHeader>
        <CardTitle>Macro panel (liquidity and risk regimes, rates, dollar)</CardTitle>
        <CardDescription>
          The regimes of the Druckenmiller and Tudor Jones presets and every series behind them, with what each says for{" "}
          {data.country.name}&apos;s equities · data from Ingestion › Macro, as published by {formatDate(data.asOfDate)}
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-6">
        <div className="grid gap-3 md:grid-cols-2">
          <RegimeBox title="Liquidity regime (Druckenmiller)" regime={data.regimes.liquidity} good="EASING" bad="TIGHTENING" />
          <RegimeBox title="Risk regime (Tudor Jones)" regime={data.regimes.risk} good="RISK_ON" bad="RISK_OFF" />
        </div>
        {data.panels.length === 0 ? (
          <EmptyState variant="chart" title="No macro data" description="Run the macro ingestion on Ingestion › Macro." />
        ) : (
          groups.map((group) => {
            const panels = data.panels.filter((p) => p.group === group)
            if (panels.length === 0) return null
            return (
              <section key={group} aria-label={group} className="space-y-3">
                <h3 className="text-sm font-semibold">{group === "Global" ? "Global liquidity and risk" : group}</h3>
                <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
                  {panels.map((p) => (
                    <PanelChart key={p.code} panel={p} />
                  ))}
                </div>
              </section>
            )
          })
        )}
      </CardContent>
    </Card>
  )
}

function PanelChart({ panel: p }: { panel: MacroPanel }) {
  const data = p.points.map(([t, v]) => ({ date: dateOf(t), value: v }))
  const unit = p.unit === "%" ? "%" : p.unit === "points" ? " points" : ` ${p.unit}`
  return (
    <div className="flex min-w-0 flex-col gap-2 rounded-xl border p-3">
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="text-sm font-medium">{p.title}</p>
          <p className="text-xs text-muted-foreground">
            <span className="font-mono text-foreground tabular-nums">
              {p.lastValue === null ? EMPTY : formatNumber(p.lastValue, p.digits)}
            </span>
            {p.lastValue === null ? "" : unit} · {formatDate(p.lastDate)}
          </p>
        </div>
        <Badge variant="outline" className={cn("shrink-0", TONE_STYLE[p.tone])}>
          {TONE_LABEL[p.tone]}
        </Badge>
      </div>
      {data.length > 1 ? (
        <ChartContainer config={lineConfig} className="aspect-auto h-[120px] w-full">
          <LineChart data={data} margin={{ left: 0, right: 0, top: 4, bottom: 0 }}>
            <XAxis dataKey="date" hide />
            <YAxis hide domain={["auto", "auto"]} />
            <ChartTooltip content={<ChartTooltipContent labelFormatter={(v) => formatDate(String(v))} />} />
            <Line dataKey="value" stroke="var(--color-value)" dot={false} strokeWidth={1.5} isAnimationActive={false} />
          </LineChart>
        </ChartContainer>
      ) : (
        <div className="flex h-[120px] items-center justify-center rounded-md border border-dashed text-xs text-muted-foreground">
          {data.length === 1 ? "One observation in this range" : "No observation in this range"}
        </div>
      )}
      <p className="text-xs leading-relaxed">{p.summary}</p>
      <p className="text-[11px] text-muted-foreground">
        {p.source ? `Source: ${p.source} · ` : ""}Read by {p.readBy}
      </p>
    </div>
  )
}

/** "tightening (-3 from 6 signals)" */
function regimeText(state: string | null, score: number | null, signals?: number): string {
  if (!state) return "unknown"
  return `${state.replace("_", " ").toLowerCase()} (${score !== null && score > 0 ? "+" : ""}${formatNumber(score, 1)}${signals ? ` from ${signals} signals` : ""})`
}

function RegimeBox({ title, regime, good, bad }: { title: string; regime: MacroRegimeThen; good: string; bad: string }) {
  const state = regime.state
  return (
    <div className="space-y-1.5 rounded-lg border p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm font-medium">{title}</p>
        <Badge
          variant="outline"
          className={cn(
            state === good && "border-emerald-600/40 text-emerald-700 dark:text-emerald-400",
            state === bad && "border-rose-600/40 text-rose-700 dark:text-rose-400"
          )}
        >
          {state ? regimeText(state, regime.score, regime.signals) : `unknown (${regime.signals} of at least 3 signals)`}
        </Badge>
      </div>
      <p className="text-xs text-muted-foreground">
        Three months ago: {regimeText(regime.stateThen, regime.scoreThen)}
      </p>
      {regime.components.length > 0 ? (
        <ul className="space-y-0.5 text-xs text-muted-foreground">
          {regime.components.map((c) => (
            <li key={c.signal} className={cn(c.vote > 0 && "text-emerald-700 dark:text-emerald-400", c.vote < 0 && "text-rose-700 dark:text-rose-400")}>
              {c.vote > 0 ? "+ " : c.vote < 0 ? "− " : "· "}
              {c.text}
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-xs text-muted-foreground">{regime.note ?? "No macro signals yet: run the macro ingestion."}</p>
      )}
    </div>
  )
}

// ------------------------------------------------------------------ weather

const RISK_STYLE: Record<WeatherRisk, string> = {
  DROUGHT: "border-amber-600/40 text-amber-700 dark:text-amber-400",
  DRY_RISK: "border-amber-600/40 text-amber-700 dark:text-amber-400",
  NORMAL: "text-muted-foreground",
  WET_RISK: "border-sky-600/40 text-sky-700 dark:text-sky-400",
  WET: "border-sky-600/40 text-sky-700 dark:text-sky-400",
}

const SPI_LABEL: Record<SpiState, string> = {
  EXTREME_DROUGHT: "extreme drought",
  SEVERE_DROUGHT: "severe drought",
  MODERATE_DROUGHT: "moderate drought",
  NEAR_NORMAL: "near normal",
  WET: "wet",
  VERY_WET: "very wet",
}

function spiClass(state: SpiState | null): string {
  if (!state) return "text-muted-foreground"
  if (state.endsWith("DROUGHT")) return "text-amber-700 dark:text-amber-400"
  if (state === "WET" || state === "VERY_WET") return "text-sky-700 dark:text-sky-400"
  return ""
}

function monthOf(iso: string | null | undefined): string {
  return iso ? formatDate(iso).replace(/^\d+ /, "") : EMPTY
}

/**
 * The weather: global (El Nino / La Nina, the Indian Ocean Dipole, NOAA's forecast, the other producers) apart from
 * Indonesia (its palm oil, coal and rice regions), what it means for the sectors and how each investor reads it. Context
 * with no vote in the regimes, the presets' stances or the investability score.
 */
function WeatherCard({ weather: w, country }: { weather: MacroWeather; country: string }) {
  const global = w.charts.filter((c) => c.group === "Global")
  const local = w.charts.filter((c) => c.group === "Indonesia")
  const enso = w.global.enso
  const ensoBadge = enso.state
    ? enso.state === "NEUTRAL"
      ? "ENSO-neutral"
      : `${enso.strength ? enso.strength.replace("_", " ").toLowerCase() + " " : ""}${enso.state === "EL_NINO" ? "El Niño" : "La Niña"}`
    : null
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <CloudRainIcon aria-hidden className="size-4 text-primary" />
          Weather: El Niño / La Niña, rainfall and drought
        </CardTitle>
        <CardDescription>
          Global climate apart from {country}&apos;s own rainfall, what they mean for plantations, coal, food inflation and
          the rupiah, and how each investor reads them · context only: no vote in the regimes, the presets or the score ·
          data from Ingestion › Macro (Weather), as published by {formatDate(w.asOfDate)}
        </CardDescription>
        <CardAction className="flex flex-wrap items-center gap-2">
          {ensoBadge && (
            <Badge variant="outline" className={cn(TONE_STYLE[enso.tone])}>
              {ensoBadge.charAt(0).toUpperCase() + ensoBadge.slice(1)}
            </Badge>
          )}
          {w.indonesia.risk && (
            <Badge variant="outline" className={cn("font-semibold", RISK_STYLE[w.indonesia.risk])}>
              Indonesia: {w.indonesia.riskLabel.toLowerCase()}
            </Badge>
          )}
        </CardAction>
      </CardHeader>
      <CardContent className="space-y-6">
        <p className="text-sm leading-relaxed">{w.summary}</p>
        <p className="text-xs text-muted-foreground">{w.season.text}</p>

        {/* Global */}
        <section aria-label="Global weather" className="space-y-3">
          <h3 className="text-sm font-semibold">Global</h3>
          <div className="grid gap-3 md:grid-cols-3">
            <WeatherBox
              title="El Niño / La Niña"
              tone={enso.tone}
              figures={[
                ["RONI", enso.roni === null ? EMPTY : `${signed(enso.roni, 2, " °C")}${enso.roniSeason ? ` (${enso.roniSeason})` : ""}`],
                ["ONI", enso.oni === null ? EMPTY : signed(enso.oni, 2, " °C")],
                ["Niño 3.4 weekly", enso.nino34Weekly === null ? EMPTY : signed(enso.nino34Weekly, 1, " °C")],
                ["SOI 30-day", enso.soi30d === null ? EMPTY : signed(enso.soi30d, 1, "")],
              ]}
              text={enso.summary}
            />
            <WeatherBox
              title="Indian Ocean Dipole"
              tone={w.global.iod.tone}
              figures={[
                ["DMI", w.global.iod.value === null ? EMPTY : `${signed(w.global.iod.value, 2, " °C")} (${monthOf(w.global.iod.month)})`],
                ["State", w.global.iod.state ? w.global.iod.state.toLowerCase() : EMPTY],
              ]}
              text={w.global.iod.summary}
            />
            <WeatherBox
              title="NOAA ENSO forecast"
              tone={w.global.forecast.tone}
              figures={[
                ["El Niño in 3 / 6 months", `${formatNumber(w.global.forecast.elNino3m, 0)}% / ${formatNumber(w.global.forecast.elNino6m, 0)}%`],
                ["La Niña in 3 / 6 months", `${formatNumber(w.global.forecast.laNina3m, 0)}% / ${formatNumber(w.global.forecast.laNina6m, 0)}%`],
                ["Issued", formatDate(w.global.forecast.issued)],
              ]}
              text={w.global.forecast.summary}
            />
          </div>
          {w.global.producers.length > 0 && (
            <RegionTable title="The other producers (Malaysia's palm oil, Australia's coal, India's monsoon)" regions={w.global.producers} />
          )}
          {global.length > 0 && (
            <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
              {global.map((c) => (
                <WeatherChartTile key={c.code} chart={c} />
              ))}
            </div>
          )}
        </section>

        {/* Indonesia */}
        <section aria-label="Indonesia's weather" className="space-y-3">
          <div className="flex flex-wrap items-center gap-2">
            <h3 className="text-sm font-semibold">{country}</h3>
            {w.indonesia.risk && (
              <Badge variant="outline" className={cn(RISK_STYLE[w.indonesia.risk])}>
                {w.indonesia.riskLabel}
              </Badge>
            )}
          </div>
          <p className="text-sm leading-relaxed">{w.indonesia.summary}</p>
          {w.indonesia.regions.length > 0 ? (
            <RegionTable title="Palm oil, coal and rice regions" regions={w.indonesia.regions} />
          ) : (
            <EmptyState
              variant="chart"
              title="No rainfall stored yet"
              description="Fetch the Weather — Indonesia series on Ingestion › Macro (each rainfall series in full once, then its SPI)."
            />
          )}
          {local.length > 0 && (
            <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
              {local.map((c) => (
                <WeatherChartTile key={c.code} chart={c} />
              ))}
            </div>
          )}
        </section>

        {w.implications.length > 0 && (
          <section aria-label="What it means" className="space-y-2">
            <h3 className="text-sm font-semibold">What it means</h3>
            <ul className="space-y-1.5">
              {w.implications.map((i) => (
                <li key={i.sector} className="flex items-start gap-2 text-sm">
                  <ToneIcon tone={i.tone} />
                  <span>
                    <span className="font-medium">{i.sector}:</span> {i.text}
                  </span>
                </li>
              ))}
            </ul>
          </section>
        )}

        {w.assessments.length > 0 && (
          <section aria-label="How each investor reads the weather" className="space-y-2">
            <h3 className="text-sm font-semibold">How each investor reads it</h3>
            <div className="grid gap-3 md:grid-cols-2">
              {w.assessments.map((a) => (
                <div key={a.preset} className="space-y-1 rounded-lg border px-3 py-2">
                  <div className="flex flex-wrap items-center gap-2">
                    <p className="text-sm font-medium">{a.name}</p>
                    <Badge variant="outline" className={cn(TONE_STYLE[a.tone])}>
                      {TONE_LABEL[a.tone]}
                    </Badge>
                  </div>
                  <p className="text-xs leading-relaxed">{a.text}</p>
                </div>
              ))}
            </div>
          </section>
        )}

        {w.notes.length > 0 && (
          <ul role="note" className="space-y-1 text-xs text-amber-800 dark:text-amber-300">
            {w.notes.map((n) => (
              <li key={n} className="flex items-start gap-2">
                <CircleAlertIcon className="mt-px size-3.5 shrink-0" />
                <span>{n}</span>
              </li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  )
}

function WeatherBox({ title, tone, figures, text }: { title: string; tone: Tone; figures: [string, string][]; text: string }) {
  return (
    <div className="flex min-w-0 flex-col gap-2 rounded-xl border p-3">
      <div className="flex items-start justify-between gap-2">
        <p className="text-sm font-medium">{title}</p>
        <Badge variant="outline" className={cn("shrink-0", TONE_STYLE[tone])}>
          {TONE_LABEL[tone]}
        </Badge>
      </div>
      <dl className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-0.5 text-xs">
        {figures.map(([label, value]) => (
          <div key={label} className="contents">
            <dt className="text-muted-foreground">{label}</dt>
            <dd className="text-right font-mono tabular-nums">{value}</dd>
          </div>
        ))}
      </dl>
      <p className="text-xs leading-relaxed">{text}</p>
    </div>
  )
}

function RegionTable({ title, regions }: { title: string; regions: WeatherRegion[] }) {
  return (
    <div className="min-w-0 rounded-lg border">
      <p className="px-3 pt-2 text-xs font-medium text-muted-foreground">{title}</p>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className="pl-3">Region</TableHead>
            <TableHead>Month</TableHead>
            <TableHead className="text-right">Rain</TableHead>
            <TableHead className="text-right">% normal</TableHead>
            <TableHead className="text-right">3 months</TableHead>
            <TableHead className="pr-3 text-right">SPI-3</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {regions.map((r) => (
            <TableRow key={r.code} title={r.summary}>
              <TableCell className="pl-3 whitespace-normal">
                <div className="text-sm">{r.name}</div>
                <div className="text-[11px] text-muted-foreground">{r.role}</div>
              </TableCell>
              <TableCell className="text-xs">
                {monthOf(r.month)}
                {r.stale && <span className="text-amber-600 dark:text-amber-400"> · stale</span>}
              </TableCell>
              <TableCell className="text-right font-mono text-xs tabular-nums">
                {r.rainfallMm === null ? EMPTY : `${formatNumber(r.rainfallMm, 0)} mm`}
                {r.normalMm !== null && <div className="text-[10px] text-muted-foreground">normal {formatNumber(r.normalMm, 0)}</div>}
              </TableCell>
              <TableCell className="text-right font-mono text-xs tabular-nums">
                {r.pctOfNormal === null ? EMPTY : `${formatNumber(r.pctOfNormal, 0)}%`}
              </TableCell>
              <TableCell className="text-right font-mono text-xs tabular-nums">
                {r.pct3mOfNormal === null ? EMPTY : `${formatNumber(r.pct3mOfNormal, 0)}%`}
              </TableCell>
              <TableCell className={cn("pr-3 text-right font-mono text-xs tabular-nums", spiClass(r.spiState))}>
                {r.spi3 === null ? EMPTY : signed(r.spi3, 2, "")}
                {r.spiState && <div className="text-[10px]">{SPI_LABEL[r.spiState]}</div>}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}

function WeatherChartTile({ chart: c }: { chart: WeatherChart }) {
  const data = c.points.map(([t, v]) => ({ date: dateOf(t), value: v }))
  const unit = c.unit === "%" ? "%" : ` ${unitLabel(c.unit)}`
  // the reference: 100% of normal for rainfall, zero for an anomaly, the SOI or the SPI; nothing for a probability
  const reference = c.pctOfNormal ? 100 : c.unit === "%" ? null : 0
  return (
    <div className="flex min-w-0 flex-col gap-2 rounded-xl border p-3">
      <div className="min-w-0">
        <p className="text-sm font-medium">{c.title}</p>
        <p className="text-xs text-muted-foreground">
          <span className="font-mono text-foreground tabular-nums">{formatNumber(c.lastValue, c.digits)}</span>
          {unit} · {formatDate(c.lastDate)}
        </p>
      </div>
      {data.length > 1 ? (
        <ChartContainer config={lineConfig} className="aspect-auto h-[110px] w-full">
          <LineChart data={data} margin={{ left: 0, right: 0, top: 4, bottom: 0 }}>
            <XAxis dataKey="date" hide />
            <YAxis hide domain={["auto", "auto"]} />
            <ChartTooltip content={<ChartTooltipContent labelFormatter={(v) => formatDate(String(v))} />} />
            {reference !== null && <ReferenceLine y={reference} stroke="var(--muted-foreground)" strokeDasharray="3 3" />}
            <Line dataKey="value" stroke="var(--color-value)" dot={false} strokeWidth={1.5} isAnimationActive={false} />
          </LineChart>
        </ChartContainer>
      ) : (
        <div className="flex h-[110px] items-center justify-center rounded-md border border-dashed text-xs text-muted-foreground">
          {data.length === 1 ? "One observation in this range" : "No observation in this range"}
        </div>
      )}
    </div>
  )
}

// ------------------------------------------------------------------ presets

function PresetsCard({ data }: { data: MacroOutlook }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Preset summaries</CardTitle>
        <CardDescription>
          {data.index.code} read the way Paul Tudor Jones, Stanley Druckenmiller, George Soros and Ray Dalio read markets: the
          macro side - money, rates, inflation, growth, the dollar, foreign flows, credit and volatility - not the chart;
          each also reads the weather, as context without a vote.
        </CardDescription>
      </CardHeader>
      <CardContent className="grid gap-4 xl:grid-cols-2">
        {data.presets.map((p) => (
          <PresetCard key={p.preset} preset={p} />
        ))}
      </CardContent>
    </Card>
  )
}

function PresetCard({ preset: p }: { preset: MacroPreset }) {
  return (
    <section aria-label={`${p.name} summary`} className="flex min-w-0 flex-col gap-3 rounded-xl border p-4">
      <div className="min-w-0">
        <h3 className="flex flex-wrap items-center gap-2 font-semibold">
          {p.name}
          <Badge variant="outline" className={cn("font-semibold", STANCE_STYLE[p.stance])}>
            {STANCE_LABEL[p.stance]}
          </Badge>
        </h3>
        <p className="mt-0.5 text-sm">{p.stanceLabel}</p>
        <p className="mt-1 text-xs text-muted-foreground">
          {p.style}
          {p.score !== null && (
            <>
              {" "}
              · score <span className="font-mono text-foreground tabular-nums">{signed(p.score, 0, "")}</span> of ±{p.maxScore}
            </>
          )}
        </p>
      </div>
      <p className="text-sm leading-relaxed">{p.thesis}</p>
      {p.climate && (
        <div className="flex items-start gap-2 rounded-lg border bg-muted/30 px-3 py-2 text-xs">
          <CloudRainIcon aria-hidden className="mt-px size-3.5 shrink-0 text-muted-foreground" />
          <p>
            <span className="font-medium">Weather</span>{" "}
            <Badge variant="outline" className={cn("mx-1 px-1.5 py-0 text-[10px]", TONE_STYLE[p.climate.tone])}>
              {TONE_LABEL[p.climate.tone]}
            </Badge>
            {p.climate.text} <span className="text-muted-foreground">(context, no vote)</span>
          </p>
        </div>
      )}
      <div className="grid gap-3 sm:grid-cols-2">
        <TriggerList title="Buy when" items={p.buyWhen} tone="buy" />
        <TriggerList title="Sell when" items={p.sellWhen} tone="sell" />
      </div>
      <details className="text-sm">
        <summary className="cursor-pointer text-xs font-medium text-muted-foreground select-none hover:text-foreground">
          Evidence ({p.points.length})
        </summary>
        <ul className="mt-2 space-y-1.5">
          {p.points.map((pt, i) => (
            <li key={i} className="flex items-start gap-2 text-xs">
              <ToneIcon tone={pt.tone} />
              <span>{pt.text}</span>
            </li>
          ))}
        </ul>
      </details>
    </section>
  )
}

function TriggerList({ title, items, tone }: { title: string; items: string[]; tone: "buy" | "sell" }) {
  return (
    <div className="rounded-lg border bg-muted/30 px-3 py-2">
      <p
        className={cn(
          "text-[11px] font-medium uppercase tracking-wide",
          tone === "buy" ? "text-emerald-700 dark:text-emerald-400" : "text-rose-700 dark:text-rose-400"
        )}
      >
        {title}
      </p>
      <ul className="mt-1 list-disc space-y-1 pl-4 text-xs">
        {items.map((t) => (
          <li key={t}>{t}</li>
        ))}
      </ul>
    </div>
  )
}

function ToneIcon({ tone }: { tone: Tone }) {
  return tone === "POSITIVE" ? (
    <TrendingUpIcon aria-label="supports" className="mt-px size-3.5 shrink-0 text-emerald-600 dark:text-emerald-400" />
  ) : tone === "NEGATIVE" ? (
    <TrendingDownIcon aria-label="against" className="mt-px size-3.5 shrink-0 text-rose-600 dark:text-rose-400" />
  ) : (
    <MinusIcon aria-label="neutral" className="mt-px size-3.5 shrink-0 text-muted-foreground" />
  )
}

// ------------------------------------------------------------------ conclusion

function ConclusionCard({ data }: { data: MacroOutlook }) {
  const c = data.conclusion
  return (
    <Card>
      <CardHeader>
        <CardTitle>Conclusion: is {data.country.name} investable?</CardTitle>
        <CardDescription>
          A score of the macro conditions and the trend of {data.index.code} (0 – 100: 60 and more investable, below 40 not),
          and when to buy and when to sell.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-5">
        <div className="flex flex-col gap-4 sm:flex-row sm:items-center">
          <div className="flex items-baseline gap-1">
            <span className={cn("font-mono text-5xl font-semibold tabular-nums", c.verdict && VERDICT_STYLE[c.verdict])}>
              {c.score ?? EMPTY}
            </span>
            <span className="text-muted-foreground">/100</span>
          </div>
          <div className="min-w-0 flex-1 space-y-2">
            <ScoreBar score={c.score} />
            <div className="flex flex-wrap items-center gap-2">
              <span className={cn("text-sm font-semibold", c.verdict && VERDICT_STYLE[c.verdict])}>{c.verdictLabel}</span>
              <Badge variant="outline" className={cn("font-semibold", STANCE_STYLE[c.action])}>
                {c.actionLabel}
              </Badge>
              <span className="text-sm text-muted-foreground">{c.reason}</span>
            </div>
          </div>
        </div>

        <p className="text-sm leading-relaxed">{c.summary}</p>

        <div className="grid gap-3 lg:grid-cols-3">
          <ConditionList title="Buy when all of these hold" items={c.buyWhen} tone="buy" />
          <ConditionList title="Accumulate when one of these holds" items={c.accumulateWhen} tone="accumulate" />
          <ConditionList title="Sell / reduce when all of these hold" items={c.sellWhen} tone="sell" />
        </div>

        {c.watch.length > 0 && (
          <div className="space-y-1">
            <p className="text-sm font-medium">What would change the call</p>
            <ul className="list-disc space-y-1 pl-5 text-xs text-muted-foreground">
              {c.watch.map((w) => (
                <li key={w}>{w}</li>
              ))}
            </ul>
          </div>
        )}

        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="pl-0">Part of the score</TableHead>
                <TableHead className="text-right">Weight</TableHead>
                <TableHead className="text-right">Reading</TableHead>
                <TableHead className="text-right">Points</TableHead>
                <TableHead>Why</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {c.components.map((p) => (
                <TableRow key={p.key}>
                  <TableCell className="pl-0 font-medium">{p.name}</TableCell>
                  <TableCell className="text-right font-mono tabular-nums">{p.weight}%</TableCell>
                  <TableCell
                    className={cn(
                      "text-right font-mono tabular-nums",
                      p.value !== null && p.value > 0 && "text-emerald-700 dark:text-emerald-400",
                      p.value !== null && p.value < 0 && "text-rose-700 dark:text-rose-400"
                    )}
                  >
                    {p.value === null ? EMPTY : signed(p.value, 2, "")}
                  </TableCell>
                  <TableCell className="text-right font-mono tabular-nums">{p.points === null ? EMPTY : signed(p.points, 1, "")}</TableCell>
                  <TableCell className="min-w-[280px] text-xs text-muted-foreground">{p.text}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <p className="pt-2 text-xs text-muted-foreground">
            Score = 50 + 50 × the weighted mean of the readings (−1 against … +1 for); a part without data is left out.
          </p>
        </div>
      </CardContent>
    </Card>
  )
}

function ScoreBar({ score }: { score: number | null }) {
  return (
    <div className="relative h-2 w-full overflow-hidden rounded-full bg-gradient-to-r from-rose-500/40 via-amber-400/40 to-emerald-500/40" aria-hidden>
      <div className="absolute inset-y-0 left-[40%] w-px bg-foreground/30" />
      <div className="absolute inset-y-0 left-[60%] w-px bg-foreground/30" />
      {score !== null && (
        <div
          className="absolute top-1/2 size-3.5 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-background bg-foreground"
          style={{ left: `${Math.max(0, Math.min(100, score))}%` }}
        />
      )}
    </div>
  )
}

function ConditionList({ title, items, tone }: { title: string; items: Condition[]; tone: "buy" | "accumulate" | "sell" }) {
  const all = items.length > 0 && items.every((i) => i.met)
  const any = items.some((i) => i.met)
  const active = tone === "accumulate" ? any : all
  return (
    <div className={cn("rounded-lg border px-3 py-2", active && (tone === "sell" ? "border-rose-600/40 bg-rose-600/5" : tone === "buy" ? "border-emerald-600/40 bg-emerald-600/5" : "border-sky-600/40 bg-sky-600/5"))}>
      <p className="flex items-center justify-between gap-2 text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
        {title}
        {active && <span className="normal-case text-foreground">now</span>}
      </p>
      <ul className="mt-1.5 space-y-1.5">
        {items.map((i) => (
          <li key={i.text} className="flex items-start gap-2 text-xs">
            {i.met ? (
              <CheckCircle2Icon aria-label="met" className="mt-px size-3.5 shrink-0 text-foreground" />
            ) : (
              <CircleIcon aria-label="not met" className="mt-px size-3.5 shrink-0 text-muted-foreground" />
            )}
            <span className={cn(!i.met && "text-muted-foreground")}>{i.text}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}
