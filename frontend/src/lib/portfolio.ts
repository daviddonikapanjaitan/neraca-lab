// Portfolio pages (backend PortfolioController, GET /api/v1/portfolio): Position, the sheet Position of
// instruction/Position Portfolio.xlsx, and Performance (planned). Shared by the sidebar, the breadcrumb and the pages.

export const PORTFOLIO_HREF = "/portfolio"
export const PORTFOLIO_PERFORMANCE_HREF = "/portfolio/performance"

export const PORTFOLIO_SECTIONS: { title: string; href: string }[] = [
  { title: "Position", href: PORTFOLIO_HREF },
  { title: "Performance", href: PORTFOLIO_PERFORMANCE_HREF },
]

/**
 * One row of the sheet. Amounts in IDR; the company amounts (ebitdaForward ... operatingProfit, sharesOutstanding) in
 * billions, as the sheet's "(M)" columns.
 */
export type PortfolioPosition = {
  positionId: number
  exchange: string
  ticker: string
  companyName: string
  sector: string | null
  /** the intraday price of a session newer than the stored daily close, else the last daily close */
  lastPrice: number | null
  /** lastPrice - the close of the session before */
  change: number | null
  changePct: number | null
  priceDate: string | null
  /** where lastPrice comes from */
  priceSource: "INTRADAY" | "DAILY" | null
  /** the last trade of an intraday price */
  priceTime: string | null
  /** the latest intraday session stored (Yahoo 5-minute bars), null before the first refresh */
  intraday: IntradaySession | null
  /** change x shares */
  dayGain: number | null
  averagePrice: number
  shares: number
  averageValue: number
  marketValue: number | null
  gainLoss: number | null
  gainLossPct: number | null
  /** averageValue / (averageTotal + cash) */
  portfolioAveragePct: number | null
  /** marketValue / (marketTotal + cash) */
  portfolioMarketPct: number | null
  bookValuePerShare: number | null
  /** the latest filing's profit to the parent made a year / shares (the Valuation tab's forward EPS) */
  forwardEps: number | null
  debtToEquity: number | null
  evEbitda: number | null
  per: number | null
  pbv: number | null
  /** cash dividends per share with an ex date in dividendYear (this year, else last year) */
  dividend: number | null
  dividendYear: number | null
  dividendYield: number | null
  /** "9M 2026" */
  lastFiling: string | null
  periodEnd: string | null
  ebitdaForward: number | null
  sharesOutstanding: number | null
  marketCap: number | null
  /** total liabilities of the latest filing (the sheet's "Total Debt") */
  totalDebt: number | null
  cash: number | null
  /** totalDebt + marketCap - cash */
  ev: number | null
  operatingProfit: number | null
  evOp: number | null
  dateIn: string | null
  /** USD: the company figures were converted from the reporting currency */
  convertedFrom: string | null
  notes: string[]
}

/** The latest intraday session of a stock. */
export type IntradaySession = {
  sessionDate: string
  previousClose: number | null
  open: number | null
  high: number | null
  low: number | null
  volume: number | null
  marketTime: string
  fetchedAt: string
  /** [epoch seconds, close] of each 5-minute bar, oldest first */
  points: [number, number][]
}

/** The IDX trading day as the page shows it. */
export type LiveStatus = {
  phase: "PRE_OPENING" | "SESSION_1" | "BREAK" | "SESSION_2" | "PRE_CLOSING" | "POST_TRADING" | "CLOSED"
  phaseLabel: string
  /** prices can change now */
  trading: boolean
  /** the latest intraday session stored for the portfolio's stocks */
  sessionDate: string | null
  /** the latest intraday fetch */
  updatedAt: string | null
  /** minutes between two scheduled refreshes */
  everyMinutes: number | null
  scheduled: boolean
}

export type PortfolioSummary = {
  cash: number
  totalInvestment: number
  averageTotal: number
  marketTotal: number
  /** marketTotal - averageTotal */
  profitMarket: number
  profitMarketPct: number | null
  /** marketTotal + cash - totalInvestment */
  totalProfit: number
  totalProfitPct: number | null
  /** marketTotal + cash */
  totalValue: number
  positions: number
  priced: number
  /** the stocks' gain or loss of the day: sum of change x shares */
  dayGain: number | null
  /** dayGain / the stocks' value at the closes of the session before */
  dayGainPct: number | null
}

export type AllocationSlice = {
  name: string
  cash: boolean
  value: number
  share: number | null
  tickers: string[]
}

/** GET /api/v1/portfolio */
export type Portfolio = {
  currency: string
  computedAt: string
  summary: PortfolioSummary
  positions: PortfolioPosition[]
  /** largest sector first, the cash last */
  allocation: AllocationSlice[]
  live: LiveStatus
  notes: string[]
}

/** The categorical slots of the sector chart (globals.css --series-1 ... 8); the cash is --series-cash. */
export const SERIES_COLORS = [1, 2, 3, 4, 5, 6, 7, 8].map((i) => `var(--series-${i})`)
export const CASH_COLOR = "var(--series-cash)"
/** Sectors past the seventh are folded into "Other" (the eighth slot), never a new hue. */
export const MAX_SECTOR_SLICES = 7
export const OTHER_COLOR = SERIES_COLORS[7]

/** A slice of the sector chart with its colour; "Other" gathers the smallest sectors past the seventh. */
export type ColoredSlice = AllocationSlice & { color: string; sectors: string[] }

/**
 * The chart's slices and each sector's colour (the table's sector dots use the same). A sector keeps its colour
 * whatever its size: the shown sectors take the categorical slots in alphabetical order. Past seven sectors the
 * smallest fold into "Other" (the eighth slot), never a new hue; the cash is the neutral grey, last.
 */
export function colorSlices(allocation: AllocationSlice[]): { slices: ColoredSlice[]; colorOf: Map<string, string> } {
  const sectors = allocation.filter((s) => !s.cash)
  const cash = allocation.find((s) => s.cash)
  // allocation comes largest first
  const kept = sectors.length > MAX_SECTOR_SLICES ? sectors.slice(0, MAX_SECTOR_SLICES - 1) : sectors
  const rest = sectors.slice(kept.length)
  const colorOf = new Map<string, string>()
  ;[...kept].sort((a, b) => a.name.localeCompare(b.name)).forEach((s, i) => colorOf.set(s.name, SERIES_COLORS[i]))
  rest.forEach((s) => colorOf.set(s.name, OTHER_COLOR))
  const slices: ColoredSlice[] = kept.map((s) => ({ ...s, color: colorOf.get(s.name)!, sectors: [s.name] }))
  if (rest.length > 0) {
    slices.push({
      name: `Other (${rest.length} sectors)`,
      cash: false,
      value: rest.reduce((a, s) => a + s.value, 0),
      share: rest.reduce((a, s) => a + (s.share ?? 0), 0),
      tickers: rest.flatMap((s) => s.tickers),
      color: OTHER_COLOR,
      sectors: rest.map((s) => s.name),
    })
  }
  if (cash) slices.push({ ...cash, color: CASH_COLOR, sectors: [] })
  return { slices, colorOf }
}
