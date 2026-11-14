// Portfolio > Performance (backend PerformanceController, GET /api/v1/portfolio/performance): the sheet
// tracking_performance of instruction/Position Portfolio.xlsx - a row per year, a snapshot per IDX trading day saved at
// or after 17:00 WIB, and the chart comparing the portfolio with IHSG and LQ45. Amounts in IDR, percentages as
// fractions (0.2192 = +21.92%).

export const PERFORMANCE_ALL = "ALL"

/** "?range=2025" for a valid range ("ALL" or a year), "" otherwise (the backend's default: ALL). */
export function performanceQuery(range: string | null | undefined): string {
  const r = range?.trim().toUpperCase()
  return r && (r === PERFORMANCE_ALL || /^\d{4}$/.test(r)) ? `?${new URLSearchParams({ range: r })}` : ""
}

export type YearStatus = "CLOSED" | "CURRENT" | "FUTURE"

/** A year of the sheet. */
export type PerformanceYear = {
  year: number
  status: YearStatus
  /** a row was saved for the year */
  typed: boolean
  /** "Total Invest <year before>": the capital at the start of the year */
  startCapital: number | null
  /** TYPED: the row; YEAR_BEFORE: market value + cash at the end of the year before */
  startFrom: "TYPED" | "YEAR_BEFORE" | null
  /** "Total Invest <year>": money put in during the year */
  newInvestment: number
  cash: number | null
  marketValue: number | null
  averageValue: number | null
  /** LIVE: the portfolio now (current year); SNAPSHOT: the year's last daily snapshot; TYPED: the row */
  endFrom: "LIVE" | "SNAPSHOT" | "TYPED" | null
  endDate: string | null
  /** marketValue + cash */
  totalValue: number | null
  /** POT G/L: totalValue - startCapital - newInvestment */
  profit: number | null
  /** POT G/L % = profit / startCapital */
  profitPct: number | null
  dividend: number | null
  /** dividend / totalValue */
  dividendPct: number | null
  /** IHSG over the year to endDate (index_daily) */
  ihsg: number | null
  lq45: number | null
  /** as the sheet (or the snapshot) recorded it */
  ihsgRecorded: number | null
  lq45Recorded: number | null
  /** profitPct - ihsg */
  portfolioVsIhsg: number | null
  note: string | null
  /** the row as typed (null: none): what the edit form starts from */
  row: PerformanceYearRequest | null
}

export type PerformancePointKind = "BASE" | "YEAR_END" | "SNAPSHOT" | "LIVE"

/** The change since the range's base of each series; null where a series has no value that day. */
export type PerformancePoint = {
  date: string
  portfolio: number | null
  ihsg: number | null
  lq45: number | null
  kind: PerformancePointKind | null
}

export type PerformanceChart = {
  range: string
  /** 1 January of the first year */
  from: string
  /** 31 December of the last year: the axis runs to the year end */
  to: string
  /** the last trading day of the year before: every series starts at 0% there */
  base: string
  points: PerformancePoint[]
  portfolio: number | null
  ihsg: number | null
  lq45: number | null
  latest: string | null
  notes: string[]
}

/** A daily snapshot (saved at or after 17:00 WIB), or the portfolio now (source LIVE, not saved). */
export type PerformanceSnapshot = {
  date: string
  capturedAt: string
  source: "SCHEDULE" | "MANUAL" | "LIVE"
  positions: number
  priced: number
  pricedToday: number
  cash: number
  marketValue: number
  averageValue: number
  totalValue: number
  /** marketValue - averageValue */
  profitLoss: number
  profitLossPct: number | null
  dayGain: number | null
  dayGainPct: number | null
  totalInvestment: number
  yearStartCapital: number | null
  yearNewInvestment: number
  /** the year's POT G/L so far */
  ytdProfit: number | null
  ytdProfitPct: number | null
  ihsgClose: number | null
  ihsgDate: string | null
  ihsgChangePct: number | null
  ihsgYtdPct: number | null
  lq45Close: number | null
  lq45Date: string | null
  lq45ChangePct: number | null
  lq45YtdPct: number | null
}

export type PerformanceSaving = {
  scheduled: boolean
  /** "17:00" (WIB) */
  saveFrom: string
  cron: string
  canSaveNow: boolean
  savedToday: boolean
  next: string
  lastSaved: string | null
  saved: number
  /** why it cannot be saved now */
  reason: string | null
}

/** GET /api/v1/portfolio/performance */
export type Performance = {
  currency: string
  computedAt: string
  currentYear: number
  ranges: string[]
  chart: PerformanceChart
  years: PerformanceYear[]
  /** newest first */
  snapshots: PerformanceSnapshot[]
  live: PerformanceSnapshot | null
  saving: PerformanceSaving
  notes: string[]
}

/** POST /api/v1/portfolio/performance/snapshots */
export type PerformanceSaved = {
  snapshot: PerformanceSnapshot
  message: string
  performance: Performance
}

/** PUT /api/v1/portfolio/performance/years/{year} */
export type PerformanceYearRequest = {
  startCapital: number | null
  newInvestment: number | null
  dividend: number | null
  cash: number | null
  marketValue: number | null
  averageValue: number | null
  ihsgYtd: number | null
  lq45Ytd: number | null
  note: string | null
}

/** The three series, in the categorical order of globals.css (--series-1 ... 3). */
export const PERFORMANCE_SERIES = [
  { key: "portfolio", label: "Portfolio", color: "var(--series-1)" },
  { key: "ihsg", label: "IHSG", color: "var(--series-2)" },
  { key: "lq45", label: "LQ45", color: "var(--series-3)" },
] as const
