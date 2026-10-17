// Technical analysis page (Companies > Technicals): API schemas (validated with zod before charting), the indicator
// catalog and the presets. The backend computes every indicator; the browser only renders.
import { z } from "zod"

import { problemOf, RequestError } from "@/lib/client-api"

// ------------------------------------------------------------------ API schemas

const num = z.number()
const nullableNum = z.number().nullable()
/** [time (seconds, 00:00 UTC of the day), value] */
const point = z.tuple([num, num])

/**
 * STOCK: a listed company; INDEX: an index of the exchange (IHSG, LQ45), charted in index points; FOREX / COMMODITY: a currency
 * pair or commodity (Price Ingestion with the market Forex or Commodities), in its quote currency and unit
 */
const listingType = z.enum(["STOCK", "INDEX", "FOREX", "COMMODITY"]).catch("STOCK")
export type ListingType = z.infer<typeof listingType>

/** DAILY bars, or MONTHLY (the World Bank, IMF or FRED monthly series: coal, cobalt, urea, ...; charted on the monthly interval only) */
const frequency = z.enum(["DAILY", "MONTHLY"]).catch("DAILY")

export const tickerSchema = z.object({
  type: listingType,
  exchange: z.string(),
  ticker: z.string(),
  companyName: z.string().nullable(),
  sector: z.string().nullable(),
  industry: z.string().nullable(),
  active: z.boolean(),
  tradingDays: num,
  firstDate: z.string().nullable(),
  lastDate: z.string().nullable(),
  limitedHistory: z.boolean(),
  /** a currency pair or commodity: "IDR per USD", DAILY / MONTHLY */
  unit: z.string().nullable().optional(),
  frequency: frequency.optional(),
})
export type TickerInfo = z.infer<typeof tickerSchema>

const header = {
  type: listingType,
  exchange: z.string(),
  ticker: z.string(),
  companyName: z.string().nullable(),
  currency: z.string(),
  interval: z.enum(["D", "W", "M"]),
  tradingDays: num,
  firstDate: z.string(),
  lastDate: z.string(),
  limitedHistory: z.boolean(),
  minHistoryDays: num,
  /** shares per lot; null for an index, a currency pair or commodity */
  lotSize: nullableNum,
  /** a currency pair or commodity: its unit ("USD per barrel"); null for a stock or index */
  unit: z.string().nullable().optional(),
  frequency: frequency.optional(),
  /** a currency pair or commodity: "Yahoo Finance IDR=X", "World Bank Pink Sheet, Coal, Australian" */
  source: z.string().nullable().optional(),
}

export const ohlcvSchema = z.object({
  ...header,
  adjustment: z.enum(["split", "total"]),
  priceBasis: z.string(),
  columns: z.array(z.string()),
  /** time, open, high, low, close, volume, value */
  bars: z.array(z.tuple([num, num, num, num, num, num, num])),
  /** time of the bar, ARA / ARB, change %, the day it happened */
  autoRejection: z.array(z.tuple([num, z.enum(["ARA", "ARB"]), num, z.string()])),
})
export type Ohlcv = z.infer<typeof ohlcvSchema>

export const indicatorsSchema = z.object({
  ...header,
  adjustment: z.enum(["split", "total"]),
  series: z.record(z.string(), z.array(point)),
})
export type IndicatorSeries = z.infer<typeof indicatorsSchema>

const zoneSchema = z.object({
  type: z.enum(["SUPPORT", "RESISTANCE", "PIVOT"]),
  low: num,
  high: num,
  mid: num,
  touches: num,
  firstTouch: z.string(),
  lastTouch: z.string(),
  distancePct: nullableNum,
})
export type Zone = z.infer<typeof zoneSchema>

const pricePoint = z.object({ date: z.string(), price: num })

const retracementSchema = z.object({
  direction: z.enum(["UP", "DOWN"]),
  swingStart: pricePoint,
  swingEnd: pricePoint,
  amplitude: num,
  majorSwingAtrMultiple: num,
  levels: z.array(z.object({ ratio: num, price: num, distancePct: nullableNum })),
})

