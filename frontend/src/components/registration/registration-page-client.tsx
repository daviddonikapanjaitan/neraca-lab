"use client"

import { useEffect, useMemo, useState, useTransition } from "react"
import Link from "next/link"
import { useRouter } from "next/navigation"
import {
  ArrowDownRightIcon,
  ArrowUpRightIcon,
  CircleAlertIcon,
  FileTextIcon,
  LoaderIcon,
  RotateCcwIcon,
  SearchIcon,
  UsersIcon,
} from "lucide-react"

import { EmptyState } from "@/components/empty-state"
import { MonthRangePicker } from "@/components/registration/month-range-picker"
import { StatTile } from "@/components/stat-tile"
import { pageCount, TablePagination } from "@/components/table-pagination"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { requestJson } from "@/lib/client-api"
import { EMPTY, formatNumber } from "@/lib/format"
import { companyHref } from "@/lib/links"
import {
  DEFAULT_REGISTRATION_PAGE_SIZE,
  type InsiderPage,
  type InsiderRole,
  type InsiderRow,
  monthLabel,
  parseTickers,
  REGISTRATION_HREF,
  REGISTRATION_PAGE_SIZES,
  registrationQuery,
  type RegistrationFilters,
  type RegistrationOptions,
  type RegistrationTransaction,
  ROLE_LABEL,
} from "@/lib/registration"
import { cn } from "@/lib/utils"

const ALL = "__all__"

/** Rows read for the holdings by month of one company (every month of every insider). */
const HISTORY_LIMIT = 500

function shares(value: number | null): string {
  return formatNumber(value, 0)
}

function percent(value: number | null): string {
  return value === null ? EMPTY : `${formatNumber(value, 4)}%`
}

/** "+2,500,000" / "-1,000,000" / "0", coloured: bought green, sold red. */
function Change({ value }: { value: number | null }) {
  if (value === null) return <span className="text-muted-foreground">{EMPTY}</span>
  if (value === 0) return <span className="text-muted-foreground">0</span>
  const bought = value > 0
  return (
    <span
      className={cn(
        "inline-flex items-center gap-0.5 font-medium",
        bought ? "text-emerald-600 dark:text-emerald-400" : "text-rose-600 dark:text-rose-400"
      )}
    >
      {bought ? <ArrowUpRightIcon className="size-3.5" /> : <ArrowDownRightIcon className="size-3.5" />}
      {bought ? "+" : ""}
      {formatNumber(value, 0)}
    </span>
  )
}

function TransactionBadge({ value }: { value: number | null }) {
  if (value === null) return <Badge variant="outline">New</Badge>
  if (value > 0) return <Badge className="bg-emerald-500/15 text-emerald-700 dark:text-emerald-300">Buy</Badge>
  if (value < 0) return <Badge className="bg-rose-500/15 text-rose-700 dark:text-rose-300">Sell</Badge>
  return <Badge variant="outline">No change</Badge>
}

/**
 * Companies > Registration, in the manner of openinsider.com: the shares of the directors and commissioners of the
 * companies at the end of each month and what they bought or sold during it, from the monthly registration reports
 * of securities holders. Filters: tickers, buy / sell, title (director or commissioner), months. With one ticker, the
 * holdings of each insider month by month.
 */
