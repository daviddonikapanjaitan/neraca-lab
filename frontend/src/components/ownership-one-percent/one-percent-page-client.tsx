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
  NetworkIcon,
  RotateCcwIcon,
  SearchIcon,
  UsersIcon,
} from "lucide-react"

import { EmptyState } from "@/components/empty-state"
import { CompanyOnePercent } from "@/components/ownership-one-percent/company-one-percent"
import { EntityOnePercent } from "@/components/ownership-one-percent/entity-one-percent"
import { MatchBadge, OnePercentMovementBadge, TypeBadge } from "@/components/ownership-one-percent/one-percent-badges"
import { PointsChange, SharesChange, StatusBadge } from "@/components/ownership/ownership-badges"
import { SortIcon } from "@/components/sort-icon"
import { StatTile } from "@/components/stat-tile"
import { pageCount, TablePagination } from "@/components/table-pagination"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { requestJson } from "@/lib/client-api"
import { formatNumber } from "@/lib/format"
import {
  DEFAULT_OWNERSHIP_PAGE_SIZE,
  EXCEL_INGESTION_HREF,
  type InvestorStatus,
  OWNERSHIP_HREF,
  OWNERSHIP_PAGE_SIZES,
  type OwnershipSort,
  parseTickers,
  percentLabel,
  sharesLabel,
} from "@/lib/ownership"
import {
  monthLabel,
  type OnePercentFilters,
  type OnePercentHighlights,
  type OnePercentOptions,
  type OnePercentPage,
  onePercentQuery,
  type OnePercentRow,
  type OnePercentTransaction,
  OWNERSHIP_ONE_PERCENT_HREF,
  type TypeGroup,
} from "@/lib/ownership-one-percent"
import { cn } from "@/lib/utils"

const ALL = "__all__"
/** Months of the preset "Last 3 months". */
const RECENT_MONTHS = 3

/** At least 3 letters or digits: an investor name the backend searches across companies. */
function searchable(holder: string | null): holder is string {
  return holder !== null && holder.replace(/[^a-z0-9]/gi, "").length >= 3
}

/**
 * Companies > Ownership (>=1%): KSEI's monthly lists of the shareholders of more than 1% of every IDX company, in the
 * manner of Companies > Registration and Ownership (>=5%): who bought or sold (each investor's shares at the month end
 * against the stored month before), who is new above 1% and who is no longer, which names changed, by type of
 * investor. A month's highlights come first (the latest at first, any stored month on choice); with one ticker the
 * company's holders month by month; with an investor's name every company it holds.
 */
