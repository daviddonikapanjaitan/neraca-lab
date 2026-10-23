"use client"

import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import dynamic from "next/dynamic"
import Link from "next/link"
import { useRouter } from "next/navigation"
import { useTheme } from "next-themes"
import { QueryClient, QueryClientProvider, useQuery } from "@tanstack/react-query"
import { AlertTriangleIcon, ImageDownIcon, RotateCwIcon, SlidersHorizontalIcon, TriangleAlertIcon } from "lucide-react"

import { EmptyState } from "@/components/empty-state"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle, SheetTrigger } from "@/components/ui/sheet"
import { Skeleton } from "@/components/ui/skeleton"
import { IndicatorPicker } from "@/components/technical/indicator-picker"
import type { PriceChartHandle } from "@/components/technical/price-chart"
import { PresetSummariesCard } from "@/components/technical/preset-summaries"
import { KeyLevelsCard, RegimeCard, SnapshotCard, VolumeProfileCard } from "@/components/technical/side-panels"
import { TickerSearch } from "@/components/technical/ticker-search"
import { jsonBody, RequestError } from "@/lib/client-api"
import { EMPTY, formatDate, formatNumber, formatPrice } from "@/lib/format"
import { companyHref } from "@/lib/links"
import {
  DISCLAIMER,
  decimalsOf,
  eventsSchema,
  fetchValidated,
  indicatorsSchema,
  INTERVALS,
  levelsSchema,
  marketSchema,
  BENCHMARK_INDEX,
  INDEX_TICKERS,
  NOT_FOR_INDEX,
  NOT_FOR_OTHER_INDEX,
  NOT_FOR_MONTHLY,
  ohlcvSchema,
  parseSettings,
  PRESETS,
  RANGES,
  seriesFor,
  snapshotSchema,
  summarySchema,
  technicalUrl,
  TRADINGVIEW_NOTICE,
  type ChartSettings,
  type IndicatorId,
} from "@/lib/technical"
import type { TechnicalMarket } from "@/lib/types"
import { cn } from "@/lib/utils"

const PriceChart = dynamic(() => import("@/components/technical/price-chart"), {
  ssr: false,
  loading: () => <Skeleton className="h-[420px] w-full" />,
})

const STORAGE_KEY = "neracalab.technical.settings"

function readLocal(): unknown {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

function writeLocal(settings: ChartSettings) {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(settings))
  } catch {
    // storage unavailable (private window, blocked): the server copy still holds them
  }
}

function searchOf(s: ChartSettings): string {
  const q = new URLSearchParams()
  q.set("exchange", s.exchange)
  if (s.ticker) q.set("ticker", s.ticker)
  q.set("interval", s.interval)
  q.set("range", s.range)
  if (s.adjustment !== "split") q.set("adjustment", s.adjustment)
  if (s.preset) q.set("preset", s.preset)
  q.set("ind", s.indicators.join(","))
  return q.toString()
}

/** A group of toggle buttons (one value). */
function Toggle<T extends string>({ label, value, options, onChange }: {
  label: string
  value: T
  options: { value: T; label: string }[]
  onChange: (v: T) => void
}) {
  return (
    <div role="radiogroup" aria-label={label} className="inline-flex rounded-lg border p-0.5">
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          role="radio"
          aria-checked={o.value === value}
          onClick={() => onChange(o.value)}
          className={cn(
            "rounded-md px-2.5 py-1 text-xs font-medium transition-colors",
            o.value === value ? "bg-primary text-primary-foreground" : "text-muted-foreground hover:bg-accent hover:text-foreground"
          )}
        >
          {o.label}
        </button>
      ))}
    </div>
  )
}

export function TechnicalsPageClient(props: {
  /** the exchanges (IDX), then Forex and Commodities */
  markets: TechnicalMarket[]
  /** the settings the URL names; the others come from the user's saved settings */
  initial: Partial<ChartSettings>
}) {
  const [client] = useState(() => new QueryClient({ defaultOptions: { queries: { staleTime: 5 * 60_000, retry: 1, refetchOnWindowFocus: false } } }))
  return (
    <QueryClientProvider client={client}>
      <Technicals {...props} />
    </QueryClientProvider>
  )
}