const keyLevelSchema = z.object({
  label: z.string(),
  price: num,
  low: nullableNum,
  high: nullableNum,
  distancePct: nullableNum,
  detail: z.string(),
})
export type KeyLevel = z.infer<typeof keyLevelSchema>

export const levelsSchema = z.object({
  exchange: z.string(),
  ticker: z.string(),
  currency: z.string(),
  lastDate: z.string(),
  close: num,
  tickSize: num,
  zones: z.array(zoneSchema),
  retracement: retracementSchema.nullable(),
  swings: z.object({ highs: z.array(pricePoint), lows: z.array(pricePoint) }).passthrough(),
  week52: z.object({
    high: num,
    highDate: z.string(),
    low: num,
    lowDate: z.string(),
    distanceFromHighPct: nullableNum,
    distanceFromLowPct: nullableNum,
    complete: z.boolean(),
  }),
  allTimeHigh: z.object({ price: num, date: z.string(), drawdownPct: nullableNum, since: z.string() }),
  base: z
    .object({
      detected: z.boolean(),
      startDate: z.string().optional(),
      endDate: z.string().optional(),
      days: num.optional(),
      high: num.optional(),
      low: num.optional(),
      depthPct: num.optional(),
    })
    .passthrough(),
  volumeProfile: z.object({
    days: num,
    from: z.string().nullable(),
    to: z.string().nullable(),
    buckets: z.array(z.object({ low: num, high: num, price: num, volume: num, sharePct: nullableNum })),
    topNodes: z.array(z.object({ price: num, low: num, high: num, sharePct: nullableNum, distancePct: nullableNum }).passthrough()),
  }),
  keyLevels: z.array(keyLevelSchema),
})
export type Levels = z.infer<typeof levelsSchema>

export const eventsSchema = z.object({
  exchange: z.string(),
  ticker: z.string(),
  events: z.array(
    z.object({
      date: z.string(),
      time: num,
      type: z.string(),
      value: z.string().nullable(),
      description: z.string().nullable(),
      source: z.string(),
      sourceUrl: z.string().nullable(),
    })
  ),
  eventsFetchedAt: z.string().nullable(),
  note: z.string(),
})
export type Events = z.infer<typeof eventsSchema>

/** The agent-ready snapshot (docs/indicator-snapshot.md); groups are kept loose: the panel shows them as data. */
export const snapshotSchema = z
  .object({
    /** 3: technical only, no macro section (docs/indicator-snapshot.md) */
    schemaVersion: z.number().int().min(1),
    exchange: z.string(),
    ticker: z.string(),
    asOfDate: z.string(),
    currency: z.string(),
    history: z.object({ tradingDays: num, limited: z.boolean(), minHistoryDays: num, firstDate: z.string() }).passthrough(),
    price: z.record(z.string(), z.unknown()),
    trend: z.record(z.string(), z.unknown()),
    momentum: z.record(z.string(), z.unknown()),
    volatility: z.record(z.string(), z.unknown()),
    volume: z.record(z.string(), z.unknown()),
    structure: z.record(z.string(), z.unknown()),
    relativeStrength: z.record(z.string(), z.unknown()),
    marketRegime: z.record(z.string(), z.unknown()),
    events: z.record(z.string(), z.unknown()),
    risk: z.record(z.string(), z.unknown()),
    notes: z.array(z.string()),
  })
  .passthrough()
export type Snapshot = z.infer<typeof snapshotSchema>

const priceLevelSchema = z.object({
  price: num,
  /** LIMIT: at or below (a sell: at or above); STOP: at or above for a buy, at or below for the stop */
  type: z.enum(["LIMIT", "STOP"]),
  basis: z.string(),
  distancePct: nullableNum,
})
export type PriceLevel = z.infer<typeof priceLevelSchema>

