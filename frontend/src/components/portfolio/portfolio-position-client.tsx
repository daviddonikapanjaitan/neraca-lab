"use client"

import { useCallback, useEffect, useMemo, useState } from "react"
import Link from "next/link"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import {
  CircleAlertIcon,
  DownloadIcon,
  PencilIcon,
  LoaderIcon,
  PlusIcon,
  RefreshCwIcon,
  Trash2Icon,
  TrendingDownIcon,
  TrendingUpIcon,
  WalletIcon,
} from "lucide-react"

import { EmptyState } from "@/components/empty-state"
import { AllocationChart } from "@/components/portfolio/allocation-chart"
import { DragScroll } from "@/components/portfolio/drag-scroll"
import { IntradaySparkline, wibTime } from "@/components/portfolio/intraday-sparkline"
import { PositionSheet, SettingsSheet } from "@/components/portfolio/position-sheet"
import { SortIcon, type SortDir } from "@/components/sort-icon"
import {
  AlertDialog,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { RequestError, requestJson } from "@/lib/client-api"
import { EMPTY, formatDate, formatMultiple, formatNumber, formatPercent, formatTimestamp } from "@/lib/format"
import { companyHref } from "@/lib/links"
import { colorSlices, type LiveStatus, type Portfolio, type PortfolioPosition } from "@/lib/portfolio"
import { downloadCsv } from "@/lib/technical"
import { cn } from "@/lib/utils"

type Key = keyof PortfolioPosition

/** A column of the sheet Position. */
type Column = {
  key: Key
  label: string
  /** the header's tooltip: what the column is */
  title: string
  align?: "left" | "right"
  /** coloured by its sign */
  signed?: boolean
  /** sorted by this field instead of key */
  sortBy?: Key
  render: (p: PortfolioPosition, ctx: RenderContext) => React.ReactNode
  /** the CSV value */
  csv: (p: PortfolioPosition) => string | number | null
}

type RenderContext = { colorOf: Map<string, string> }

/** Columns grouped under one header, in the order of the sheet. */
type Group = { label: string; columns: Column[] }

const rp = (v: number | null | undefined, digits = 0) => (v === null || v === undefined ? EMPTY : formatNumber(v, digits))
const bn = (v: number | null | undefined) => (v === null || v === undefined ? EMPTY : formatNumber(v, 2))
const pct = (v: number | null | undefined) => formatPercent(v, 2)
const x = (v: number | null | undefined) => formatMultiple(v)
const raw = (v: number | null | undefined, digits = 4) =>
  v === null || v === undefined || !Number.isFinite(v) ? null : Number(v.toFixed(digits))

function signedNumber(v: number | null | undefined, digits: number) {
  if (v === null || v === undefined) return EMPTY
  return `${v > 0 ? "+" : ""}${formatNumber(v, digits)}`
}

function signedPercent(v: number | null | undefined) {
  if (v === null || v === undefined) return EMPTY
  return `${v > 0 ? "+" : ""}${formatPercent(v, 2)}`
}

function signTone(v: number | null | undefined) {
  if (v === null || v === undefined || v === 0) return undefined
  return v > 0 ? "text-emerald-600 dark:text-emerald-400" : "text-rose-600 dark:text-rose-400"
}

/** The sheet's columns (Ticker and the name are the sticky first column). */
const GROUPS: Group[] = [
  {
    label: "Price (Rp)",
    columns: [
      {
        key: "lastPrice",
        label: "Last",
        title: "Latest price: the intraday price during the IDX session (●), else the last daily close",
        render: (p) => (
          <span className="inline-flex items-center gap-1.5">
            {p.priceSource === "INTRADAY" && (
              <span
                className="size-1.5 rounded-full bg-emerald-500"
                title={`Intraday price${p.priceTime ? ` at ${wibTime(Date.parse(p.priceTime) / 1000)} WIB` : ""}`}
              />
            )}
            {rp(p.lastPrice, 2)}
          </span>
        ),
        csv: (p) => raw(p.lastPrice),
      },
      { key: "change", label: "Change", title: "Last price − the close of the session before", signed: true, render: (p) => signedNumber(p.change, 2), csv: (p) => raw(p.change) },
      { key: "changePct", label: "Change %", title: "Change ÷ the close of the session before", signed: true, render: (p) => signedPercent(p.changePct), csv: (p) => raw(p.changePct, 6) },
      {
        key: "intraday",
        sortBy: "changePct",
        label: "Today",
        title: "The day from the open to the close of the IDX session (5-minute prices; dashed: the previous close)",
        align: "left",
        render: (p) =>
          p.intraday ? <IntradaySparkline session={p.intraday} last={p.lastPrice} /> : <span className="text-muted-foreground">{EMPTY}</span>,
        csv: (p) => p.intraday?.sessionDate ?? null,
      },
      { key: "dayGain", label: "Day G/L", title: "Change × shares: the position's gain or loss of the day", signed: true, render: (p) => signedNumber(p.dayGain, 0), csv: (p) => raw(p.dayGain, 2) },
    ],
  },
  {
    label: "Weight",
    columns: [
      { key: "portfolioAveragePct", label: "Port. %", title: "Avg value ÷ (average total + cash)", render: (p) => pct(p.portfolioAveragePct), csv: (p) => raw(p.portfolioAveragePct, 6) },
      { key: "portfolioMarketPct", label: "Port. mkt %", title: "Market value ÷ (market total + cash)", render: (p) => pct(p.portfolioMarketPct), csv: (p) => raw(p.portfolioMarketPct, 6) },
    ],
  },
  {
    label: "Position (Rp)",
    columns: [
      { key: "averagePrice", label: "Avg price", title: "Average buy price per share", render: (p) => rp(p.averagePrice, 2), csv: (p) => raw(p.averagePrice) },
      { key: "shares", label: "Shares", title: "Number of shares held", render: (p) => rp(p.shares), csv: (p) => p.shares },
      { key: "marketValue", label: "Market value", title: "Last price × shares", render: (p) => rp(p.marketValue), csv: (p) => raw(p.marketValue, 2) },
      { key: "averageValue", label: "Avg value", title: "Average price × shares", render: (p) => rp(p.averageValue), csv: (p) => raw(p.averageValue, 2) },
      { key: "gainLoss", label: "G/L", title: "Market value − avg value", signed: true, render: (p) => signedNumber(p.gainLoss, 0), csv: (p) => raw(p.gainLoss, 2) },
      { key: "gainLossPct", label: "% G/L", title: "G/L ÷ avg value", signed: true, render: (p) => signedPercent(p.gainLossPct), csv: (p) => raw(p.gainLossPct, 6) },
    ],
  },
  {
    label: "Valuation",
    columns: [
      { key: "bookValuePerShare", label: "BVPS", title: "Book value per share (Rp), latest filing", render: (p) => rp(p.bookValuePerShare, 2), csv: (p) => raw(p.bookValuePerShare) },
      { key: "forwardEps", label: "EPS fwd", title: "Forward EPS (Rp): the latest filing's profit to the parent made a year ÷ shares", render: (p) => rp(p.forwardEps, 2), csv: (p) => raw(p.forwardEps) },
      { key: "debtToEquity", label: "D/E", title: "Debt to equity (interest-bearing debt ÷ equity)", render: (p) => x(p.debtToEquity), csv: (p) => raw(p.debtToEquity) },
      { key: "evEbitda", label: "EV/EBITDA", title: "EV ÷ forward EBITDA", render: (p) => x(p.evEbitda), csv: (p) => raw(p.evEbitda) },
      { key: "per", label: "PER", title: "Last price ÷ forward EPS", render: (p) => x(p.per), csv: (p) => raw(p.per) },
      { key: "pbv", label: "PBV", title: "Last price ÷ BVPS", render: (p) => x(p.pbv), csv: (p) => raw(p.pbv) },
    ],
  },
  {
    label: "Dividend",
    columns: [
      {
        key: "dividend",
        label: "Rp / share",
        title: "Cash dividends per share with an ex date this year, else last year (Profile tab)",
        render: (p) =>
          p.dividend === null ? (
            EMPTY
          ) : (
            <span>
              {rp(p.dividend, 2)} <span className="text-[10px] text-muted-foreground">{p.dividendYear}</span>
            </span>
          ),
        csv: (p) => raw(p.dividend),
      },
      { key: "dividendYield", label: "Yield", title: "Dividend ÷ last price", render: (p) => pct(p.dividendYield), csv: (p) => raw(p.dividendYield, 6) },
    ],
  },
  {
    label: "Company · Rp bn",
    columns: [
      { key: "lastFiling", label: "Last filing", title: "Latest financial statements (Q1 / H1 / 9M / FY)", align: "left", render: (p) => p.lastFiling ?? EMPTY, csv: (p) => p.lastFiling },
      {
        key: "sector",
        label: "Sector",
        title: "Sector of Companies > Fundamentals",
        align: "left",
        render: (p, ctx) =>
          p.sector ? (
            <span className="inline-flex items-center gap-1.5">
              <span className="size-2 shrink-0 rounded-full" style={{ background: ctx.colorOf.get(p.sector) }} aria-hidden />
              {p.sector}
            </span>
          ) : (
            EMPTY
          ),
        csv: (p) => p.sector,
      },
      { key: "ebitdaForward", label: "EBITDA fwd", title: "Forward EBITDA: the latest filing made a year (Rp bn)", render: (p) => bn(p.ebitdaForward), csv: (p) => raw(p.ebitdaForward) },
      { key: "sharesOutstanding", label: "Shares out", title: "Shares outstanding (bn)", render: (p) => (p.sharesOutstanding === null ? EMPTY : formatNumber(p.sharesOutstanding, 3)), csv: (p) => raw(p.sharesOutstanding, 6) },
      { key: "marketCap", label: "Market cap", title: "Last price × shares outstanding (Rp bn)", render: (p) => bn(p.marketCap), csv: (p) => raw(p.marketCap) },
      { key: "totalDebt", label: "Total debt", title: "Total liabilities of the latest filing, as in the sheet (Rp bn)", render: (p) => bn(p.totalDebt), csv: (p) => raw(p.totalDebt) },
      { key: "cash", label: "Cash", title: "Cash and equivalents of the latest filing (Rp bn)", render: (p) => bn(p.cash), csv: (p) => raw(p.cash) },
      { key: "ev", label: "EV", title: "Total debt + market cap − cash (Rp bn)", render: (p) => bn(p.ev), csv: (p) => raw(p.ev) },
      { key: "operatingProfit", label: "OP fwd", title: "Forward operating profit: the latest filing made a year (Rp bn)", render: (p) => bn(p.operatingProfit), csv: (p) => raw(p.operatingProfit) },
      { key: "evOp", label: "EV/OP", title: "EV ÷ forward operating profit", render: (p) => x(p.evOp), csv: (p) => raw(p.evOp) },
    ],
  },
  {
    label: "",
    columns: [
      { key: "dateIn", label: "Date in", title: "Date of the first buy", align: "left", render: (p) => (p.dateIn ? formatDate(p.dateIn) : EMPTY), csv: (p) => p.dateIn },
    ],
  },
]

const COLUMNS = GROUPS.flatMap((g) => g.columns)

/** The page reads the portfolio again this often while the IDX day is on (the backend refreshes every few minutes). */
const POLL_MS = 60_000
/** The first column of each group draws the divider before it. */
const GROUP_START = new Set(GROUPS.map((g) => g.columns[0].key))

function compare(a: PortfolioPosition, b: PortfolioPosition, key: Key): number {
  const va = a[key]
  const vb = b[key]
  // missing values always last, whatever the direction
  if (va === null || va === undefined) return vb === null || vb === undefined ? 0 : 1
  if (vb === null || vb === undefined) return -1
  if (typeof va === "number" && typeof vb === "number") return va - vb
  return String(va).localeCompare(String(vb))
}

/**
 * Portfolio > Position: the metrics, the positions with the columns of the sheet Position of
 * instruction/Position Portfolio.xlsx, and the allocation by sector. Every change answers the whole portfolio, so the
 * page shows what the backend computed.
 */
export function PortfolioPositionClient({ initial }: { initial: Portfolio }) {
  // the company search of the add form (shared with Technicals) reads through React Query
  const [client] = useState(
    () => new QueryClient({ defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: false } } })
  )
  return (
    <QueryClientProvider client={client}>
      <PositionPage initial={initial} />
    </QueryClientProvider>
  )
}

