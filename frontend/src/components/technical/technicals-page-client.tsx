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
import { KeyLevelsCard, MacroCard, RegimeCard, SnapshotCard, VolumeProfileCard } from "@/components/technical/side-panels"
import { TickerSearch } from "@/components/technical/ticker-search"
import { jsonBody, RequestError } from "@/lib/client-api"
import { EMPTY, formatDate, formatNumber, formatPrice } from "@/lib/format"
import { companyHref } from "@/lib/links"
import {
  DISCLAIMER,
  eventsSchema,
  fetchValidated,
  indicatorsSchema,
  INTERVALS,
  levelsSchema,
  marketSchema,
  ohlcvSchema,
  parseSettings,
  PRESETS,
  RANGES,
  seriesFor,
  snapshotSchema,
  technicalUrl,
  TRADINGVIEW_NOTICE,
  type ChartSettings,
  type IndicatorId,
} from "@/lib/technical"
import type { Exchange } from "@/lib/types"
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
  exchanges: Exchange[]
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
function Technicals({ exchanges, initial }: {
  exchanges: Exchange[]
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
  return <TechnicalsView exchanges={exchanges} start={parseSettings({ ...stored, ...initial, ticker })} />
}

function TechnicalsView({ exchanges, start }: { exchanges: Exchange[]; start: ChartSettings }) {
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

  const { exchange, ticker, interval, range, adjustment, indicators } = settings
  const has = (id: IndicatorId) => indicators.includes(id)
  const set = useMemo(() => seriesFor(indicators), [indicators])
  const params = { interval, range, adjustment }

  const ohlcv = useQuery({
    queryKey: ["technical", "ohlcv", exchange, ticker, interval, range, adjustment],
    queryFn: ({ signal }) => fetchValidated(technicalUrl(exchange, ticker!, "ohlcv", params), ohlcvSchema, signal),
    enabled: !!ticker,
  })
  const series = useQuery({
    queryKey: ["technical", "indicators", exchange, ticker, interval, range, adjustment, set.join(",")],
    queryFn: ({ signal }) => fetchValidated(technicalUrl(exchange, ticker!, "indicators", { ...params, set: set.join(",") }), indicatorsSchema, signal),
    enabled: !!ticker && set.length > 0,
  })
  const levels = useQuery({
    queryKey: ["technical", "levels", exchange, ticker],
    queryFn: ({ signal }) => fetchValidated(technicalUrl(exchange, ticker!, "levels"), levelsSchema, signal),
    enabled: !!ticker,
  })
  const events = useQuery({
    queryKey: ["technical", "events", exchange, ticker],
    queryFn: ({ signal }) => fetchValidated(technicalUrl(exchange, ticker!, "events"), eventsSchema, signal),
    enabled: !!ticker,
  })
  const snapshot = useQuery({
    queryKey: ["technical", "snapshot", exchange, ticker],
    queryFn: ({ signal }) => fetchValidated(technicalUrl(exchange, ticker!, "snapshot"), snapshotSchema, signal),
    enabled: !!ticker,
  })
  const showMarket = has("regime") || has("macro")
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

  const picker = <IndicatorPicker selected={indicators} onToggle={toggle} levelsDisabled={levelsDisabled} />

  return (
    <div className="flex flex-col gap-4">
      {/* Controls */}
      <div className="flex flex-col gap-3">
        <div className="flex flex-col gap-2 sm:flex-row sm:flex-wrap sm:items-center">
          <Select
            items={exchanges.map((e) => ({ value: e.code, label: `${e.code} · ${e.name}` }))}
            value={exchange}
            onValueChange={(v) => v && v !== exchange && update({ exchange: v, ticker: null })}
          >
            <SelectTrigger aria-label="Exchange" className="w-full sm:w-[240px]">
              <SelectValue placeholder="Exchange" />
            </SelectTrigger>
            <SelectContent>
              {exchanges.map((e) => (
                <SelectItem key={e.code} value={e.code}>
                  {e.code} · {e.name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <TickerSearch exchange={exchange} value={ticker} onSelect={(t) => update({ ticker: t.ticker })} />
          <div className="flex flex-wrap items-center gap-2 sm:ml-auto">
            <Sheet>
              <SheetTrigger render={<Button variant="outline" size="sm" className="lg:hidden" />}>
                <SlidersHorizontalIcon /> Indicators
              </SheetTrigger>
              <SheetContent side="right" className="w-[300px] overflow-y-auto">
                <SheetHeader>
                  <SheetTitle>Indicators</SheetTitle>
                  <SheetDescription>Choose what the chart shows. Presets only toggle indicators.</SheetDescription>
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
          <Toggle label="Interval" value={interval} options={INTERVALS} onChange={(v) => update({ interval: v })} />
          <Toggle label="Range" value={range} options={RANGES.map((r) => ({ value: r, label: r }))} onChange={(v) => update({ range: v })} />
          <Toggle
            label="Price basis"
            value={adjustment}
            options={[{ value: "split", label: "Split-adjusted" }, { value: "total", label: "Total return" }]}
            onChange={(v) => update({ adjustment: v })}
          />
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
              title="Choose a company"
              description={`Search a ticker or company name of ${exchange} to open its chart.`}
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
                  {data && (
                    <Link href={companyHref(data.exchange, data.ticker)} className="text-xs font-normal text-primary underline-offset-4 hover:underline">
                      Fundamentals
                    </Link>
                  )}
                </CardTitle>
                <CardDescription className="flex flex-wrap items-center gap-x-3 gap-y-1">
                  {last ? (
                    <>
                      <span className="font-mono text-base font-semibold text-foreground tabular-nums">
                        {data!.currency} {formatPrice(last[4])}
                      </span>
                      <span className={cn("font-mono tabular-nums", change !== null && change > 0 && "text-emerald-600 dark:text-emerald-400",
                        change !== null && change < 0 && "text-rose-600 dark:text-rose-400")}>
                        {change === null ? EMPTY : `${change > 0 ? "+" : ""}${formatNumber(change, 2)}%`}
                      </span>
                      <span>{formatDate(data!.lastDate)} · {data!.tradingDays.toLocaleString("en-US")} trading days since {formatDate(data!.firstDate)} · lot {data!.lotSize} shares</span>
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
                    selected={indicators}
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

            {(has("regime") || has("macro")) && (
              market.isError ? (
                <ErrorNote error={market.error} onRetry={() => market.refetch()} />
              ) : !market.data ? (
                <Skeleton className="h-[220px] w-full" />
              ) : (
                <div className="grid gap-4 xl:grid-cols-2">
                  {has("regime") && <RegimeCard market={market.data} />}
                  {has("macro") && <MacroCard market={market.data} />}
                </div>
              )
            )}

            <div className="grid gap-4 xl:grid-cols-2">
              {levels.data ? <KeyLevelsCard levels={levels.data} /> : levels.isError ? <ErrorNote error={levels.error} onRetry={() => levels.refetch()} /> : <Skeleton className="h-[300px]" />}
              {has("profile") && levels.data && <VolumeProfileCard levels={levels.data} />}
              {snapshot.data ? <SnapshotCard snapshot={snapshot.data} /> : snapshot.isError ? <ErrorNote error={snapshot.error} onRetry={() => snapshot.refetch()} /> : <Skeleton className="h-[300px]" />}
            </div>
          </div>

          <Card className="hidden h-fit lg:block">
            <CardHeader>
              <CardTitle>Indicators</CardTitle>
              <CardDescription>Presets only toggle indicators: no scores, no signals.</CardDescription>
            </CardHeader>
            <CardContent>{picker}</CardContent>
          </Card>
        </div>
      )}

      <footer className="space-y-1 border-t pt-3 text-xs text-muted-foreground">
        <p className="font-medium text-foreground">{DISCLAIMER}</p>
        <p>
          Indicators are computed by Neraca Lab from stored daily prices (split-adjusted; IDX tick sizes and auto-rejection
          limits applied). Events: Yahoo Finance dividends and splits, and splits recorded from the filings.
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