export function OnePercentPageClient({
  options,
  filters,
  initial,
}: {
  options: OnePercentOptions
  /** the filters of the URL, which the server loaded {@code initial} for */
  filters: OnePercentFilters
  initial: OnePercentPage
}) {
  const router = useRouter()
  const [navigating, startNavigation] = useTransition()
  const [tickersInput, setTickersInput] = useState(filters.tickers.join(", "))
  const [holder, setHolder] = useState(filters.holder ?? "")
  const [transaction, setTransaction] = useState<OnePercentTransaction>(filters.transaction)
  const [status, setStatus] = useState<string>(filters.status ?? ALL)
  const [group, setGroup] = useState<string>(filters.group ?? ALL)
  const [from, setFrom] = useState(filters.from ?? "")
  const [to, setTo] = useState(filters.to ?? "")
  const [sort, setSort] = useState<OwnershipSort>(filters.sort)
  const [list, setList] = useState(initial)
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(DEFAULT_OWNERSHIP_PAGE_SIZE)
  const current = onePercentQuery(filters, pageSize, (page - 1) * pageSize).toString()
  /** the query of the rows shown; differs from the chosen page while it loads */
  const [loadedQuery, setLoadedQuery] = useState(() =>
    onePercentQuery(filters, DEFAULT_OWNERSHIP_PAGE_SIZE, 0).toString()
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
    requestJson<OnePercentPage>(`/api/ownership/one-percent?${current}`)
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

  function apply(next: Partial<OnePercentFilters> = {}) {
    const chosen: OnePercentFilters = {
      tickers: parseTickers(tickersInput),
      holder: holder.trim() || null,
      transaction,
      status: status === ALL ? null : (status as InvestorStatus),
      group: group === ALL ? null : (group as TypeGroup),
      from: from || null,
      to: to || null,
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
    setGroup(chosen.group ?? ALL)
    setFrom(chosen.from ?? "")
    setTo(chosen.to ?? "")
    setSort(chosen.sort)
    const query = onePercentQuery(chosen).toString()
    // stay where the filters and the list are (by default the page would go back to its top)
    startNavigation(() =>
      router.push(query ? `${OWNERSHIP_ONE_PERCENT_HREF}?${query}` : OWNERSHIP_ONE_PERCENT_HREF, { scroll: false })
    )
  }

  function reset() {
    setTickersInput("")
    setHolder("")
    setTransaction("ALL")
    setStatus(ALL)
    setGroup(ALL)
    setFrom("")
    setTo("")
    setSort("DATE")
    startNavigation(() => router.push(OWNERSHIP_ONE_PERCENT_HREF, { scroll: false }))
  }

  const transactionItems = options.transactions.map((t) => ({ value: t.code, label: t.label }))
  const sortItems = options.sorts.map((s) => ({ value: s.code, label: s.label.replace("Latest day first", "Latest month first") }))
  const statusItems = [{ value: ALL, label: "Local and foreign" }, ...options.statuses.map((s) => ({ value: s.code, label: s.label }))]
  const groupItems = [{ value: ALL, label: "Every type" }, ...options.groups.map((g) => ({ value: g.code, label: g.label }))]
  const single = filters.tickers.length === 1 ? (tickers.get(filters.tickers[0]) ?? null) : null

  return (
    <>
      <div className="flex flex-col gap-1">
        <h1 className="text-xl font-semibold tracking-tight">Ownership (&gt;=1%)</h1>
        <p className="text-sm text-muted-foreground">
          The shareholders of more than 1% of every IDX company, month by month, from KSEI&apos;s monthly lists: who
          bought or sold, who is new above 1% and who is no longer, which holders are individuals, corporates, funds or
          institutions, local or foreign. Upload the lists on{" "}
          <Link href={EXCEL_INGESTION_HREF} className="text-primary hover:underline">
            Ingestion &gt; Excel Documents (RAG)
          </Link>{" "}
          with the Ownership (&gt;=1%) type. The daily moves of the holders of more than 5% are on{" "}
          <Link href={OWNERSHIP_HREF} className="text-primary hover:underline">
            Ownership (&gt;=5%)
          </Link>
          .
        </p>
      </div>

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatTile
          label="Latest list"
          value={latest ? monthLabel(latest.reportDate) : "none yet"}
          icon={CalendarDaysIcon}
          hint={
            dates.length > 0
              ? `${dates.length} ${dates.length === 1 ? "month" : "months"} stored, ${monthLabel(dates[dates.length - 1])} to ${monthLabel(dates[0])}`
              : "upload a list"
          }
        />
        <StatTile
          label="Investors of more than 1%"
          value={latest ? formatNumber(latest.holders, 0) : "—"}
          icon={UsersIcon}
          tone="muted"
          hint={latest ? `of ${formatNumber(latest.issuers, 0)} companies` : undefined}
        />
        <StatTile
          label="Buys / sells in the latest month"
          value={latest && latest.previousDate ? `${formatNumber(latest.buys, 0)} / ${formatNumber(latest.sells, 0)}` : "—"}
          icon={latest && latest.sells > latest.buys ? ArrowDownRightIcon : ArrowUpRightIcon}
          tone={latest && latest.previousDate ? (latest.buys >= latest.sells ? "positive" : "negative") : "muted"}
          hint={latest?.previousDate ? `against ${monthLabel(latest.previousDate)}` : latest ? "no earlier list stored" : undefined}
        />
        <StatTile
          label="New / no longer above 1%"
          value={
            latest && latest.previousDate
              ? `${formatNumber(latest.entered, 0)} / ${latest.exited === null ? "?" : formatNumber(latest.exited, 0)}`
              : "—"
          }
          icon={ArrowRightLeftIcon}
          tone="primary"
          hint={latest && latest.previousDate ? `${formatNumber(latest.renamed + latest.moved, 0)} names changed` : undefined}
        />
      </div>

      {latest && options.highlights && (
        <MonthHighlights
          dates={dates}
          initial={options.highlights}
          onTicker={(t) => apply({ tickers: [t] })}
          onHolder={(h) => apply({ holder: h, tickers: [] })}
          onMonth={(month) => apply({ from: month, to: month, transaction: "ACTIVITY" })}
        />
      )}

      <Card>
        <CardHeader>
          <CardTitle>Filters</CardTitle>
          <CardDescription>
            Tickers separated by commas (empty: every company); part of an investor&apos;s name (&quot;lo kheng
            hong&quot;, &quot;asabri&quot;); the type of investor; the months of the lists.
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
                  list="one-percent-tickers"
                />
                <datalist id="one-percent-tickers">
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
              <FilterSelect
                label="Transaction"
                items={transactionItems}
                value={transaction}
                onChange={(v) => setTransaction(v as OnePercentTransaction)}
              />
              <FilterSelect
                label="Order"
                items={sortItems}
                value={sort}
                onChange={(v) => setSort(v as OwnershipSort)}
                className="lg:col-span-2"
              />
              <FilterSelect label="Local / foreign" items={statusItems} value={status} onChange={setStatus} />
              <FilterSelect
                label="Investor type"
                items={groupItems}
                value={group}
                onChange={setGroup}
                className="lg:col-span-2"
              />
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
                    Latest month
                  </Button>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => apply({ from: dates[Math.min(RECENT_MONTHS, dates.length) - 1], to: dates[0] })}
                  >
                    Last {Math.min(RECENT_MONTHS, dates.length)} months
                  </Button>
                  <Button type="button" variant="outline" size="sm" onClick={() => apply({ from: null, to: null })}>
                    Every month
                  </Button>
                </div>
              )}
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
        <CompanyOnePercent
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
        <EntityOnePercent query={filters.holder} onTicker={(t) => apply({ tickers: [t], holder: null })} />
      )}

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            Shareholders of more than 1%, month by month
            {(loading || navigating) && <LoaderIcon className="size-3.5 animate-spin text-muted-foreground" />}
          </CardTitle>
          <CardDescription>
            {sortItems.find((s) => s.value === filters.sort)?.label ?? "Latest month first"}
            {filters.sort !== "DATE" && " (rows without a change last)"}. Click Change or % owned to order by them.
            The change is the investor&apos;s shares at the month end minus those of the stored month before; an
            investor matched with none of the month before is new above 1%, one of the month before matched with none
            is no longer above 1%.
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
                  ? "Upload KSEI's monthly lists on Ingestion > Excel Documents (RAG) with the Ownership (>=1%) type."
                  : "Change the tickers, the investor, the transaction, the investor type or the months."
              }
            />
          ) : (
            <div className="overflow-x-auto">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead className="pl-4">Month</TableHead>
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
                    <TableHead className="hidden pr-4 text-right lg:table-cell">Scrip</TableHead>
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
        Percentages are of the shares listed, as KSEI prints them; a percentage can change without a trade when the
        shares listed change. KSEI counts an investor by SID, and an investor without an SID by account, so the same
        investor may be listed twice for a company (an SID and a scrip account): those rows are counted as one. The
        lists have no SID and spell names differently from month to month: an investor is matched with the month before
        by its name without PT / Tbk / titles, then by a name spelled almost the same (Renamed), then by the same block
        of shares under another name (Block moved: a new name or a transfer, not a trade).
      </p>
    </>
  )
}