export const STANCES = ["BUY", "ACCUMULATE", "WAIT", "AVOID"] as const
export type Stance = (typeof STANCES)[number]

const presetSummarySchema = z.object({
  preset: z.enum(["murphy", "gill", "ptj", "druckenmiller"]),
  name: z.string(),
  style: z.string(),
  stance: z.enum(STANCES),
  stanceLabel: z.string(),
  score: num,
  maxScore: num,
  buy: priceLevelSchema.nullable(),
  sell: priceLevelSchema.nullable(),
  stopLoss: priceLevelSchema.nullable(),
  rewardRisk: nullableNum,
  thesis: z.string(),
  points: z.array(z.object({ tone: z.enum(["POSITIVE", "NEGATIVE", "NEUTRAL"]), text: z.string() })),
})
export type PresetSummary = z.infer<typeof presetSummarySchema>

/** GET .../summary: the four preset summaries (fixed rules over the indicators; no AI model) */
export const summarySchema = z.object({
  exchange: z.string(),
  ticker: z.string(),
  companyName: z.string().nullable(),
  asOfDate: z.string(),
  currency: z.string(),
  close: num,
  type: listingType,
  /** decimals of the prices: 0 for an IDX price, 2 for an index, 3 for USD/JPY */
  priceDecimals: num.optional(),
  /** a currency pair or commodity: its unit */
  unit: z.string().nullable().optional(),
  method: z.string(),
  rulesVersion: z.string(),
  summaries: z.array(presetSummarySchema),
  caveats: z.array(z.string()),
  disclaimer: z.string(),
})
export type Summary = z.infer<typeof summarySchema>

export const marketSchema = z.object({
  exchange: z.string(),
  asOfDate: z.string().nullable(),
  index: z.string(),
  regime: z
    .object({
      date: z.string(),
      close: num,
      sma200: nullableNum,
      aboveSma200: z.boolean().nullable(),
      vsSma200Pct: nullableNum,
      closes: z.array(point),
      sma200Series: z.array(point),
    })
    .partial(),
  breadth: z.object({ above200Pct: nullableNum, counted: num, universe: num, note: z.string() }),
})
export type Market = z.infer<typeof marketSchema>

// ------------------------------------------------------------------ fetching

/** GET a route handler and validate the JSON: a malformed answer never reaches the chart. */
export async function fetchValidated<T>(url: string, schema: z.ZodType<T>, signal?: AbortSignal): Promise<T> {
  let response: Response
  try {
    response = await fetch(url, { cache: "no-store", signal })
  } catch (e) {
    if (e instanceof DOMException && e.name === "AbortError") throw e
    throw new RequestError(0, "Cannot reach the Neraca Lab server. Check your connection and try again.")
  }
  if (!response.ok) throw await problemOf(response)
  const parsed = schema.safeParse(await response.json())
  if (!parsed.success) {
    throw new RequestError(502, `Unexpected response from ${url.split("?")[0]}: ${parsed.error.issues[0]?.message ?? "invalid"}`)
  }
  return parsed.data
}

export function technicalUrl(exchange: string, ticker: string, resource: string, params?: Record<string, string>): string {
  const query = params ? `?${new URLSearchParams(params)}` : ""
  return `/api/technical/${encodeURIComponent(exchange)}/${encodeURIComponent(ticker)}/${resource}${query}`
}

// ------------------------------------------------------------------ settings and catalog

export type Interval = "D" | "W" | "M"
export type RangeCode = "3M" | "6M" | "1Y" | "3Y" | "5Y" | "MAX"
export type Adjustment = "split" | "total"

export const INTERVALS: { value: Interval; label: string }[] = [
  { value: "D", label: "Daily" },
  { value: "W", label: "Weekly" },
  { value: "M", label: "Monthly" },
]

export const RANGES: RangeCode[] = ["3M", "6M", "1Y", "3Y", "5Y", "MAX"]