/** Loads the user's saved settings (server; else this browser's), then opens the page with them and the URL's. */
function Technicals({ markets, initial }: {
  markets: TechnicalMarket[]
  initial: Partial<ChartSettings>
}) {
  const saved = useQuery({
    queryKey: ["technical", "settings"],
    queryFn: async () => {
      const response = await fetch("/api/technical/settings", { cache: "no-store" })
      if (response.status === 204 || !response.ok) return readLocal()
      return response.json() as Promise<unknown>
    },
    staleTime: Infinity,
    retry: false,
  })
  if (saved.isPending) {
    return <Skeleton className="h-[520px] w-full rounded-xl" />
  }
  const value = saved.data ?? readLocal()
  const stored = value && typeof value === "object" ? (value as Partial<ChartSettings>) : {}
  // another exchange in the URL than in the saved settings: the saved ticker does not apply
  const ticker = initial.ticker ?? (initial.exchange && initial.exchange !== stored.exchange ? null : stored.ticker ?? null)
  return <TechnicalsView markets={markets} start={parseSettings({ ...stored, ...initial, ticker })} />
}

function TechnicalsView({ markets, start }: { markets: TechnicalMarket[]; start: ChartSettings }) {
  const router = useRouter()
  const { resolvedTheme } = useTheme()
  const dark = resolvedTheme === "dark"
  const chartRef = useRef<PriceChartHandle>(null)
  const [settings, setSettings] = useState<ChartSettings>(start)

  // URL and persistence follow the settings (the save is debounced)
  useEffect(() => {
    router.replace(`/companies/technicals?${searchOf(settings)}`, { scroll: false })
    writeLocal(settings)
    const id = setTimeout(() => {
      fetch("/api/technical/settings", jsonBody("PUT", settings)).catch(() => undefined)
    }, 800)
    return () => clearTimeout(id)
  }, [settings, router])

  const update = useCallback((patch: Partial<ChartSettings>) => setSettings((s) => ({ ...s, ...patch })), [])
  const toggle = (id: IndicatorId, on: boolean) =>
    setSettings((s) => ({
      ...s,
      preset: null,
      indicators: on ? Array.from(new Set([...s.indicators, id])) : s.indicators.filter((x) => x !== id),
    }))

  const { exchange, ticker, range, indicators } = settings
  const selected = markets.find((m) => m.code === exchange) ?? null
  const isMarket = selected?.kind === "MARKET"
  // a currency pair or commodity has no dividends: always the price as traded
  const adjustment = isMarket ? "split" : settings.adjustment
  const has = (id: IndicatorId) => indicators.includes(id)

  // the frequency of the series once known (a monthly series - coal, cobalt, urea, ... - is charted on the monthly interval only)
  const [monthlyTicker, setMonthlyTicker] = useState<string | null>(null)
  const monthly = !!ticker && monthlyTicker === `${exchange}:${ticker}`
  const interval = monthly ? "M" : settings.interval
  const params = { interval, range, adjustment }

  const ohlcv = useQuery({
    queryKey: ["technical", "ohlcv", exchange, ticker, interval, range, adjustment],
    queryFn: ({ signal }) => fetchValidated(technicalUrl(exchange, ticker!, "ohlcv", params), ohlcvSchema, signal),
    enabled: !!ticker,
  })
  const type = ohlcv.data ? ohlcv.data.type : isMarket ? (exchange === "FOREX" ? "FOREX" : "COMMODITY")
    : ticker && INDEX_TICKERS.includes(ticker) ? "INDEX" : "STOCK"
  if (ohlcv.data && ticker && ohlcv.data.ticker === ticker && (ohlcv.data.frequency === "MONTHLY") !== monthly) {
    // learnt from the answer: a monthly series re-queries on the monthly interval
    setMonthlyTicker(ohlcv.data.frequency === "MONTHLY" ? `${exchange}:${ticker}` : null)
  }
  // an index (IHSG, LQ45) is charted in points, a currency pair or commodity in its unit: no events, no auto-rejection,
  // and no comparison with IHSG - except LQ45, which is compared with IHSG
  const isIndex = type !== "STOCK"
  const isInstrument = type === "FOREX" || type === "COMMODITY"
  const comparable = type === "INDEX" && ticker !== BENCHMARK_INDEX
  const shown = useMemo(
    () => (monthly ? indicators.filter((id) => !NOT_FOR_MONTHLY.includes(id))
      : isIndex ? indicators.filter((id) => !(comparable ? NOT_FOR_OTHER_INDEX : NOT_FOR_INDEX).includes(id)) : indicators),
    [indicators, isIndex, comparable, monthly]
  )
  // levels, the snapshot and the summaries need daily bars: not asked for a monthly series
  const daily = !!ticker && ohlcv.isSuccess && !monthly
  const set = useMemo(() => seriesFor(shown), [shown])

  const series = useQuery({
    queryKey: ["technical", "indicators", exchange, ticker, interval, range, adjustment, set.join(",")],
    queryFn: ({ signal }) => fetchValidated(technicalUrl(exchange, ticker!, "indicators", { ...params, set: set.join(",") }), indicatorsSchema, signal),
    enabled: !!ticker && set.length > 0,
  })
  const levels = useQuery({
    queryKey: ["technical", "levels", exchange, ticker],
    queryFn: ({ signal }) => fetchValidated(technicalUrl(exchange, ticker!, "levels"), levelsSchema, signal),
    enabled: daily,
  })
  const events = useQuery({
    queryKey: ["technical", "events", exchange, ticker],
    queryFn: ({ signal }) => fetchValidated(technicalUrl(exchange, ticker!, "events"), eventsSchema, signal),
    enabled: !!ticker,
  })
  const snapshot = useQuery({
    queryKey: ["technical", "snapshot", exchange, ticker],
    queryFn: ({ signal }) => fetchValidated(technicalUrl(exchange, ticker!, "snapshot"), snapshotSchema, signal),
    enabled: daily,
  })
  const summary = useQuery({
    queryKey: ["technical", "summary", exchange, ticker],
    queryFn: ({ signal }) => fetchValidated(technicalUrl(exchange, ticker!, "summary"), summarySchema, signal),
    enabled: daily,
  })
  const showMarket = has("regime")
  const market = useQuery({
    queryKey: ["technical", "market", exchange],
    queryFn: ({ signal }) => fetchValidated(`/api/technical/market?${new URLSearchParams({ exchange, range: "1Y" })}`, marketSchema, signal),
    enabled: showMarket,
  })

  const savePng = () => {
    const canvas = chartRef.current?.screenshot()
    if (!canvas || !ticker) return
    canvas.toBlob((blob) => {
      if (!blob) return
      const url = URL.createObjectURL(blob)
      const a = document.createElement("a")
      a.href = url
      a.download = `${ticker}-${interval}-${range}-${ohlcv.data?.lastDate ?? "chart"}.png`
      a.click()
      URL.revokeObjectURL(url)
    }, "image/png")
  }

  const data = ohlcv.data
  const last = data?.bars[data.bars.length - 1]
  const prev = data && data.bars.length > 1 ? data.bars[data.bars.length - 2] : undefined
  const change = last && prev ? (last[4] / prev[4] - 1) * 100 : null
  const levelsDisabled = adjustment === "total"

  const picker = (
    <IndicatorPicker selected={indicators} onToggle={toggle} levelsDisabled={levelsDisabled} index={isIndex} comparable={comparable} monthly={monthly} />
  )
  // decimals of the header price: an index 2, an instrument those of its tick (EUR/USD 4, USD/JPY 3; by its size
  // when the tick is unknown), a stock as the price formatter
  const instrumentDecimals = (value: number) =>
    data?.tickSize != null ? decimalsOf(data.tickSize) : value < 1000 ? 3 : 2
  const priceText = (value: number) =>
    isIndex && !isInstrument ? `${formatPrice(value)} points`
      : isInstrument ? `${formatNumber(value, instrumentDecimals(value))} ${data?.unit ?? ""}`.trim()
        : `${data!.currency} ${formatPrice(value)}`

  return (
    <div className="flex flex-col gap-4">
      {/* Controls */}
      <div className="flex flex-col gap-3">
        <div className="flex flex-col gap-2 sm:flex-row sm:flex-wrap sm:items-center">
          <Select
            items={markets.map((m) => ({ value: m.code, label: m.kind === "EXCHANGE" ? `${m.code} · ${m.name}` : m.name }))}
            value={exchange}
            onValueChange={(v) => v && v !== exchange && update({ exchange: v, ticker: null })}
          >
            <SelectTrigger aria-label="Exchange or market" className="w-full sm:w-[240px]">
              <SelectValue placeholder="Exchange or market" />
            </SelectTrigger>
            <SelectContent>
              {markets.map((m) => (
                <SelectItem key={m.code} value={m.code}>
                  {m.kind === "EXCHANGE" ? `${m.code} · ${m.name}` : m.name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <TickerSearch
            exchange={exchange}
            value={ticker}
            itemLabel={selected?.itemLabel ?? "Ticker"}
            onSelect={(t) => {
              if (t.frequency === "MONTHLY") setMonthlyTicker(`${exchange}:${t.ticker}`)
              update({ ticker: t.ticker })
            }}
          />
          <div className="flex flex-wrap items-center gap-2 sm:ml-auto">
            <Sheet>
              <SheetTrigger render={<Button variant="outline" size="sm" className="lg:hidden" />}>
                <SlidersHorizontalIcon /> Indicators
              </SheetTrigger>
              <SheetContent side="right" className="w-[300px] overflow-y-auto">
                <SheetHeader>
                  <SheetTitle>Indicators</SheetTitle>
                  <SheetDescription>Choose what the chart shows. Presets toggle indicators; their summaries are below the chart.</SheetDescription>
                </SheetHeader>
                <div className="px-4 pb-6">{picker}</div>
              </SheetContent>
            </Sheet>
            <Button variant="outline" size="sm" onClick={savePng} disabled={!data}>
              <ImageDownIcon /> PNG
            </Button>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {monthly ? (
            <span className="rounded-lg border px-2.5 py-1 text-xs text-muted-foreground">Monthly series: monthly bars only</span>
          ) : (
            <Toggle label="Interval" value={interval} options={INTERVALS} onChange={(v) => update({ interval: v })} />
          )}
          <Toggle label="Range" value={range} options={RANGES.map((r) => ({ value: r, label: r }))} onChange={(v) => update({ range: v })} />
          {!isMarket && (
            <Toggle
              label="Price basis"
              value={adjustment}
              options={[{ value: "split", label: "Split-adjusted" }, { value: "total", label: "Total return" }]}
              onChange={(v) => update({ adjustment: v })}
            />
          )}
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-xs text-muted-foreground">Presets:</span>
          {PRESETS.map((p) => (
            <Button
              key={p.id}
              size="sm"
              variant={settings.preset === p.id ? "default" : "outline"}
              title={p.description}
              aria-pressed={settings.preset === p.id}
              onClick={() => update({ preset: p.id, indicators: p.indicators })}
            >
              {p.label}
            </Button>
          ))}
        </div>
      </div>

      {!ticker ? (
        <Card>
          <CardContent className="py-10">
            <EmptyState
              variant="search"
              title={isMarket ? `Choose a ${selected!.itemLabel.toLowerCase()}` : "Choose a company or the index"}
              description={
                isMarket
                  ? `Search a ${selected!.itemLabel.toLowerCase()} of ${selected!.name} to open its chart (its prices come from Ingestion › Price Ingestion).`
                  : `Search a ticker or company name of ${exchange}, or IHSG / LQ45 for the indexes, to open its chart.`
              }
            />
          </CardContent>
        </Card>
      ) : (
        <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_260px]">
          <div className="flex min-w-0 flex-col gap-4">
            <Card className="min-w-0">
              <CardHeader>
                <CardTitle className="flex flex-wrap items-center gap-2">
                  <span className="font-mono">{ticker}</span>
                  <span className="font-normal text-muted-foreground">{data?.companyName ?? ""}</span>
                  {data && data.type === "INDEX" && <Badge variant="secondary">Index</Badge>}
                  {data && data.type === "FOREX" && <Badge variant="secondary">Forex</Badge>}
                  {data && data.type === "COMMODITY" && <Badge variant="secondary">Commodity</Badge>}
                  {data && data.frequency === "MONTHLY" && <Badge variant="outline">Monthly</Badge>}
                  {data && data.type === "STOCK" && (
                    <Link href={companyHref(data.exchange, data.ticker)} className="text-xs font-normal text-primary underline-offset-4 hover:underline">
                      Fundamentals
                    </Link>
                  )}
                </CardTitle>
                <CardDescription className="flex flex-wrap items-center gap-x-3 gap-y-1">
                  {last ? (
                    <>
                      <span className="font-mono text-base font-semibold text-foreground tabular-nums">
                        {priceText(last[4])}
                      </span>
                      <span className={cn("font-mono tabular-nums", change !== null && change > 0 && "text-emerald-600 dark:text-emerald-400",
                        change !== null && change < 0 && "text-rose-600 dark:text-rose-400")}>
                        {change === null ? EMPTY : `${change > 0 ? "+" : ""}${formatNumber(change, 2)}%`}
                      </span>
                      <span>
                        {formatDate(data!.lastDate)} · {data!.tradingDays.toLocaleString("en-US")}{" "}
                        {data!.frequency === "MONTHLY" ? "months" : "trading days"} since {formatDate(data!.firstDate)}
                        {data!.lotSize !== null ? ` · lot ${data!.lotSize} shares` : ` · ${data!.source ?? "Yahoo Finance"}`}
                      </span>
                    </>
                  ) : (
                    <span>{ohlcv.isPending ? "Loading…" : ""}</span>
                  )}
                </CardDescription>
                {data?.limitedHistory && (
                  <Badge variant="destructive" className="w-fit">
                    <AlertTriangleIcon /> Limited history: {data.tradingDays} trading days (fewer than {data.minHistoryDays}); long-period indicators are empty
                  </Badge>
                )}
              </CardHeader>
              <CardContent className="px-2 sm:px-4">
                {ohlcv.isError ? (
                  <ErrorNote error={ohlcv.error} onRetry={() => ohlcv.refetch()} />
                ) : !data || (set.length > 0 && series.isPending) ? (
                  <Skeleton className="h-[420px] w-full" />
                ) : data.bars.length === 0 ? (
                  <EmptyState variant="chart" title="No bars in this range" description="Choose a longer range." />
                ) : (
                  <PriceChart
                    ref={chartRef}
                    ohlcv={data}
                    indicators={series.data}
                    levels={levels.data}
                    events={events.data}
                    selected={shown}
                    dark={dark}
                  />
                )}
                {data && (
                  <p className="px-2 pt-2 text-xs text-muted-foreground">
                    Prices: {data.priceBasis}.{levelsDisabled ? " Levels are hidden on the total-return basis." : ""}
                    {series.isError ? ` Indicators could not be loaded: ${series.error.message}` : ""}
                  </p>
                )}
              </CardContent>
            </Card>

            {monthly ? (
              <Card>
                <CardHeader>
                  <CardTitle>Preset summaries</CardTitle>
                  <CardDescription>
                    {data?.companyName ?? ticker} is a monthly series (the World Bank monthly average): the key levels, the
                    snapshot and the preset summaries need daily bars, so only the chart and its indicators on monthly bars
                    are shown.
                  </CardDescription>
                </CardHeader>
              </Card>
            ) : summary.data ? (
              <PresetSummariesCard
                summary={summary.data}
                activePreset={settings.preset}
                onShowPreset={(id) => {
                  const preset = PRESETS.find((p) => p.id === id)
                  if (preset) update({ preset: preset.id, indicators: preset.indicators })
                }}
              />
            ) : summary.isError ? (
              <ErrorNote error={summary.error} onRetry={() => summary.refetch()} />
            ) : (
              <Skeleton className="h-[320px] w-full" />
            )}

            {has("regime") && (
              market.isError ? (
                <ErrorNote error={market.error} onRetry={() => market.refetch()} />
              ) : !market.data ? (
                <Skeleton className="h-[220px] w-full" />
              ) : (
                <RegimeCard market={market.data} />
              )
            )}

            {!monthly && (
              <div className="grid gap-4 xl:grid-cols-2">
                {levels.data ? <KeyLevelsCard levels={levels.data} /> : levels.isError ? <ErrorNote error={levels.error} onRetry={() => levels.refetch()} /> : <Skeleton className="h-[300px]" />}
                {has("profile") && levels.data && levels.data.volumeProfile.topNodes.length > 0 && <VolumeProfileCard levels={levels.data} />}
                {snapshot.data ? <SnapshotCard snapshot={snapshot.data} /> : snapshot.isError ? <ErrorNote error={snapshot.error} onRetry={() => snapshot.refetch()} /> : <Skeleton className="h-[300px]" />}
              </div>
            )}
          </div>

          <Card className="hidden h-fit lg:block">
            <CardHeader>
              <CardTitle>Indicators</CardTitle>
              <CardDescription>Presets toggle indicators; their summaries are below the chart.</CardDescription>
            </CardHeader>
            <CardContent>{picker}</CardContent>
          </Card>
        </div>
      )}

      <footer className="space-y-1 border-t pt-3 text-xs text-muted-foreground">
        <p className="font-medium text-foreground">{DISCLAIMER}</p>
        <p>
          Indicators are computed by Neraca Lab from stored daily prices (split-adjusted; IDX tick sizes and auto-rejection
          limits applied). Events: Yahoo Finance dividends and splits, and splits recorded from the filings. Currency pairs
          and commodities: Yahoo Finance daily bars (coal, LNG, nickel, tin, rubber and the fertilizers: the World Bank monthly
          average; cobalt: the IMF monthly average; wood pulp: the US producer price index, FRED; palm
          oil in ringgit: CME CPO=F x USD/MYR, an approximation of Bursa Malaysia FCPO), levels at their own tick size; palm
          oil and the monthly series are drawn as a plain line of the close, not candlesticks.
          The presets read the chart only; the macro view is on the Macro page.
        </p>
        <p>
          Charts: {TRADINGVIEW_NOTICE.replace(" https://www.tradingview.com/", "")}{" "}
          <a href="https://www.tradingview.com/" target="_blank" rel="noopener noreferrer" className="underline underline-offset-4">
            tradingview.com
          </a>{" "}
          · <Link href="/credits" className="underline underline-offset-4">Credits</Link>
        </p>
      </footer>
    </div>
  )
}

/** A failed request of the page, with its HTTP status and a retry of that request. */
function ErrorNote({ error, onRetry }: { error: Error; onRetry: () => void }) {
  const status = error instanceof RequestError ? error.status : 0
  return (
    <div role="alert" className="flex flex-col items-center gap-2 rounded-lg border border-dashed px-4 py-8 text-center">
      <TriangleAlertIcon className="size-5 text-muted-foreground" />
      <p className="text-sm font-medium">{status === 404 ? "Not found" : status === 0 ? "Server unreachable" : "Request failed"}</p>
      <p className="max-w-md text-sm text-muted-foreground">{error.message}</p>
      {status !== 404 && (
        <Button variant="outline" size="sm" onClick={onRetry}>
          <RotateCwIcon /> Retry
        </Button>
      )}
    </div>
  )
}