function FilterSelect({
  label,
  items,
  value,
  onChange,
  className,
}: {
  label: string
  items: { value: string; label: string }[]
  value: string
  onChange: (value: string) => void
  className?: string
}) {
  return (
    <div className={cn("flex flex-col gap-1.5", className)}>
      <span className="text-xs font-medium text-muted-foreground">{label}</span>
      <Select items={items} value={value} onValueChange={(v) => v && onChange(v as string)}>
        <SelectTrigger aria-label={label} className="w-full">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {items.map((i) => (
            <SelectItem key={i.value} value={i.value}>
              {i.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  )
}

/**
 * A column header that orders the table by it: the first click largest first (DESC), the next smallest first (ASC),
 * the third back to the latest month first.
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
          ? "Order by the latest month again"
          : `Order by ${label.toLowerCase()}, ${next === desc ? "largest first" : "smallest first"}`
      }
    >
      {label}
      <SortIcon active={active} dir={current === asc ? "asc" : "desc"} />
    </button>
  )
}

/**
 * The movements of one month: the latest list's at first, another stored month's once chosen. Its counts, the largest
 * buys and sells, who is new above 1% and who is no longer, the names that changed, the investors in the most
 * companies.
 */
function MonthHighlights({
  dates,
  initial,
  onTicker,
  onHolder,
  onMonth,
}: {
  /** the stored months, latest first */
  dates: string[]
  /** the latest month's, loaded by the server */
  initial: OnePercentHighlights
  onTicker: (ticker: string) => void
  onHolder: (name: string) => void
  /** list every movement of the month in the table */
  onMonth: (month: string) => void
}) {
  const [month, setMonth] = useState(initial.report.reportDate)
  const [data, setData] = useState(initial)
  const [error, setError] = useState<string | null>(null)
  const loading = data.report.reportDate !== month && !error
  const index = dates.indexOf(month)

  useEffect(() => {
    if (data.report.reportDate === month) return
    let cancelled = false
    requestJson<OnePercentHighlights>(`/api/ownership/one-percent/highlights?${new URLSearchParams({ date: month })}`)
      .then(({ body }) => {
        if (cancelled) return
        setData(body)
        setError(null)
      })
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : String(e)))
    return () => {
      cancelled = true
    }
  }, [month, data.report.reportDate])

  function choose(next: string) {
    setError(null)
    setMonth(next)
  }

  const report = data.report
  const compared = report.previousDate !== null
  const monthItems = dates.map((d) => ({ value: d, label: monthLabel(d) }))
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex flex-wrap items-center gap-2">
          Highlights of {monthLabel(month)}
          {loading && <LoaderIcon className="size-3.5 animate-spin text-muted-foreground" />}
        </CardTitle>
        <CardDescription>
          The largest buys and sells of the month by percentage points, the investors new above 1% and no longer above
          it, against the stored month before, and the holdings listed under another name. Choose another month of the
          stored lists. Click a ticker for its shareholders, a name for every company the investor holds.
        </CardDescription>
        <div className="flex flex-wrap items-center gap-2 pt-1">
          <Button
            type="button"
            variant="outline"
            size="icon-sm"
            aria-label="The month before"
            title="The month before"
            disabled={index < 0 || index >= dates.length - 1}
            onClick={() => choose(dates[index + 1])}
          >
            <ChevronLeftIcon />
          </Button>
          <Select items={monthItems} value={month} onValueChange={(v) => v && choose(v as string)}>
            <SelectTrigger aria-label="Month of the highlights" className="w-40">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {monthItems.map((d) => (
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
            aria-label="The month after"
            title="The month after"
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
          <Button type="button" variant="outline" size="sm" onClick={() => onMonth(month)} className="ml-auto">
            Every movement of {monthLabel(month)}
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
          {compared
            ? `${monthLabel(report.reportDate)} against ${monthLabel(report.previousDate)}: `
            : `${monthLabel(report.reportDate)} (no earlier list stored, no movements): `}
          {formatNumber(report.holders, 0)} investors of {formatNumber(report.issuers, 0)} companies
          {compared && (
            <>
              {" "}
              · {formatNumber(report.buys, 0)} buys · {formatNumber(report.sells, 0)} sells ·{" "}
              {formatNumber(report.entered, 0)} new above 1% ·{" "}
              {report.exited === null ? "no longer above 1% unknown" : `${formatNumber(report.exited, 0)} no longer above 1%`}{" "}
              · {formatNumber(report.renamed, 0)} renamed · {formatNumber(report.moved, 0)} blocks moved
            </>
          )}
        </p>
        <div className="grid gap-4 lg:grid-cols-2">
          <Highlights
            title="Largest buys"
            icon={ArrowUpRightIcon}
            tone="text-emerald-600 dark:text-emerald-400"
            rows={data.largestBuys}
            empty={compared ? undefined : "No earlier list stored."}
            onTicker={onTicker}
            onHolder={onHolder}
          />
          <Highlights
            title="Largest sells"
            icon={ArrowDownRightIcon}
            tone="text-rose-600 dark:text-rose-400"
            rows={data.largestSells}
            empty={compared ? undefined : "No earlier list stored."}
            onTicker={onTicker}
            onHolder={onHolder}
          />
          <Highlights
            title="New above 1%"
            icon={LogInIcon}
            tone="text-sky-600 dark:text-sky-400"
            rows={data.entered}
            empty={compared ? undefined : "No earlier list stored."}
            onTicker={onTicker}
            onHolder={onHolder}
          />
          <Highlights
            title="No longer above 1%"
            icon={LogOutIcon}
            tone="text-amber-600 dark:text-amber-400"
            rows={data.exited}
            empty={compared ? undefined : "No earlier list stored."}
            onTicker={onTicker}
            onHolder={onHolder}
          />
          <Highlights
            title="Renamed / block moved"
            icon={ArrowRightLeftIcon}
            tone="text-violet-600 dark:text-violet-400"
            rows={data.renamed}
            empty={compared ? undefined : "No earlier list stored."}
            onTicker={onTicker}
            onHolder={onHolder}
          />
          <div className="flex flex-col gap-2">
            <p className="flex items-center gap-1.5 text-sm font-medium text-indigo-600 dark:text-indigo-400">
              <NetworkIcon className="size-4" /> Investors in the most companies
              <span className="text-xs font-normal text-muted-foreground">({data.topInvestors.length})</span>
            </p>
            {data.topInvestors.length === 0 ? (
              <p className="text-xs text-muted-foreground">No investor holds more than 1% of two companies.</p>
            ) : (
              <ul className="flex max-h-72 flex-col divide-y overflow-y-auto rounded-lg border">
                {data.topInvestors.map((t) => (
                  <li key={t.holderKey} className="flex items-center gap-2 px-3 py-1.5 text-sm">
                    <button
                      type="button"
                      onClick={() => onHolder(t.name)}
                      className="min-w-0 flex-1 truncate text-left hover:underline"
                      title={t.name}
                    >
                      {t.name}
                    </button>
                    <span className="shrink-0 text-right text-xs tabular-nums">
                      {t.companies} companies
                      <div className="text-muted-foreground">
                        largest {t.largestTicker} {percentLabel(t.largestPercent)}
                      </div>
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </div>
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
  row: OnePercentRow
  onTicker: (ticker: string) => void
  onHolder: (name: string) => void
}) {
  return (
    <TableRow>
      <TableCell className="pl-4 whitespace-nowrap tabular-nums">
        {monthLabel(r.reportDate)}
        {r.previousDate && <div className="text-xs text-muted-foreground">vs {monthLabel(r.previousDate)}</div>}
      </TableCell>
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
          <TypeBadge type={r.investorType} group={r.typeGroup} />
          <StatusBadge status={r.status} />
          <MatchBadge kind={r.matchKind} previousName={r.previousName} />
        </div>
        {r.matchKind && r.matchKind !== "SAME" && r.previousName && (
          <div className="max-w-[260px] truncate text-xs text-muted-foreground" title={r.previousName}>
            {r.matchKind === "MOVED" ? "Same shares as " : "Listed before as "}
            {r.previousName}
          </div>
        )}
        {r.otherNames.length > 0 && (
          <div className="max-w-[260px] truncate text-xs text-muted-foreground" title={r.otherNames.join("; ")}>
            Also listed as {r.otherNames.join("; ")}
          </div>
        )}
      </TableCell>
      <TableCell>
        <OnePercentMovementBadge movement={r.movement} />
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
      <TableCell className="hidden pr-4 text-right text-xs tabular-nums lg:table-cell">
        {r.leftList ? "—" : r.sharesScrip > 0 ? sharesLabel(r.sharesScrip) : "—"}
      </TableCell>
    </TableRow>
  )
}

/** One kind of a month's movements: ticker, investor, change. */
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
  rows: OnePercentRow[]
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
        <p className="text-xs text-muted-foreground">{empty ?? "None in this month."}</p>
      ) : (
        <ul className="flex max-h-72 flex-col divide-y overflow-y-auto rounded-lg border">
          {rows.map((r) => (
            <li key={`${r.holderId}-${r.leftList}`} className="flex items-center gap-2 px-3 py-1.5 text-sm">
              <button
                type="button"
                onClick={() => onTicker(r.ticker)}
                className="w-14 shrink-0 text-left font-mono font-medium hover:underline"
              >
                {r.ticker}
              </button>
              <span className="min-w-0 flex-1">
                <button
                  type="button"
                  onClick={() => onHolder(r.holderName)}
                  className="block w-full truncate text-left hover:underline"
                  title={r.holderName}
                >
                  {r.holderName}
                </button>
                {r.matchKind && r.matchKind !== "SAME" && r.previousName && (
                  <span className="block truncate text-xs text-muted-foreground" title={r.previousName}>
                    was {r.previousName}
                  </span>
                )}
              </span>
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
                ) : r.change === 0 ? (
                  <>
                    {percentLabel(r.percentCurrent)}
                    <div className="text-muted-foreground">{sharesLabel(r.sharesCurrent)}</div>
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