/** Everything the chart can show; ids are stored in the settings and the URL. */
export type IndicatorId =
  | "sma20" | "sma50" | "sma100" | "sma200" | "ema20" | "ema50" | "bb" | "channel20" | "channel50" | "atrBands"
  | "zones" | "retracements" | "extremes" | "base" | "events" | "autoRejection" | "ihsg"
  | "volume" | "rsi" | "macd" | "stoch" | "obv" | "adx" | "atrPct" | "rs" | "mom"
  | "profile" | "regime"

export interface IndicatorDef {
  id: IndicatorId
  label: string
  /** series of /indicators it needs (groups expand on the server: bb -> bb.upper, ...) */
  series?: string[]
  /** price: overlay on the candles; pane: own lower pane; levels / markers: from /levels or /events; panel: side panel */
  kind: "price" | "pane" | "levels" | "markers" | "panel"
}

export const INDICATORS: IndicatorDef[] = [
  { id: "sma20", label: "SMA 20", series: ["sma20"], kind: "price" },
  { id: "sma50", label: "SMA 50", series: ["sma50"], kind: "price" },
  { id: "sma100", label: "SMA 100", series: ["sma100"], kind: "price" },
  { id: "sma200", label: "SMA 200", series: ["sma200"], kind: "price" },
  { id: "ema20", label: "EMA 20", series: ["ema20"], kind: "price" },
  { id: "ema50", label: "EMA 50", series: ["ema50"], kind: "price" },
  { id: "bb", label: "Bollinger Bands (20, 2)", series: ["bb"], kind: "price" },
  { id: "channel20", label: "20-bar high / low channel", series: ["channel20"], kind: "price" },
  { id: "channel50", label: "50-bar high / low channel", series: ["channel50"], kind: "price" },
  { id: "atrBands", label: "ATR bands (EMA 20 ± 2 ATR)", series: ["atrBands"], kind: "price" },
  { id: "ihsg", label: "IHSG rebased (comparison)", series: ["index"], kind: "price" },
  { id: "zones", label: "Support / resistance zones", kind: "levels" },
  { id: "retracements", label: "Retracements (last major swing)", kind: "levels" },
  { id: "extremes", label: "52-week high / low, all-time high", kind: "levels" },
  { id: "base", label: "Base range", kind: "levels" },
  { id: "events", label: "Corporate events", kind: "markers" },
  { id: "autoRejection", label: "ARA / ARB days", kind: "markers" },
  { id: "volume", label: "Volume + 50-bar average", series: ["volSma50"], kind: "pane" },
  { id: "rsi", label: "RSI (14)", series: ["rsi14"], kind: "pane" },
  { id: "macd", label: "MACD (12, 26, 9)", series: ["macd"], kind: "pane" },
  { id: "stoch", label: "Stochastic (14, 3, 3)", series: ["stoch"], kind: "pane" },
  { id: "obv", label: "On-balance volume", series: ["obv"], kind: "pane" },
  { id: "adx", label: "ADX (14) with +DI / −DI", series: ["adx"], kind: "pane" },
  { id: "atrPct", label: "ATR % of price", series: ["atrPct"], kind: "pane" },
  { id: "rs", label: "Relative strength vs IHSG", series: ["rs"], kind: "pane" },
  { id: "mom", label: "12-1 month momentum", series: ["mom12_1"], kind: "pane" },
  { id: "profile", label: "Volume-by-price profile", kind: "panel" },
  { id: "regime", label: "IHSG regime panel", kind: "panel" },
]

export const INDICATOR_GROUPS: { title: string; ids: IndicatorId[] }[] = [
  { title: "Moving averages", ids: ["sma20", "sma50", "sma100", "sma200", "ema20", "ema50"] },
  { title: "Bands & channels", ids: ["bb", "channel20", "channel50", "atrBands"] },
  { title: "Levels", ids: ["zones", "retracements", "extremes", "base"] },
  { title: "Markers & comparison", ids: ["events", "autoRejection", "ihsg"] },
  { title: "Lower panes", ids: ["volume", "rsi", "macd", "stoch", "obv", "adx", "atrPct", "rs", "mom"] },
  { title: "Panels", ids: ["profile", "regime"] },
]

