"use client"

import { useEffect, useMemo, useState, useTransition } from "react"
import Link from "next/link"
import { useRouter } from "next/navigation"
import {
  ArrowDownRightIcon,
  ArrowRightLeftIcon,
  ArrowUpRightIcon,
  CalendarDaysIcon,
  ChevronLeftIcon,
  ChevronRightIcon,
  CircleAlertIcon,
  LoaderIcon,
  LogInIcon,
  LogOutIcon,
  RotateCcwIcon,
  SearchIcon,
  UsersIcon,
} from "lucide-react"

import { EmptyState } from "@/components/empty-state"
import { CompanyOwnership } from "@/components/ownership/company-ownership"
import { EntityHoldings } from "@/components/ownership/entity-holdings"
import { MovementBadge, PointsChange, SharesChange, StatusBadge } from "@/components/ownership/ownership-badges"
import { SortIcon } from "@/components/sort-icon"
import { StatTile } from "@/components/stat-tile"
import { pageCount, TablePagination } from "@/components/table-pagination"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { requestJson } from "@/lib/client-api"
import { formatNumber } from "@/lib/format"
import {
  dayLabel,
  DEFAULT_OWNERSHIP_PAGE_SIZE,
  EXCEL_INGESTION_HREF,
  type InvestorStatus,
  type MovementPage,
  type MovementRow,
  OWNERSHIP_HREF,
  type OwnershipHighlights,
  type OwnershipSort,
  OWNERSHIP_PAGE_SIZES,
  ownershipQuery,
  type OwnershipFilters,
  type OwnershipOptions,
  type OwnershipTransaction,
  parseTickers,
  percentLabel,
  sharesLabel,
} from "@/lib/ownership"
import { OWNERSHIP_ONE_PERCENT_HREF } from "@/lib/ownership-one-percent"
import { cn } from "@/lib/utils"

const ALL = "__all__"
/** Lists of the preset "Last 5 lists". */
const RECENT_LISTS = 5

/** At least 3 letters or digits: an investor name the backend searches across companies. */
function searchable(holder: string | null): holder is string {
  return holder !== null && holder.replace(/[^a-z0-9]/gi, "").length >= 3
}

/**
 * Companies > Ownership (>=5%): KSEI's daily lists of the shareholders of more than 5% of every IDX company, in the
 * manner of Companies > Registration: who bought or sold (by the investor's combined shares against the trading day
 * before), who crossed 5% and who fell below it, day by day. The latest day's largest movements come first; with one
 * ticker the company's holders day by day with their accounts; with an investor's name every company it holds.
 */