function PositionPage({ initial }: { initial: Portfolio }) {
  const [portfolio, setPortfolio] = useState(initial)
  const [sortKey, setSortKey] = useState<Key>("portfolioMarketPct")
  const [sortDir, setSortDir] = useState<SortDir>("desc")
  const [editing, setEditing] = useState<PortfolioPosition | null>(null)
  const [adding, setAdding] = useState(false)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [removing, setRemoving] = useState<PortfolioPosition | null>(null)
  const [refreshing, setRefreshing] = useState(false)
  const [refreshError, setRefreshError] = useState<string | null>(null)

  const live = portfolio.live
  const dayOn = live.phase !== "CLOSED"

  // read the portfolio again every minute while the IDX day is on and the page is visible
  const reload = useCallback(async () => {
    try {
      const { body } = await requestJson<Portfolio>("/api/portfolio")
      setPortfolio(body)
    } catch {
      // the next round tries again
    }
  }, [])
  useEffect(() => {
    if (!dayOn) return
    const id = setInterval(() => {
      if (document.visibilityState === "visible") void reload()
    }, POLL_MS)
    const onVisible = () => {
      if (document.visibilityState === "visible") void reload()
    }
    document.addEventListener("visibilitychange", onVisible)
    return () => {
      clearInterval(id)
      document.removeEventListener("visibilitychange", onVisible)
    }
  }, [dayOn, reload])

  async function refreshNow() {
    if (refreshing) return
    setRefreshing(true)
    setRefreshError(null)
    try {
      const { body } = await requestJson<Portfolio>("/api/portfolio/intraday/refresh", { method: "POST" })
      setPortfolio(body)
    } catch (e) {
      setRefreshError(e instanceof RequestError ? e.message : "The prices could not be refreshed.")
    } finally {
      setRefreshing(false)
    }
  }

  const s = portfolio.summary
  const { slices, colorOf } = useMemo(() => colorSlices(portfolio.allocation), [portfolio.allocation])
  const weights = useMemo(
    () => new Map(portfolio.positions.map((p) => [p.ticker, p.portfolioMarketPct])),
    [portfolio.positions]
  )
  const rows = useMemo(() => {
    const out = [...portfolio.positions]
    out.sort((a, b) => {
      const r = compare(a, b, sortKey)
      const bothPresent = a[sortKey] !== null && b[sortKey] !== null
      return sortDir === "asc" || !bothPresent ? r : -r
    })
    return out
  }, [portfolio.positions, sortKey, sortDir])

  function toggleSort(key: Key) {
    if (key === sortKey) setSortDir(sortDir === "asc" ? "desc" : "asc")
    else {
      setSortKey(key)
      setSortDir(key === "ticker" || key === "sector" || key === "lastFiling" || key === "dateIn" ? "asc" : "desc")
    }
  }

  function exportCsv() {
    downloadCsv(
      `portfolio-position-${new Date().toISOString().slice(0, 10)}.csv`,
      ["Ticker", "Company", ...COLUMNS.map((c) => c.label)],
      rows.map((p) => [p.ticker, p.companyName, ...COLUMNS.map((c) => c.csv(p))])
    )
  }

  const sortButton = (key: Key, label: string, title: string, align: "left" | "right") => (
    <button
      type="button"
      onClick={() => toggleSort(key)}
      title={title}
      className={cn(
        "inline-flex items-center gap-0.5 font-medium whitespace-nowrap hover:text-foreground",
        sortKey === key && "text-foreground",
        align === "right" && "flex-row-reverse"
      )}
    >
      <SortIcon active={sortKey === key} dir={sortDir} />
      {label}
    </button>
  )

  const ctx: RenderContext = { colorOf }

  return (
    <div className="flex min-w-0 flex-col gap-4">
      {/* Title */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
        <div className="flex flex-col gap-1">
          <h1 className="text-xl font-semibold tracking-tight">Position</h1>
          <p className="text-sm text-muted-foreground">
            Your stocks read with Companies › Fundamentals, in rupiah · computed {formatTimestamp(portfolio.computedAt)}
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button variant="outline" size="sm" onClick={() => setSettingsOpen(true)}>
            <WalletIcon /> Cash &amp; investment
          </Button>
          <Button variant="outline" size="sm" onClick={exportCsv} disabled={rows.length === 0}>
            <DownloadIcon /> Export CSV
          </Button>
          <Button size="sm" onClick={() => setAdding(true)}>
            <PlusIcon /> Add position
          </Button>
        </div>
      </div>

      {/* Metrics */}
      <div className="grid gap-4 lg:grid-cols-2">
        <MetricCard
          title="Portfolio"
          caption="Market total + cash against the capital put in"
          headlineLabel="Total profit"
          headline={s.totalProfit}
          headlinePct={s.totalProfitPct}
          items={[
            { label: "Total cash", value: `Rp ${rp(s.cash)}` },
            { label: "Total investment", value: `Rp ${rp(s.totalInvestment)}` },
            {
              label: "Total profit",
              value: <span className={signTone(s.totalProfit)}>Rp {signedNumber(s.totalProfit, 0)}</span>,
              hint: "market total + cash − total investment",
            },
            {
              label: "Percentage profit",
              value: <span className={signTone(s.totalProfitPct)}>{signedPercent(s.totalProfitPct)}</span>,
              hint: s.totalInvestment > 0 ? "total profit ÷ total investment" : "set the total investment",
            },
          ]}
          action={
            <Button variant="ghost" size="sm" onClick={() => setSettingsOpen(true)}>
              <PencilIcon /> Edit
            </Button>
          }
        />
        <MetricCard
          title="Stocks"
          caption={`${s.positions} ${s.positions === 1 ? "stock" : "stocks"} at the last price against their average price`}
          headlineLabel="Profit market"
          headline={s.profitMarket}
          headlinePct={s.profitMarketPct}
          items={[
            { label: "Average total", value: `Rp ${rp(s.averageTotal)}`, hint: "Σ average price × shares" },
            {
              label: "Market total",
              value: `Rp ${rp(s.marketTotal)}`,
              hint: s.priced < s.positions ? `${s.priced} of ${s.positions} positions priced` : "Σ last price × shares",
            },
            {
              label: "Profit market",
              value: <span className={signTone(s.profitMarket)}>Rp {signedNumber(s.profitMarket, 0)}</span>,
              hint: "market total − average total",
            },
            {
              label: "Percentage profit market",
              value: <span className={signTone(s.profitMarketPct)}>{signedPercent(s.profitMarketPct)}</span>,
              hint: "profit market ÷ average total",
            },
          ]}
        />
      </div>

      {portfolio.notes.length > 0 && (
        <ul role="note" className="space-y-1 rounded-lg border border-amber-600/30 bg-amber-600/5 px-3 py-2 text-xs">
          {portfolio.notes.map((n) => (
            <li key={n} className="flex items-start gap-2 text-amber-800 dark:text-amber-300">
              <CircleAlertIcon className="mt-px size-3.5 shrink-0" />
              <span>{n}</span>
            </li>
          ))}
        </ul>
      )}

      <LiveCard
        live={live}
        dayGain={s.dayGain}
        dayGainPct={s.dayGainPct}
        refreshing={refreshing}
        error={refreshError}
        onRefresh={refreshNow}
      />

      {/* Positions */}
      <Card className="min-w-0 gap-0 py-0">
        <CardHeader className="border-b py-4">
          <CardTitle>Positions</CardTitle>
          <CardDescription>
            {s.positions} {s.positions === 1 ? "stock" : "stocks"} · company figures from the latest filing, forward =
            made a year (Q1 × 4, H1 × 2, 9M × 4/3) · total debt = total liabilities, as in the sheet · hover a header
            for its formula · drag the table or use the scrollbar above it to slide sideways
          </CardDescription>
        </CardHeader>
        <CardContent className="px-0">
          {rows.length === 0 ? (
            <div className="py-6">
              <EmptyState
                variant="generic"
                title="No positions yet"
                description="Add the stocks you hold: average price, shares and the date of the first buy."
                actionLabel="Add position"
                onAction={() => setAdding(true)}
              />
            </div>
          ) : (
            // a scrollbar above the table too, and press-and-drag to slide it; only the table moves
            <DragScroll>
              <table className="w-full border-separate border-spacing-0 text-xs">
                <thead className="text-muted-foreground">
                  {/* group headers */}
                  <tr>
                    <th
                      rowSpan={2}
                      className="sticky left-0 z-20 border-r border-b bg-muted px-4 py-2 text-left align-bottom"
                    >
                      {sortButton("ticker", "Stock", "Ticker", "left")}
                    </th>
                    {GROUPS.map((g) => (
                      <th
                        key={g.label || "date"}
                        colSpan={g.columns.length}
                        className="border-b border-l bg-muted px-3 pt-2 pb-1 text-center text-[11px] font-semibold tracking-wide text-foreground/80 uppercase"
                      >
                        {g.label}
                      </th>
                    ))}
                    <th rowSpan={2} className="border-b border-l bg-muted px-2">
                      <span className="sr-only">Actions</span>
                    </th>
                  </tr>
                  <tr>
                    {COLUMNS.map((c) => (
                      <th
                        key={c.key}
                        scope="col"
                        aria-sort={sortKey === (c.sortBy ?? c.key) ? (sortDir === "asc" ? "ascending" : "descending") : "none"}
                        className={cn(
                          "border-b bg-muted px-3 py-2 font-medium",
                          (c.align ?? "right") === "right" ? "text-right" : "text-left",
                          GROUP_START.has(c.key) && "border-l"
                        )}
                      >
                        {sortButton(c.sortBy ?? c.key, c.label, c.title, c.align ?? "right")}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {rows.map((p) => (
                    <tr key={p.positionId} className="group">
                      <td className="sticky left-0 z-10 border-r border-b bg-card px-4 py-2.5 group-hover:bg-muted">
                        <div className="flex items-center gap-1.5">
                          <Link
                            href={companyHref(p.exchange, p.ticker)}
                            className="font-mono text-sm font-semibold hover:underline"
                          >
                            {p.ticker}
                          </Link>
                          {p.convertedFrom && (
                            <Badge
                              variant="outline"
                              className="h-4 px-1 text-[9px]"
                              title={`Company figures converted from ${p.convertedFrom} into IDR`}
                            >
                              {p.convertedFrom}
                            </Badge>
                          )}
                          {p.notes.length > 0 && (
                            <span title={p.notes.join("\n")} aria-label={p.notes.join(" ")}>
                              <CircleAlertIcon className="size-3.5 text-amber-600" />
                            </span>
                          )}
                        </div>
                        <div className="w-[168px] truncate text-[11px] text-muted-foreground" title={p.companyName}>
                          {p.companyName}
                        </div>
                      </td>
                      {COLUMNS.map((c) => (
                        <td
                          key={c.key}
                          className={cn(
                            "border-b px-3 py-2.5 whitespace-nowrap group-hover:bg-muted/50",
                            (c.align ?? "right") === "right" && "text-right font-mono tabular-nums",
                            GROUP_START.has(c.key) && "border-l",
                            c.signed && signTone(p[c.key] as number | null)
                          )}
                        >
                          {c.render(p, ctx)}
                        </td>
                      ))}
                      <td className="border-b border-l px-2 py-1.5 text-right whitespace-nowrap group-hover:bg-muted/50">
                        <Button variant="ghost" size="icon-sm" onClick={() => setEditing(p)} aria-label={`Edit ${p.ticker}`}>
                          <PencilIcon />
                        </Button>
                        <Button
                          variant="ghost"
                          size="icon-sm"
                          onClick={() => setRemoving(p)}
                          aria-label={`Remove ${p.ticker}`}
                        >
                          <Trash2Icon />
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
                <tfoot className="font-medium">
                  <tr>
                    <td className="sticky left-0 z-10 border-r bg-muted px-4 py-2.5 text-sm">Total</td>
                    {COLUMNS.map((c) => (
                      <td
                        key={c.key}
                        className={cn(
                          "bg-muted/60 px-3 py-2.5 text-right font-mono whitespace-nowrap tabular-nums",
                          GROUP_START.has(c.key) && "border-l"
                        )}
                      >
                        {c.key === "marketValue" ? (
                          rp(s.marketTotal)
                        ) : c.key === "averageValue" ? (
                          rp(s.averageTotal)
                        ) : c.key === "gainLoss" ? (
                          <span className={signTone(s.profitMarket)}>{signedNumber(s.profitMarket, 0)}</span>
                        ) : c.key === "gainLossPct" ? (
                          <span className={signTone(s.profitMarketPct)}>{signedPercent(s.profitMarketPct)}</span>
                        ) : c.key === "portfolioMarketPct" && s.totalValue > 0 ? (
                          pct(s.marketTotal / s.totalValue)
                        ) : c.key === "portfolioAveragePct" && s.averageTotal + s.cash > 0 ? (
                          pct(s.averageTotal / (s.averageTotal + s.cash))
                        ) : null}
                      </td>
                    ))}
                    <td className="border-l bg-muted/60" />
                  </tr>
                </tfoot>
              </table>
            </DragScroll>
          )}
        </CardContent>
      </Card>

      {/* Allocation */}
      <AllocationChart slices={slices} total={s.totalValue} weights={weights} />

      <PositionSheet
        open={adding || editing !== null}
        position={editing}
        onClose={() => {
          setAdding(false)
          setEditing(null)
        }}
        onSaved={setPortfolio}
      />
      <SettingsSheet
        open={settingsOpen}
        cash={s.cash}
        totalInvestment={s.totalInvestment}
        onClose={() => setSettingsOpen(false)}
        onSaved={setPortfolio}
      />
      <RemoveDialog position={removing} onClose={() => setRemoving(null)} onRemoved={setPortfolio} />
    </div>
  )
}

/**
 * The trading day: the IDX phase now, the stocks' gain or loss of the day, when the intraday prices were read and the
 * "Refresh" button. The backend reads them every few minutes from the open to the close of the session.
 */
function LiveCard({
  live,
  dayGain,
  dayGainPct,
  refreshing,
  error,
  onRefresh,
}: {
  live: LiveStatus
  dayGain: number | null
  dayGainPct: number | null
  refreshing: boolean
  error: string | null
  onRefresh: () => void
}) {
  const updated = live.updatedAt ? wibTime(Date.parse(live.updatedAt) / 1000) : null
  const sessionDay = live.sessionDate ? formatDate(live.sessionDate) : null
  return (
    <Card className="gap-0 py-0">
      <CardContent className="flex flex-col gap-3 py-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex flex-wrap items-center gap-x-6 gap-y-2">
          <div className="flex items-center gap-2">
            <span className="relative flex size-2.5">
              {live.trading && (
                <span className="absolute inline-flex size-full animate-ping rounded-full bg-emerald-500 opacity-60" />
              )}
              <span
                className={cn(
                  "relative inline-flex size-2.5 rounded-full",
                  live.trading ? "bg-emerald-500" : live.phase === "CLOSED" ? "bg-muted-foreground/50" : "bg-amber-500"
                )}
              />
            </span>
            <div>
              <p className="text-sm font-medium">IDX · {live.phaseLabel}</p>
              <p className="text-[11px] text-muted-foreground">
                {live.trading ? "Prices are moving" : live.phase === "CLOSED" ? "Market closed" : "No trading right now"}
                {sessionDay && ` · latest session ${sessionDay}`}
              </p>
            </div>
          </div>
          <div>
            <p className="text-[11px] text-muted-foreground">Today&apos;s change (stocks)</p>
            <p className="font-mono text-sm font-semibold tabular-nums">
              <span className={signTone(dayGain)}>
                {dayGain === null ? EMPTY : `Rp ${signedNumber(dayGain, 0)}`}
              </span>
              <span className={cn("ml-2 text-xs", signTone(dayGainPct))}>{signedPercent(dayGainPct)}</span>
            </p>
          </div>
        </div>
        <div className="flex items-center gap-3">
          <p className="text-right text-[11px] text-muted-foreground">
            {updated ? `Intraday prices read at ${updated} WIB` : "No intraday prices read yet"}
            <br />
            {live.scheduled
              ? `Every ${live.everyMinutes ?? 5} minutes from the open to the close of the session`
              : "Automatic refresh is off"}
          </p>
          <Button variant="outline" size="sm" onClick={onRefresh} disabled={refreshing}>
            {refreshing ? <LoaderIcon className="animate-spin" /> : <RefreshCwIcon />}
            Refresh
          </Button>
        </div>
      </CardContent>
      {error && (
        <p role="alert" className="flex items-start gap-2 border-t px-4 py-2 text-xs text-destructive">
          <CircleAlertIcon className="mt-px size-3.5 shrink-0" />
          <span>{error}</span>
        </p>
      )}
    </Card>
  )
}

/** A group of the page's metrics: a headline figure with its percentage and four labelled values. */
function MetricCard({
  title,
  caption,
  headlineLabel,
  headline,
  headlinePct,
  items,
  action,
}: {
  title: string
  caption: string
  headlineLabel: string
  headline: number
  headlinePct: number | null
  items: { label: string; value: React.ReactNode; hint?: string }[]
  action?: React.ReactNode
}) {
  const up = headline >= 0
  const Icon = up ? TrendingUpIcon : TrendingDownIcon
  return (
    <Card className="gap-4">
      <CardHeader>
        <div className="flex items-start justify-between gap-2">
          <div className="space-y-1">
            <CardTitle>{title}</CardTitle>
            <CardDescription>{caption}</CardDescription>
          </div>
          {action}
        </div>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div className="flex flex-wrap items-end gap-x-3 gap-y-1">
          <div>
            <p className="text-xs text-muted-foreground">{headlineLabel}</p>
            <p className={cn("font-mono text-2xl font-semibold tracking-tight tabular-nums", signTone(headline))}>
              Rp {signedNumber(headline, 0)}
            </p>
          </div>
          <span
            className={cn(
              "mb-1 inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-semibold",
              up
                ? "bg-emerald-500/10 text-emerald-700 dark:text-emerald-400"
                : "bg-rose-500/10 text-rose-700 dark:text-rose-400"
            )}
          >
            <Icon className="size-3.5" />
            {signedPercent(headlinePct)}
          </span>
        </div>
        <dl className="grid grid-cols-1 gap-px overflow-hidden rounded-lg border bg-border sm:grid-cols-2">
          {items.map((i) => (
            <div key={i.label} className="flex flex-col gap-0.5 bg-card px-3 py-2.5">
              <dt className="text-xs text-muted-foreground">{i.label}</dt>
              <dd className="font-mono text-sm font-semibold tabular-nums">{i.value}</dd>
              {i.hint && <dd className="text-[11px] text-muted-foreground">{i.hint}</dd>}
            </div>
          ))}
        </dl>
      </CardContent>
    </Card>
  )
}

/** Confirms removing a position (it can be added again). */
function RemoveDialog({
  position,
  onClose,
  onRemoved,
}: {
  position: PortfolioPosition | null
  onClose: () => void
  onRemoved: (portfolio: Portfolio) => void
}) {
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function remove() {
    if (!position || pending) return
    setPending(true)
    setError(null)
    try {
      const { body } = await requestJson<Portfolio>(`/api/portfolio/positions/${position.positionId}`, {
        method: "DELETE",
      })
      onRemoved(body)
      onClose()
    } catch (e) {
      setError(e instanceof RequestError ? e.message : "The position could not be removed.")
    } finally {
      setPending(false)
    }
  }

  return (
    <AlertDialog
      open={position !== null}
      onOpenChange={(next) => {
        if (!next && !pending) {
          setError(null)
          onClose()
        }
      }}
    >
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Remove {position?.ticker}?</AlertDialogTitle>
          <AlertDialogDescription>
            {position?.companyName}: {position ? formatNumber(position.shares, 0) : ""} shares at an average of Rp{" "}
            {position ? formatNumber(position.averagePrice, 2) : ""}. The position is deleted from your portfolio.
          </AlertDialogDescription>
        </AlertDialogHeader>
        {error && (
          <p role="alert" className="flex items-start gap-2 text-xs text-destructive">
            <CircleAlertIcon className="mt-px size-3.5 shrink-0" />
            <span>{error}</span>
          </p>
        )}
        <AlertDialogFooter>
          <Button variant="outline" onClick={onClose} disabled={pending}>
            Cancel
          </Button>
          <Button variant="destructive" onClick={remove} disabled={pending}>
            <Trash2Icon /> Remove
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