export type PresetId = "murphy" | "gill" | "ptj" | "druckenmiller"

/** Presets toggle indicators; the backend summarises the chart in each preset's style (GET .../summary). */
export const PRESETS: { id: PresetId; label: string; description: string; indicators: IndicatorId[] }[] = [
  {
    id: "murphy",
    label: "Murphy",
    description: "Trend, levels and confirmation: SMA 50/200, zones, retracements, RSI, MACD, volume, OBV, RS vs IHSG",
    indicators: ["sma50", "sma200", "zones", "retracements", "rsi", "macd", "volume", "obv", "rs"],
  },
  {
    id: "gill",
    label: "Keith Gill",
    description: "Long base and positioning: SMA 200, volume, volume profile, base range, 52-week range, events",
    indicators: ["sma200", "volume", "profile", "base", "extremes", "events"],
  },
  {
    id: "ptj",
    label: "Paul Tudor Jones",
    description: "Trend and risk: SMA 200, 20/50-bar channels, ADX, ATR bands",
    indicators: ["sma200", "channel20", "channel50", "adx", "atrBands"],
  },
  {
    id: "druckenmiller",
    label: "Druckenmiller",
    description: "Leadership and the market: RS vs IHSG, 12-1 month momentum, IHSG regime",
    indicators: ["rs", "mom", "regime"],
  },
]

export const DEFAULT_INDICATORS: IndicatorId[] = ["sma50", "sma200", "volume", "events", "autoRejection"]

/**
 * What an index (IHSG), a currency pair or a commodity cannot show: a comparison with IHSG (itself), corporate events,
 * auto-rejection limits.
 */
export const NOT_FOR_INDEX: IndicatorId[] = ["rs", "ihsg", "events", "autoRejection"]

/** The index every listing is compared with (relative strength, the rebased line, the regime panel). */
export const BENCHMARK_INDEX = "IHSG"

/** The indexes of IDX, listed first by the ticker search: IHSG and LQ45. */
export const INDEX_TICKERS = ["IHSG", "LQ45"]

/** What an index other than the benchmark (LQ45) cannot show: events, auto-rejection; it is compared with IHSG. */
export const NOT_FOR_OTHER_INDEX: IndicatorId[] = ["events", "autoRejection"]

/** What a monthly series (coal, cobalt, urea, ...) cannot show besides: the levels, the volume profile (they need daily bars). */
export const NOT_FOR_MONTHLY: IndicatorId[] = [...NOT_FOR_INDEX, "zones", "retracements", "extremes", "base", "profile"]

/**
 * Daily commodities charted as a plain line of the close instead of candlesticks: palm oil, in US dollars and in
 * ringgit. Every monthly series (the World Bank, IMF and FRED averages: coal, LNG, nickel, tin, cobalt, rubber, pulp,
 * fertilizers) is a line too - it has no real open / high / low. The other daily commodities (Brent, WTI, natural gas,
 * the metals, iron ore, steel, the grains, sugar) keep their candlesticks.
 */
export const LINE_CHART_COMMODITIES = ["PALM_OIL", "PALM_OIL_MYR"]

/** Whether a listing's price is charted as a plain line of the close (see {@link LINE_CHART_COMMODITIES}). */
export function chartsAsLine(type: ListingType, ticker: string, frequency?: "DAILY" | "MONTHLY"): boolean {
  return type === "COMMODITY" && (frequency === "MONTHLY" || LINE_CHART_COMMODITIES.includes(ticker.toUpperCase()))
}

/** Decimals of a tick size: 25 -> 0, 0.01 -> 2, 0.001 -> 3, 0.25 -> 2 */
export function decimalsOf(tick: number | null | undefined): number {
  if (tick === null || tick === undefined || !Number.isFinite(tick) || tick <= 0) return 2
  const text = String(tick)
  const dot = text.indexOf(".")
  return dot < 0 ? 0 : Math.min(8, text.length - dot - 1)
}