export function OwnershipPageClient({
  options,
  filters,
  initial,
}: {
  options: OwnershipOptions
  /** the filters of the URL, which the server loaded {@code initial} for */
  filters: OwnershipFilters
  initial: MovementPage
}) {
  const router = useRouter()
  const [navigating, startNavigation] = useTransition()
  const [tickersInput, setTickersInput] = useState(filters.tickers.join(", "))
  const [holder, setHolder] = useState(filters.holder ?? "")
  const [transaction, setTransaction] = useState<OwnershipTransaction>(filters.transaction)
  const [status, setStatus] = useState<string>(filters.status ?? ALL)
  const [from, setFrom] = useState(filters.from ?? "")
  const [to, setTo] = useState(filters.to ?? "")
  const [aliases, setAliases] = useState(filters.aliases)
  const [sort, setSort] = useState<OwnershipSort>(filters.sort)
  const [list, setList] = useState(initial)
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(DEFAULT_OWNERSHIP_PAGE_SIZE)
  const current = ownershipQuery(filters, pageSize, (page - 1) * pageSize).toString()
  /** the query of the rows shown; differs from the chosen page while it loads */
  const [loadedQuery, setLoadedQuery] = useState(() =>
    ownershipQuery(filters, DEFAULT_OWNERSHIP_PAGE_SIZE, 0).toString()
  )
  const [error, setError] = useState<string | null>(null)
  const loading = loadedQuery !== current && !error

  const tickers = useMemo(() => new Map(options.tickers.map((t) => [t.ticker, t])), [options.tickers])
  const unknownTickers = parseTickers(tickersInput).filter((t) => !tickers.has(t))
  const latest = options.latest
  const dates = options.dates

  // another page or page size: read from the backend with the URL's filters
  useEffect(() => {
    if (loadedQuery === current) return
    let cancelled = false
    requestJson<MovementPage>(`/api/ownership?${current}`)
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
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : String(e)))
    return () => {
      cancelled = true
    }
  }, [current, loadedQuery, page, pageSize])

  function apply(next: Partial<OwnershipFilters> = {}) {
    const chosen: OwnershipFilters = {
      tickers: parseTickers(tickersInput),
      holder: holder.trim() || null,
      transaction,
      status: status === ALL ? null : (status as InvestorStatus),
      from: from || null,
      to: to || null,
      aliases,
      sort,
      ...next,
    }
    if (chosen.from && chosen.to && chosen.from > chosen.to) {
      ;[chosen.from, chosen.to] = [chosen.to, chosen.from]
    }
    // the inputs show what is applied
    setTickersInput(chosen.tickers.join(", "))
    setHolder(chosen.holder ?? "")
    setTransaction(chosen.transaction)
    setStatus(chosen.status ?? ALL)
    setFrom(chosen.from ?? "")
    setTo(chosen.to ?? "")
    setAliases(chosen.aliases)
    setSort(chosen.sort)
    const query = ownershipQuery(chosen).toString()
    // stay where the filters and the list are, as Reset (by default the page would go back to its top)
    startNavigation(() => router.push(query ? `${OWNERSHIP_HREF}?${query}` : OWNERSHIP_HREF, { scroll: false }))
  }

  function reset() {
    setTickersInput("")
    setHolder("")
    setTransaction("ALL")
    setStatus(ALL)
    setFrom("")
    setTo("")
    setAliases(false)
    setSort("DATE")
    // stay where the filters and the list are (by default the page would go back to its top)
    startNavigation(() => router.push(OWNERSHIP_HREF, { scroll: false }))
  }

  const transactionItems = options.transactions.map((t) => ({ value: t.code, label: t.label }))
  const sortItems = options.sorts.map((s) => ({ value: s.code, label: s.label }))
  const statusItems = [{ value: ALL, label: "Local and foreign" }, ...options.statuses.map((s) => ({ value: s.code, label: s.label }))]
  const single = filters.tickers.length === 1 ? tickers.get(filters.tickers[0]) ?? null : null

  return (
    <>
      <div className="flex flex-col gap-1">
        <h1 className="text-xl font-semibold tracking-tight">Ownership (&gt;=5%)</h1>
        <p className="text-sm text-muted-foreground">
          The shareholders of more than 5% of every IDX company, day by day, from KSEI&apos;s daily lists by investor
          (Kepemilikan Efek Diatas 5% Berdasarkan SID): who bought or sold, who crossed 5% and who fell below it, and
          through which brokers and custodians they hold. Upload the lists on{" "}
          <Link href={EXCEL_INGESTION_HREF} className="text-primary hover:underline">
            Ingestion &gt; Excel Documents (RAG)
          </Link>{" "}
          with the Ownership (&gt;=5%) type.
        </p>
      </div>

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatTile
          label="Latest list"
          value={latest ? dayLabel(latest.reportDate) : "none yet"}
          icon={CalendarDaysIcon}
          hint={
            dates.length > 0
              ? `${dates.length} ${dates.length === 1 ? "list" : "lists"} stored, ${dayLabel(dates[dates.length - 1])} to ${dayLabel(dates[0])}`
              : "upload a list"
          }
        />
        <StatTile
          label="Investors of more than 5%"
          value={latest ? formatNumber(latest.holders - latest.aliases, 0) : "—"}
          icon={UsersIcon}
          tone="muted"
          hint={latest ? `of ${formatNumber(latest.issuers, 0)} companies, ${formatNumber(latest.accounts, 0)} accounts` : undefined}
        />
        <StatTile
          label="Buys / sells on the latest day"
          value={latest ? `${formatNumber(latest.buys, 0)} / ${formatNumber(latest.sells, 0)}` : "—"}
          icon={latest && latest.sells > latest.buys ? ArrowDownRightIcon : ArrowUpRightIcon}
          tone={latest ? (latest.buys >= latest.sells ? "positive" : "negative") : "muted"}
        />
        <StatTile
          label="Crossed / fell below 5%"
          value={latest ? `${formatNumber(latest.entered, 0)} / ${latest.exited === null ? "?" : formatNumber(latest.exited, 0)}` : "—"}
          icon={ArrowRightLeftIcon}
          tone="primary"
          hint={latest && latest.exited === null ? `the list of ${dayLabel(latest.previousDate)} is not stored` : undefined}
        />
      </div>

      {latest && (
        <DayHighlights
          dates={dates}
          initial={{
            report: latest,
            largestBuys: options.largestBuys,
            largestSells: options.largestSells,
            entered: options.entered,
            exited: options.exited,
          }}
          onTicker={(t) => apply({ tickers: [t] })}
          onHolder={(h) => apply({ holder: h, tickers: [] })}
          onDay={(day) => apply({ from: day, to: day, transaction: "ACTIVITY" })}
        />
      )}

      <Card>
        <CardHeader>
          <CardTitle>Filters</CardTitle>
          <CardDescription>
            Tickers separated by commas (empty: every company); part of an investor&apos;s name (&quot;lo kheng
            hong&quot;, &quot;asabri&quot;); the days of the lists.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <form
            className="flex flex-col gap-3"
            onSubmit={(e) => {
              e.preventDefault()
              apply()
            }}
          >
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <label className="flex flex-col gap-1.5 lg:col-span-2">
                <span className="text-xs font-medium text-muted-foreground">Tickers</span>
                <Input
                  value={tickersInput}
                  onChange={(e) => setTickersInput(e.target.value)}
                  placeholder="e.g. ADRO, BBCA (all)"
                  aria-label="Tickers"
                  maxLength={500}
                  list="ownership-tickers"
                />
                <datalist id="ownership-tickers">
                  {options.tickers.map((t) => (
                    <option key={t.ticker} value={t.ticker}>
                      {t.issuerName}
                    </option>
                  ))}
                </datalist>
              </label>
              <label className="flex flex-col gap-1.5 lg:col-span-2">
                <span className="text-xs font-medium text-muted-foreground">Investor</span>
                <Input
                  value={holder}
                  onChange={(e) => setHolder(e.target.value)}
                  placeholder="e.g. Lo Kheng Hong (all)"
                  aria-label="Investor"
                  maxLength={100}
                />
              </label>
              <div className="flex flex-col gap-1.5">
                <span className="text-xs font-medium text-muted-foreground">Transaction</span>
                <Select
                  items={transactionItems}
                  value={transaction}
                  onValueChange={(v) => v && setTransaction(v as OwnershipTransaction)}
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
              <div className="flex flex-col gap-1.5 lg:col-span-2">
                <span className="text-xs font-medium text-muted-foreground">Order</span>
                <Select items={sortItems} value={sort} onValueChange={(v) => v && setSort(v as OwnershipSort)}>
                  <SelectTrigger aria-label="Order" className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {sortItems.map((s) => (
                      <SelectItem key={s.value} value={s.value}>
                        {s.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="flex flex-col gap-1.5">
                <span className="text-xs font-medium text-muted-foreground">Investor type</span>
                <Select items={statusItems} value={status} onValueChange={(v) => v && setStatus(v as string)}>
                  <SelectTrigger aria-label="Investor type" className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {statusItems.map((s) => (
                      <SelectItem key={s.value} value={s.value}>
                        {s.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
            </div>

            <div className="flex flex-wrap items-end gap-3">
              <label className="flex flex-col gap-1.5">
                <span className="text-xs font-medium text-muted-foreground">From</span>
                <Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} aria-label="From" className="w-40" />
              </label>
              <label className="flex flex-col gap-1.5">
                <span className="text-xs font-medium text-muted-foreground">To</span>
                <Input type="date" value={to} onChange={(e) => setTo(e.target.value)} aria-label="To" className="w-40" />
              </label>
              {dates.length > 0 && (
                <div className="flex flex-wrap gap-1.5">
                  <Button type="button" variant="outline" size="sm" onClick={() => apply({ from: dates[0], to: dates[0] })}>
                    Latest day
                  </Button>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => apply({ from: dates[Math.min(RECENT_LISTS, dates.length) - 1], to: dates[0] })}
                  >
                    Last {Math.min(RECENT_LISTS, dates.length)} lists
                  </Button>
                  <Button type="button" variant="outline" size="sm" onClick={() => apply({ from: null, to: null })}>
                    Every day
                  </Button>
                </div>
              )}
              <label className="flex items-center gap-2 pb-1.5 text-xs text-muted-foreground">
                <input
                  type="checkbox"
                  checked={aliases}
                  onChange={(e) => setAliases(e.target.checked)}
                  className="size-3.5 accent-primary"
                />
                Also the same investor under another name
              </label>
            </div>

            {unknownTickers.length > 0 && options.tickers.length > 0 && (
              <p role="status" className="flex items-start gap-2 text-xs text-amber-600 dark:text-amber-400">
                <CircleAlertIcon className="mt-px size-3.5 shrink-0" />
                <span>No stored list names {unknownTickers.join(", ")}.</span>
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

      {single && (
        <CompanyOwnership
          // a new ticker or new page dates start the table's own range afresh
          key={`${single.ticker}|${filters.from ?? ""}|${filters.to ?? ""}`}
          ticker={single.ticker}
          from={filters.from}
          to={filters.to}
          stored={single.stored}
          onHolder={(name) => apply({ holder: name, tickers: [] })}
        />
      )}

      {searchable(filters.holder) && (
        <EntityHoldings query={filters.holder} onTicker={(t) => apply({ tickers: [t], holder: null })} />
      )}

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            Shareholders of more than 5%, day by day
            {(loading || navigating) && <LoaderIcon className="size-3.5 animate-spin text-muted-foreground" />}
          </CardTitle>
          <CardDescription>
            {options.sorts.find((s) => s.code === filters.sort)?.label ?? "Latest day first"}
            {filters.sort !== "DATE" && " (rows without a change last)"}. Click Change or % owned to order by them.
            The change is the investor&apos;s combined shares minus those of the trading day before;
            an investor that fell below 5% is the one of the day before missing from the day&apos;s list.
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
              title={dates.length === 0 ? "No ownership lists yet" : "No rows for these filters"}
              description={
                dates.length === 0
                  ? "Upload KSEI's daily lists on Ingestion > Excel Documents (RAG) with the Ownership (>=5%) type."
                  : "Change the tickers, the investor, the transaction, the investor type or the days."
              }
            />
          ) : (
            <div className="overflow-x-auto">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead className="pl-4">Date</TableHead>
                    <TableHead>Ticker</TableHead>
                    <TableHead>Investor</TableHead>
                    <TableHead>Transaction</TableHead>
                    <TableHead className="text-right">Shares before</TableHead>
                    <TableHead className="text-right">Shares after</TableHead>
                    <TableHead className="text-right">
                      <SortHeader
                        label="Change"
                        desc="CHANGE_DESC"
                        asc="CHANGE_ASC"
                        current={filters.sort}
                        disabled={navigating}
                        onSort={(s) => apply({ sort: s })}
                      />
                    </TableHead>
                    <TableHead className="hidden text-right md:table-cell">
                      <SortHeader
                        label="% owned"
                        desc="POINTS_DESC"
                        asc="POINTS_ASC"
                        current={filters.sort}
                        disabled={navigating}
                        onSort={(s) => apply({ sort: s })}
                      />
                    </TableHead>
                    <TableHead className="hidden pr-4 text-right lg:table-cell">Accounts</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {list.rows.map((r) => (
                    <MovementTableRow
                      key={`${r.reportId}-${r.holderId}-${r.leftList}`}
                      row={r}
                      onTicker={(t) => apply({ tickers: [t] })}
                      onHolder={(h) => apply({ holder: h })}
                    />
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
                pageSizes={OWNERSHIP_PAGE_SIZES}
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
        Percentages are of the shares listed, as KSEI prints them (two decimals); a percentage can change without a
        trade when the shares listed change. KSEI lists an investor once per name its accounts are registered under:
        the same accounts under a second name are an alias and counted once. The holders of more than 1%, month by
        month and by type of investor, are on{" "}
        <Link href={OWNERSHIP_ONE_PERCENT_HREF} className="text-primary hover:underline">
          Ownership (&gt;=1%)
        </Link>
        .
      </p>
    </>
  )
}

/**
 * A column header that orders the table by it: the first click largest first (DESC), the next smallest first (ASC),
 * the third back to the latest day first.
 */
function SortHeader({
  label,
  desc,
  asc,
  current,
  disabled,
  onSort,
}: {
  label: string
  desc: OwnershipSort
  asc: OwnershipSort
  current: OwnershipSort
  disabled: boolean
  onSort: (sort: OwnershipSort) => void
}) {
  const active = current === desc || current === asc
  const next: OwnershipSort = current === desc ? asc : current === asc ? "DATE" : desc
  return (
    <button
      type="button"
      onClick={() => onSort(next)}
      disabled={disabled}
      className="inline-flex items-center font-medium hover:text-foreground disabled:opacity-60"
      title={
        next === "DATE"
          ? "Order by the latest day again"
          : `Order by ${label.toLowerCase()}, ${next === desc ? "largest first" : "smallest first"}`
      }
    >
      {label}
      <SortIcon active={active} dir={current === asc ? "asc" : "desc"} />
    </button>
  )
}

/**
 * The largest movements of one day: the latest list's at first, another stored day's once chosen (the days come from
 * the stored lists). Its counts, the largest buys and sells, and who crossed 5% or fell below it.
 */
function DayHighlights({
  dates,
  initial,
  onTicker,
  onHolder,
  onDay,
}: {
  /** the stored days, latest first */
  dates: string[]
  /** the latest day's, loaded by the server */
  initial: OwnershipHighlights
  onTicker: (ticker: string) => void
  onHolder: (name: string) => void
  /** list every movement of the day in the table */
  onDay: (day: string) => void
}) {
  const [day, setDay] = useState(initial.report.reportDate)
  const [data, setData] = useState(initial)
  const [error, setError] = useState<string | null>(null)
  const loading = data.report.reportDate !== day && !error
  const index = dates.indexOf(day)

  useEffect(() => {
    if (data.report.reportDate === day) return
    let cancelled = false
    requestJson<OwnershipHighlights>(`/api/ownership/highlights?${new URLSearchParams({ date: day })}`)
      .then(({ body }) => {
        if (cancelled) return
        setData(body)
        setError(null)
      })
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : String(e)))
    return () => {
      cancelled = true
    }
  }, [day, data.report.reportDate])

  function choose(next: string) {
    setError(null)
    setDay(next)
  }

  const report = data.report
  const dayItems = dates.map((d) => ({ value: d, label: dayLabel(d) }))
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex flex-wrap items-center gap-2">
          Highlights of {dayLabel(day)}
          {loading && <LoaderIcon className="size-3.5 animate-spin text-muted-foreground" />}
        </CardTitle>
        <CardDescription>
          The largest buys and sells of the day by percentage points, and the investors that crossed 5% or fell below
          it, against the trading day before. Choose another day of the stored lists. Click a ticker for its
          shareholders, a name for every company the investor holds.
        </CardDescription>
        <div className="flex flex-wrap items-center gap-2 pt-1">
          <Button
            type="button"
            variant="outline"
            size="icon-sm"
            aria-label="The day before"
            title="The day before"
            disabled={index < 0 || index >= dates.length - 1}
            onClick={() => choose(dates[index + 1])}
          >
            <ChevronLeftIcon />
          </Button>
          <Select items={dayItems} value={day} onValueChange={(v) => v && choose(v as string)}>
            <SelectTrigger aria-label="Day of the highlights" className="w-44">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {dayItems.map((d) => (
                <SelectItem key={d.value} value={d.value}>
                  {d.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <Button
            type="button"
            variant="outline"
            size="icon-sm"
            aria-label="The day after"
            title="The day after"
            disabled={index <= 0}
            onClick={() => choose(dates[index - 1])}
          >
            <ChevronRightIcon />
          </Button>
          {index > 0 && (
            <Button type="button" variant="ghost" size="sm" onClick={() => choose(dates[0])}>
              Latest
            </Button>
          )}
          <Button type="button" variant="outline" size="sm" onClick={() => onDay(day)} className="ml-auto">
            Every movement of {dayLabel(day)}
          </Button>
        </div>
      </CardHeader>
      <CardContent className={cn("flex flex-col gap-4 transition-opacity", loading && "opacity-60")}>
        {error && (
          <p role="alert" className="flex items-start gap-2 text-xs text-destructive">
            <CircleAlertIcon className="mt-px size-3.5 shrink-0" />
            <span>{error}</span>
          </p>
        )}
        <p className="text-xs text-muted-foreground">
          {dayLabel(report.reportDate)} against {dayLabel(report.previousDate)}:{" "}
          {formatNumber(report.holders - report.aliases, 0)} investors of {formatNumber(report.issuers, 0)} companies ·{" "}
          {formatNumber(report.buys, 0)} buys · {formatNumber(report.sells, 0)} sells · {formatNumber(report.entered, 0)}{" "}
          crossed 5% · {report.exited === null ? "fell below 5% unknown" : `${formatNumber(report.exited, 0)} fell below 5%`}
        </p>
        <div className="grid gap-4 lg:grid-cols-2">
          <Highlights
            title="Largest buys"
            icon={ArrowUpRightIcon}
            tone="text-emerald-600 dark:text-emerald-400"
            rows={data.largestBuys}
            onTicker={onTicker}
            onHolder={onHolder}
          />
          <Highlights
            title="Largest sells"
            icon={ArrowDownRightIcon}
            tone="text-rose-600 dark:text-rose-400"
            rows={data.largestSells}
            onTicker={onTicker}
            onHolder={onHolder}
          />
          <Highlights
            title="Crossed above 5%"
            icon={LogInIcon}
            tone="text-sky-600 dark:text-sky-400"
            rows={data.entered}
            onTicker={onTicker}
            onHolder={onHolder}
          />
          <Highlights
            title="Fell below 5%"
            icon={LogOutIcon}
            tone="text-amber-600 dark:text-amber-400"
            rows={data.exited}
            empty={report.exited === null ? `Unknown: the list of ${dayLabel(report.previousDate)} is not stored.` : undefined}
            onTicker={onTicker}
            onHolder={onHolder}
          />
        </div>
      </CardContent>
    </Card>
  )
}

function MovementTableRow({
  row: r,
  onTicker,
  onHolder,
}: {
  row: MovementRow
  onTicker: (ticker: string) => void
  onHolder: (name: string) => void
}) {
  return (
    <TableRow>
      <TableCell className="pl-4 whitespace-nowrap tabular-nums">{dayLabel(r.reportDate)}</TableCell>
      <TableCell>
        <button type="button" onClick={() => onTicker(r.ticker)} className="font-mono font-medium hover:underline">
          {r.ticker}
        </button>
        <div className="max-w-[160px] truncate text-xs text-muted-foreground">{r.issuerName}</div>
      </TableCell>
      <TableCell>
        <button type="button" onClick={() => onHolder(r.holderName)} className="text-left font-medium hover:underline">
          {r.holderName}
        </button>
        <div className="flex flex-wrap items-center gap-1">
          <StatusBadge status={r.status} />
          {r.domicile && <span className="text-xs text-muted-foreground">{r.domicile}</span>}
          {r.aliasOf !== null && <Badge variant="secondary">Alias of No. {r.aliasOf}</Badge>}
          {r.movement === "UNCHANGED" && r.accountMoves > 0 && (
            <Badge variant="outline" title="Shares moved between its accounts; its total did not change">
              <ArrowRightLeftIcon />
              Moved between accounts
            </Badge>
          )}
        </div>
        {r.aliases.length > 0 && (
          <div className="max-w-[260px] truncate text-xs text-muted-foreground" title={r.aliases.join("; ")}>
            Also listed as {r.aliases.join("; ")}
          </div>
        )}
      </TableCell>
      <TableCell>
        <MovementBadge movement={r.movement} />
      </TableCell>
      <TableCell className="text-right tabular-nums">{sharesLabel(r.sharesPrevious)}</TableCell>
      <TableCell className="text-right tabular-nums">{sharesLabel(r.sharesCurrent)}</TableCell>
      <TableCell className="text-right tabular-nums">
        <SharesChange value={r.change} />
      </TableCell>
      <TableCell className="hidden text-right whitespace-nowrap tabular-nums md:table-cell">
        <div>
          {percentLabel(r.percentPrevious)} → {percentLabel(r.percentCurrent)}
        </div>
        {r.percentChange !== null && (
          <div className="text-xs">
            <PointsChange value={r.percentChange} />
          </div>
        )}
      </TableCell>
      <TableCell className="hidden pr-4 text-right tabular-nums lg:table-cell">
        {formatNumber(r.accounts, 0)}
        {r.accountMoves > 0 && <div className="text-xs text-muted-foreground">{r.accountMoves} changed</div>}
      </TableCell>
    </TableRow>
  )
}

/** One kind of the latest day's movements: ticker, investor, change. */
function Highlights({
  title,
  icon: Icon,
  tone,
  rows,
  empty,
  onTicker,
  onHolder,
}: {
  title: string
  icon: typeof LogInIcon
  tone: string
  rows: MovementRow[]
  empty?: string
  onTicker: (ticker: string) => void
  onHolder: (name: string) => void
}) {
  return (
    <div className="flex flex-col gap-2">
      <p className={cn("flex items-center gap-1.5 text-sm font-medium", tone)}>
        <Icon className="size-4" /> {title}
        <span className="text-xs font-normal text-muted-foreground">({rows.length})</span>
      </p>
      {rows.length === 0 ? (
        <p className="text-xs text-muted-foreground">{empty ?? "None on this day."}</p>
      ) : (
        <ul className="flex max-h-72 flex-col divide-y overflow-y-auto rounded-lg border">
          {rows.map((r) => (
            <li key={`${r.holderId}-${r.leftList}`} className="flex items-center gap-2 px-3 py-1.5 text-sm">
              <button type="button" onClick={() => onTicker(r.ticker)} className="w-14 shrink-0 text-left font-mono font-medium hover:underline">
                {r.ticker}
              </button>
              <button
                type="button"
                onClick={() => onHolder(r.holderName)}
                className="min-w-0 flex-1 truncate text-left hover:underline"
                title={r.holderName}
              >
                {r.holderName}
              </button>
              <span className="shrink-0 text-right text-xs tabular-nums">
                {r.movement === "ENTERED" ? (
                  <>
                    {percentLabel(r.percentCurrent)}
                    <div className="text-muted-foreground">{sharesLabel(r.sharesCurrent)}</div>
                  </>
                ) : r.movement === "EXITED" ? (
                  <>
                    was {percentLabel(r.percentPrevious)}
                    <div className="text-muted-foreground">{sharesLabel(r.sharesPrevious)}</div>
                  </>
                ) : (
                  <>
                    <PointsChange value={r.percentChange} />
                    <div>
                      <SharesChange value={r.change} />
                    </div>
                  </>
                )}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