export function RegistrationPageClient({
  options,
  filters,
  initial,
  currentMonth,
}: {
  options: RegistrationOptions
  /** this month in Jakarta ("2026-10"): the latest month the calendar offers */
  currentMonth: string
  /** the filters of the URL, which the server loaded {@code initial} for */
  filters: RegistrationFilters
  initial: InsiderPage
}) {
  const router = useRouter()
  const [navigating, startNavigation] = useTransition()
  const [tickersInput, setTickersInput] = useState(filters.tickers.join(", "))
  const [transaction, setTransaction] = useState<RegistrationTransaction>(filters.transaction)
  const [role, setRole] = useState<string>(filters.role ?? ALL)
  const [from, setFrom] = useState<string | null>(filters.from)
  const [to, setTo] = useState<string | null>(filters.to)
  const [list, setList] = useState(initial)
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(DEFAULT_REGISTRATION_PAGE_SIZE)
  const current = registrationQuery(filters, pageSize, (page - 1) * pageSize).toString()
  /** the query of the rows shown; differs from the chosen page while it loads */
  const [loadedQuery, setLoadedQuery] = useState(() =>
    registrationQuery(filters, DEFAULT_REGISTRATION_PAGE_SIZE, 0).toString()
  )
  const [error, setError] = useState<string | null>(null)
  const loading = loadedQuery !== current && !error

  const reportsFrom = options.companies.reduce<string | null>(
    (min, c) => (min === null || c.firstPeriod < min ? c.firstPeriod : min),
    null
  )
  const reportsTo = options.companies.reduce<string | null>(
    (max, c) => (max === null || c.lastPeriod > max ? c.lastPeriod : max),
    null
  )
  const known = useMemo(() => new Set(options.companies.map((c) => c.ticker)), [options.companies])
  const typed = parseTickers(tickersInput)
  const unknownTickers = typed.filter((t) => !known.has(t))

  // another page or page size: read from the backend with the URL's filters
  useEffect(() => {
    if (loadedQuery === current) return
    let cancelled = false
    requestJson<InsiderPage>(`/api/registrations?${current}`)
      .then(({ body }) => {
        if (cancelled) return
        if (page > pageCount(body.total, pageSize)) {
          setPage(pageCount(body.total, pageSize))
          return
        }
        setList(body)
        setLoadedQuery(current)
        setError(null)
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e))
      })
    return () => {
      cancelled = true
    }
  }, [current, loadedQuery, page, pageSize])

  function apply(next: Partial<RegistrationFilters> = {}) {
    const chosen: RegistrationFilters = {
      tickers: parseTickers(tickersInput),
      transaction,
      role: role === ALL ? null : (role as InsiderRole),
      from,
      to,
      ...next,
    }
    const query = registrationQuery(chosen).toString()
    startNavigation(() => router.push(query ? `${REGISTRATION_HREF}?${query}` : REGISTRATION_HREF))
  }

  function reset() {
    setTickersInput("")
    setTransaction("ALL")
    setRole(ALL)
    setFrom(null)
    setTo(null)
    startNavigation(() => router.push(REGISTRATION_HREF))
  }

  const bought = list.rows.filter((r) => (r.change ?? 0) > 0).length
  const sold = list.rows.filter((r) => (r.change ?? 0) < 0).length
  const transactionItems = options.transactions.map((t) => ({ value: t.code, label: t.label }))
  const roleItems = [{ value: ALL, label: "All titles" }, ...options.roles.map((r) => ({ value: r.code, label: r.label }))]

  return (
    <>
      <div className="flex flex-col gap-1">
        <h1 className="text-xl font-semibold tracking-tight">Registration</h1>
        <p className="text-sm text-muted-foreground">
          Shares of the directors and commissioners at the end of each month, and what they bought or sold during it,
          from the monthly registration reports of securities holders (Laporan Bulanan Registrasi Pemegang Efek).
          Upload a report on{" "}
          <Link href="/ingestion/rag-pdf" className="text-primary hover:underline">
            Ingestion &gt; PDF Documents (RAG)
          </Link>{" "}
          with the Registration type.
        </p>
      </div>

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatTile
          label="Companies with reports"
          value={formatNumber(options.companies.length, 0)}
          icon={FileTextIcon}
          hint={options.companies.map((c) => c.ticker).join(", ") || "none yet"}
        />
        <StatTile label="Rows matching" value={formatNumber(list.total, 0)} icon={UsersIcon} tone="muted" />
        <StatTile label="Buys on this page" value={formatNumber(bought, 0)} icon={ArrowUpRightIcon} tone="positive" />
        <StatTile label="Sells on this page" value={formatNumber(sold, 0)} icon={ArrowDownRightIcon} tone="negative" />
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Filters</CardTitle>
          <CardDescription>Tickers separated by commas; leave empty for every company.</CardDescription>
        </CardHeader>
        <CardContent>
          <form
            className="flex flex-col gap-3"
            onSubmit={(e) => {
              e.preventDefault()
              apply()
            }}
          >
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
              <label className="flex flex-col gap-1.5 lg:col-span-2">
                <span className="text-xs font-medium text-muted-foreground">Tickers</span>
                <Input
                  value={tickersInput}
                  onChange={(e) => setTickersInput(e.target.value)}
                  placeholder="e.g. HRTA, BBRI (all)"
                  aria-label="Tickers"
                  maxLength={500}
                />
              </label>
              <div className="flex flex-col gap-1.5">
                <span className="text-xs font-medium text-muted-foreground">Transaction</span>
                <Select
                  items={transactionItems}
                  value={transaction}
                  onValueChange={(v) => v && setTransaction(v as RegistrationTransaction)}
                >
                  <SelectTrigger aria-label="Transaction" className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {transactionItems.map((t) => (
                      <SelectItem key={t.value} value={t.value}>
                        {t.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="flex flex-col gap-1.5">
                <span className="text-xs font-medium text-muted-foreground">Title</span>
                <Select items={roleItems} value={role} onValueChange={(v) => v && setRole(v)}>
                  <SelectTrigger aria-label="Title" className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {roleItems.map((r) => (
                      <SelectItem key={r.value} value={r.value}>
                        {r.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="flex flex-col gap-1.5">
                <span className="text-xs font-medium text-muted-foreground">Months</span>
                <MonthRangePicker
                  from={from}
                  to={to}
                  onChange={(first, last) => {
                    setFrom(first)
                    setTo(last)
                  }}
                  latest={currentMonth}
                  reportsFrom={reportsFrom}
                  reportsTo={reportsTo}
                  disabled={navigating}
                />
              </div>
            </div>

            {options.companies.length > 0 && (
              <div className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
                <span>With reports:</span>
                {options.companies.map((c) => (
                  <button
                    key={`${c.exchange}:${c.ticker}`}
                    type="button"
                    title={`${c.companyName}: ${c.reports} reports, ${monthLabel(c.firstPeriod)} to ${monthLabel(c.lastPeriod)}`}
                    onClick={() => {
                      setTickersInput(c.ticker)
                      apply({ tickers: [c.ticker] })
                    }}
                    className="rounded-md border px-1.5 py-0.5 font-mono text-foreground hover:bg-muted"
                  >
                    {c.ticker}
                  </button>
                ))}
              </div>
            )}

            {unknownTickers.length > 0 && (
              <p role="status" className="flex items-start gap-2 text-xs text-amber-600 dark:text-amber-400">
                <CircleAlertIcon className="mt-px size-3.5 shrink-0" />
                <span>No registration reports stored for {unknownTickers.join(", ")}.</span>
              </p>
            )}

            <div className="flex gap-2">
              <Button type="submit" disabled={navigating}>
                {navigating ? <LoaderIcon className="animate-spin" /> : <SearchIcon />}
                Apply
              </Button>
              <Button type="button" variant="outline" onClick={reset} disabled={navigating}>
                <RotateCcwIcon />
                Reset
              </Button>
            </div>
          </form>
        </CardContent>
      </Card>

      {filters.tickers.length === 1 && known.has(filters.tickers[0]) && (
        <HoldingsByMonth ticker={filters.tickers[0]} filters={filters} />
      )}

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            Directors&apos; and commissioners&apos; shares
            {(loading || navigating) && <LoaderIcon className="size-3.5 animate-spin text-muted-foreground" />}
          </CardTitle>
          <CardDescription>
            Latest month first. The change is the shares at the end of the month minus those at the end of the month
            before, as the report prints them.
          </CardDescription>
        </CardHeader>
        <CardContent className={cn("flex flex-col gap-3 px-0 transition-opacity", (loading || navigating) && "opacity-60")}>
          {error && (
            <p role="alert" className="flex items-start gap-2 px-4 text-xs text-destructive">
              <CircleAlertIcon className="mt-px size-3.5 shrink-0" />
              <span>{error}</span>
            </p>
          )}
          {list.rows.length === 0 ? (
            <EmptyState
              variant="generic"
              title={options.companies.length === 0 ? "No registration reports yet" : "No rows for these filters"}
              description={
                options.companies.length === 0
                  ? "Upload a monthly registration report on Ingestion > PDF Documents (RAG) with the Registration type."
                  : "Change the tickers, the transaction, the title or the months."
              }
            />
          ) : (
            <div className="overflow-x-auto">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead className="pl-4">Month</TableHead>
                    <TableHead>Ticker</TableHead>
                    <TableHead>Insider</TableHead>
                    <TableHead>Title</TableHead>
                    <TableHead>Transaction</TableHead>
                    <TableHead className="text-right">Shares before</TableHead>
                    <TableHead className="text-right">Shares after</TableHead>
                    <TableHead className="text-right">Change</TableHead>
                    <TableHead className="hidden text-right md:table-cell">% owned</TableHead>
                    <TableHead className="hidden pr-4 lg:table-cell">Report</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {list.rows.map((r) => (
                    <InsiderTableRow key={r.insiderId} row={r} />
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
          {list.total > 0 && (
            <div className="px-4">
              <TablePagination
                page={page}
                pageSize={pageSize}
                pageSizes={REGISTRATION_PAGE_SIZES}
                total={list.total}
                noun="rows"
                disabled={loading}
                onPageChange={setPage}
                onPageSizeChange={(size) => {
                  setPageSize(size)
                  setPage(1)
                }}
              />
            </div>
          )}
        </CardContent>
      </Card>

      <p className="text-xs text-muted-foreground">
        Shareholders of 5% or more and holders of more than 1% with their broker are not shown yet: they need further
        planning.
      </p>
    </>
  )
}

function InsiderTableRow({ row: r }: { row: InsiderRow }) {
  return (
    <TableRow>
      <TableCell className="pl-4 whitespace-nowrap tabular-nums">{monthLabel(r.period)}</TableCell>
      <TableCell>
        <Link href={companyHref(r.exchange, r.ticker)} className="font-mono font-medium hover:underline">
          {r.ticker}
        </Link>
        <div className="max-w-[160px] truncate text-xs text-muted-foreground">{r.companyName}</div>
      </TableCell>
      <TableCell>
        <div className="font-medium">{r.holderName}</div>
        <div className="flex flex-wrap gap-1">
          {r.controller && <Badge variant="secondary">Controller</Badge>}
          {r.controllerAffiliate && <Badge variant="secondary">Affiliate of the controller</Badge>}
        </div>
      </TableCell>
      <TableCell>
        <div>{ROLE_LABEL[r.role]}</div>
        {r.position && r.position.toLowerCase() !== ROLE_LABEL[r.role].toLowerCase() && (
          <div className="text-xs text-muted-foreground">{r.position}</div>
        )}
      </TableCell>
      <TableCell>
        <TransactionBadge value={r.change} />
      </TableCell>
      <TableCell className="text-right tabular-nums">{shares(r.sharesPrevious)}</TableCell>
      <TableCell className="text-right tabular-nums">{shares(r.sharesCurrent)}</TableCell>
      <TableCell className="text-right tabular-nums">
        <Change value={r.change} />
      </TableCell>
      <TableCell className="hidden text-right whitespace-nowrap tabular-nums md:table-cell">
        {percent(r.percentPrevious)} → {percent(r.percentCurrent)}
      </TableCell>
      <TableCell className="hidden pr-4 text-xs text-muted-foreground lg:table-cell">
        <div className="max-w-[220px] truncate" title={r.letterNumber ?? undefined}>
          {r.letterNumber ?? r.fileName ?? EMPTY}
        </div>
        {r.correction && <Badge variant="outline">Correction</Badge>}
      </TableCell>
    </TableRow>
  )
}

/**
 * The holdings of each director and commissioner of one company month by month (the month's shares and their change),
 * with the title and month filters of the page.
 */
function HoldingsByMonth({ ticker, filters }: { ticker: string; filters: RegistrationFilters }) {
  const [rows, setRows] = useState<InsiderRow[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    const query = registrationQuery({ ...filters, tickers: [ticker], transaction: "ALL" }, HISTORY_LIMIT, 0)
    requestJson<InsiderPage>(`/api/registrations?${query}`)
      .then(({ body }) => {
        if (cancelled) return
        setRows(body.rows)
        setError(body.total > body.rows.length ? `Only the latest ${body.rows.length} of ${body.total} rows are shown.` : null)
      })
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : String(e)))
    return () => {
      cancelled = true
    }
  }, [ticker, filters])

  const table = useMemo(() => {
    if (!rows) return null
    const months = [...new Set(rows.map((r) => r.period))].sort()
    const holders = new Map<string, { name: string; role: InsiderRole; byMonth: Map<string, InsiderRow> }>()
    // the latest report's order first (rows come latest month first)
    for (const r of rows) {
      const key = r.holderName.toUpperCase()
      if (!holders.has(key)) holders.set(key, { name: r.holderName, role: r.role, byMonth: new Map() })
      holders.get(key)!.byMonth.set(r.period, r)
    }
    return { months, holders: [...holders.values()] }
  }, [rows])

  return (
    <Card>
      <CardHeader>
        <CardTitle>{ticker}: holdings by month</CardTitle>
        <CardDescription>
          Shares of each director and commissioner at the end of each month, with the change during the month.
        </CardDescription>
        {table && (
          <CardAction className="hidden sm:block">
            <Badge variant="outline">
              {table.holders.length} insiders · {table.months.length} months
            </Badge>
          </CardAction>
        )}
      </CardHeader>
      <CardContent className="px-0">
        {error && (
          <p role="alert" className="flex items-start gap-2 px-4 pb-2 text-xs text-destructive">
            <CircleAlertIcon className="mt-px size-3.5 shrink-0" />
            <span>{error}</span>
          </p>
        )}
        {!table ? (
          !error && (
            <p className="flex items-center gap-2 px-4 text-sm text-muted-foreground">
              <LoaderIcon className="size-3.5 animate-spin" /> Loading the months
            </p>
          )
        ) : table.holders.length === 0 ? (
          <p className="px-4 text-sm text-muted-foreground">No holdings for these filters.</p>
        ) : (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="pl-4">Insider</TableHead>
                  {table.months.map((m) => (
                    <TableHead key={m} className="text-right whitespace-nowrap last:pr-4">
                      {monthLabel(m)}
                    </TableHead>
                  ))}
                </TableRow>
              </TableHeader>
              <TableBody>
                {table.holders.map((h) => (
                  <TableRow key={h.name}>
                    <TableCell className="pl-4">
                      <div className="font-medium">{h.name}</div>
                      <div className="text-xs text-muted-foreground">{ROLE_LABEL[h.role]}</div>
                    </TableCell>
                    {table.months.map((m) => {
                      const r = h.byMonth.get(m)
                      return (
                        <TableCell key={m} className="text-right tabular-nums last:pr-4">
                          {r ? (
                            <>
                              <div>{shares(r.sharesCurrent)}</div>
                              <div className="text-xs">
                                <Change value={r.change} />
                              </div>
                            </>
                          ) : (
                            <span className="text-muted-foreground">{EMPTY}</span>
                          )}
                        </TableCell>
                      )
                    })}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </CardContent>
    </Card>
  )
}