export interface ChartSettings {
  exchange: string
  ticker: string | null
  interval: Interval
  range: RangeCode
  adjustment: Adjustment
  indicators: IndicatorId[]
  preset: PresetId | null
}

const settingsSchema = z.object({
  exchange: z.string().catch("IDX"),
  ticker: z.string().nullable().catch(null),
  interval: z.enum(["D", "W", "M"]).catch("D"),
  range: z.enum(["3M", "6M", "1Y", "3Y", "5Y", "MAX"]).catch("1Y"),
  adjustment: z.enum(["split", "total"]).catch("split"),
  indicators: z
    .array(z.string())
    .catch(DEFAULT_INDICATORS)
    .transform((ids) => ids.filter((id): id is IndicatorId => INDICATORS.some((i) => i.id === id))),
  preset: z.enum(["murphy", "gill", "ptj", "druckenmiller"]).nullable().catch(null),
})

export const DEFAULT_SETTINGS: ChartSettings = {
  exchange: "IDX",
  ticker: null,
  interval: "D",
  range: "1Y",
  adjustment: "split",
  indicators: DEFAULT_INDICATORS,
  preset: null,
}

/** Settings from any stored or URL value; unknown fields fall back to the defaults. */
export function parseSettings(value: unknown): ChartSettings {
  const parsed = settingsSchema.safeParse({ ...DEFAULT_SETTINGS, ...(typeof value === "object" && value ? value : {}) })
  return parsed.success ? parsed.data : DEFAULT_SETTINGS
}

/** The series /indicators must return for the selected indicators. */
export function seriesFor(ids: IndicatorId[]): string[] {
  return Array.from(new Set(INDICATORS.filter((i) => ids.includes(i.id)).flatMap((i) => i.series ?? [])))
}

export const DISCLAIMER = "For information only. Not investment advice."

/**
 * A level: an index in points with 2 decimals; an IDX price in whole rupiah (decimals only where it has them); with a
 * number, exactly that many decimals (USD/JPY: 3)
 */
export function formatLevel(value: number | null | undefined, decimals: boolean | number): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return "—"
  if (typeof decimals === "number") {
    return value.toLocaleString("en-US", { minimumFractionDigits: decimals, maximumFractionDigits: decimals })
  }
  return value.toLocaleString("en-US", { minimumFractionDigits: decimals ? 2 : 0, maximumFractionDigits: 2 })
}

export const TRADINGVIEW_NOTICE = "TradingView Lightweight Charts™ Copyright (с) 2025 TradingView, Inc. https://www.tradingview.com/"

// ------------------------------------------------------------------ export

/** Downloads rows as a CSV file (values quoted where needed). */
export function downloadCsv(fileName: string, header: string[], rows: (string | number | null | undefined)[][]) {
  const cell = (v: string | number | null | undefined) => {
    if (v === null || v === undefined) return ""
    const s = String(v)
    return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
  }
  const text = [header, ...rows].map((r) => r.map(cell).join(",")).join("\n")
  const url = URL.createObjectURL(new Blob([text], { type: "text/csv;charset=utf-8" }))
  const a = document.createElement("a")
  a.href = url
  a.download = fileName
  a.click()
  URL.revokeObjectURL(url)
}

/** Flattens a nested object into [path, value] rows (arrays of objects are summarised by their length). */
export function flatten(value: unknown, prefix = ""): [string, string][] {
  if (value === null || value === undefined) return [[prefix, ""]]
  if (Array.isArray(value)) {
    if (value.every((v) => typeof v !== "object" || v === null)) return [[prefix, value.join("; ")]]
    return value.flatMap((v, i) => flatten(v, `${prefix}[${i}]`))
  }
  if (typeof value === "object") {
    return Object.entries(value as Record<string, unknown>).flatMap(([k, v]) => flatten(v, prefix ? `${prefix}.${k}` : k))
  }
  return [[prefix, String(value)]]
}
