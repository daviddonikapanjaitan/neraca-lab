"use client"

import { useEffect, useMemo, useState, useTransition } from "react"
import Link from "next/link"
import { useRouter } from "next/navigation"
import {
  Building2Icon,
  CalendarCheckIcon,
  ChevronRightIcon,
  FileSpreadsheetIcon,
  GaugeIcon,
  LineChartIcon,
  LoaderIcon,
  SearchIcon,
} from "lucide-react"

import { EmptyState } from "@/components/empty-state"
import { SortIcon, type SortDir } from "@/components/sort-icon"
import { StatTile } from "@/components/stat-tile"
import { Badge } from "@/components/ui/badge"
import { buttonVariants } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Skeleton } from "@/components/ui/skeleton"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { RequestError, requestJson } from "@/lib/client-api"
import { EMPTY, formatDate, formatNumber, formatTimestamp } from "@/lib/format"
import { companiesHref, companyHref } from "@/lib/links"
import { quantScoresUrl, scoreTone, VERDICT_LABELS, type QuantScore, type QuantScoreBoard } from "@/lib/scorecards"
import type { CompanyListResponse, CompanySummary, Exchange } from "@/lib/types"
import { cn } from "@/lib/utils"

type SortKey =
  | "ticker"
  | "companyName"
  | "sector"
  | "quantScore"
  | "periodCount"
  | "latestPeriodEnd"
  | "latestPriceDate"

const ALL_SECTORS = "__all__"

const TONE_TEXT = {
  good: "text-emerald-600 dark:text-emerald-400",
  mid: "text-amber-600 dark:text-amber-400",
  poor: "text-rose-600 dark:text-rose-400",
  none: "text-muted-foreground",
} as const

type Scores =
  | { state: "loading"; exchange: string }
  | { state: "ready"; exchange: string; board: QuantScoreBoard; byTicker: Map<string, QuantScore> }
  | { state: "error"; exchange: string; message: string }

/** A company of the list with its overall Quant Score (null: unknown or not loaded yet). */
type Row = CompanySummary & { quantScore: number | null }

function compare(a: Row, b: Row, key: SortKey): number {
  const va = a[key]
  const vb = b[key]
  // missing values always last, whatever the direction
  if (va === null || va === undefined) return vb === null || vb === undefined ? 0 : 1
  if (vb === null || vb === undefined) return -1
  if (typeof va === "number" && typeof vb === "number") return va - vb
  return String(va).localeCompare(String(vb))
}

export function CompaniesPageClient({
  exchanges,
  list,
}: {
  exchanges: Exchange[]
  list: CompanyListResponse
}) {
  const router = useRouter()
  const [pending, startTransition] = useTransition()
  const [search, setSearch] = useState("")
  const [sector, setSector] = useState(ALL_SECTORS)
  const [sortKey, setSortKey] = useState<SortKey>("ticker")
  const [sortDir, setSortDir] = useState<SortDir>("asc")
  const [scores, setScores] = useState<Scores>({ state: "loading", exchange: list.exchange })

  // the overall Quant Score of every company, loaded after the list (a few seconds when the backend has not kept them)
  useEffect(() => {
    const abort = new AbortController()
    requestJson<QuantScoreBoard>(quantScoresUrl(list.exchange), { signal: abort.signal })
      .then(({ body }) => {
        if (abort.signal.aborted) return
        setScores({
          state: "ready",
          exchange: list.exchange,
          board: body,
          byTicker: new Map(body.scores.map((s) => [s.ticker, s])),
        })
      })
      .catch((e: unknown) => {
        if (abort.signal.aborted) return
        setScores({
          state: "error",
          exchange: list.exchange,
          message: e instanceof RequestError ? e.message : "The Quant Scores could not be loaded.",
        })
      })
    return () => abort.abort()
  }, [list.exchange])

  // the scores of another exchange (the list changed, its scores are loading) are not shown
  const current: Scores = scores.exchange === list.exchange ? scores : { state: "loading", exchange: list.exchange }
  const byTicker = current.state === "ready" ? current.byTicker : null

  const companies = useMemo<Row[]>(
    () => list.companies.map((c) => ({ ...c, quantScore: byTicker?.get(c.ticker)?.overall ?? null })),
    [list.companies, byTicker]
  )

  const sectors = useMemo(
    () => Array.from(new Set(companies.map((c) => c.sector).filter((s): s is string => !!s))).sort(),
    [companies]
  )

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase()
    const rows = companies.filter((c) => {
      if (sector !== ALL_SECTORS && c.sector !== sector) return false
      if (!q) return true
      return [c.ticker, c.companyName, c.legalName, c.sector, c.industry]
        .some((v) => v?.toLowerCase().includes(q))
    })
    return rows.sort((a, b) => {
      const result = compare(a, b, sortKey)
      const bothPresent = a[sortKey] !== null && b[sortKey] !== null
      return sortDir === "asc" || !bothPresent ? result : -result
    })
  }, [companies, search, sector, sortKey, sortDir])

  const stats = useMemo(() => {
    const withStatements = companies.filter((c) => c.periodCount > 0).length
    const withPrices = companies.filter((c) => c.latestPriceDate).length
    const latest = companies
      .filter((c) => c.latestPeriodEnd)
      .sort((a, b) => (b.latestPeriodEnd ?? "").localeCompare(a.latestPeriodEnd ?? ""))[0]
    return { withStatements, withPrices, latest }
  }, [companies])

  function toggleSort(key: SortKey) {
    if (sortKey === key) {
      setSortDir(sortDir === "asc" ? "desc" : "asc")
    } else {
      setSortKey(key)
      // the best score first
      setSortDir(key === "quantScore" ? "desc" : "asc")
    }
  }

  function changeExchange(code: string | null) {
    if (!code || code === list.exchange) return
    setSector(ALL_SECTORS)
    startTransition(() => router.push(companiesHref(code)))
  }

  const exchangeItems = exchanges.map((e) => ({ value: e.code, label: `${e.code} · ${e.name}` }))
  const sectorItems = [
    { value: ALL_SECTORS, label: "All sectors" },
    ...sectors.map((s) => ({ value: s, label: s })),
  ]
  const filtersActive = search.trim() !== "" || sector !== ALL_SECTORS

  const header = (key: SortKey, label: string, className?: string) => (
    <TableHead className={className} aria-sort={sortKey === key ? (sortDir === "asc" ? "ascending" : "descending") : "none"}>
      <button
        type="button"
        onClick={() => toggleSort(key)}
        className="inline-flex select-none items-center font-medium hover:text-foreground"
      >
        {label}
        <SortIcon active={sortKey === key} dir={sortDir} />
      </button>
    </TableHead>
  )

  return (
    <div className="flex flex-col gap-4">
      {/* Title */}
      <div className="flex flex-col gap-1">
        <h1 className="text-xl font-semibold tracking-tight">Companies</h1>
        <p className="text-sm text-muted-foreground">
          Listed companies stored in Neraca Lab on the {list.exchangeName} ({list.exchange}).
        </p>
      </div>

      {/* Summary */}
      <div className={cn("grid grid-cols-2 gap-3 lg:grid-cols-4 transition-opacity", pending && "opacity-60")}>
        <StatTile label="Companies" value={list.count} icon={Building2Icon} hint={list.exchange} />
        <StatTile
          label="With financial statements"
          value={`${stats.withStatements} / ${list.count}`}
          icon={FileSpreadsheetIcon}
          tone="positive"
        />
        <StatTile
          label="With price history"
          value={`${stats.withPrices} / ${list.count}`}
          icon={LineChartIcon}
          tone="muted"
        />
        <StatTile
          label="Latest reported period"
          value={stats.latest?.latestPeriod ?? EMPTY}
          hint={stats.latest ? `${stats.latest.ticker} · ends ${formatDate(stats.latest.latestPeriodEnd)}` : undefined}
          icon={CalendarCheckIcon}
          tone="muted"
        />
      </div>

      {/* Filters */}
      <div className="flex flex-wrap items-center gap-2">
        <div className="relative w-full sm:min-w-[220px] sm:flex-1">
          <SearchIcon className="pointer-events-none absolute left-2.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            placeholder="Search ticker, name, sector or industry..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="pl-8"
            aria-label="Search companies"
          />
        </div>

        <Select items={exchangeItems} value={list.exchange} onValueChange={changeExchange}>
          <SelectTrigger aria-label="Exchange" className="min-w-[220px]">
            {pending && <LoaderIcon className="size-3.5 animate-spin" />}
            <SelectValue placeholder="Exchange" />
          </SelectTrigger>
          <SelectContent>
            {exchangeItems.map((e) => (
              <SelectItem key={e.value} value={e.value}>
                {e.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>

        <Select items={sectorItems} value={sector} onValueChange={(v) => v && setSector(v)}>
          <SelectTrigger aria-label="Sector" className="min-w-[180px]">
            <SelectValue placeholder="Sector" />
          </SelectTrigger>
          <SelectContent>
            {sectorItems.map((s) => (
              <SelectItem key={s.value} value={s.value}>
                {s.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {/* Table */}
      <Card className={cn("transition-opacity", pending && "opacity-60")}>
        <CardHeader>
          <CardTitle>{list.exchange} companies</CardTitle>
          <CardDescription>
            {filtersActive
              ? `${filtered.length} of ${list.count} companies match the filters`
              : `${list.count} ${list.count === 1 ? "company" : "companies"}`}
            . Select a company to see everything stored for it.
            <span className="mt-1 flex items-start gap-1.5">
              <GaugeIcon className="mt-0.5 size-3.5 shrink-0" />
              <span>
                Quant Score: the overall of the six fundamental agents of the Quant Score tab (preset rules, no AI)
                {current.state === "ready" &&
                  ` · ${current.board.scored} of ${current.board.companies} scored, computed ${formatTimestamp(current.board.computedAt)}`}
                {current.state === "loading" && " · computing…"}
                {current.state === "error" && (
                  <span className="text-rose-600 dark:text-rose-400"> · not available: {current.message}</span>
                )}
              </span>
            </span>
          </CardDescription>
        </CardHeader>
        <CardContent className="px-0">
          {filtered.length === 0 ? (
            filtersActive ? (
              <EmptyState
                variant="filter"
                actionLabel="Clear filters"
                onAction={() => {
                  setSearch("")
                  setSector(ALL_SECTORS)
                }}
              />
            ) : (
              <EmptyState
                variant="generic"
                title={`No ${list.exchange} companies yet`}
                description="Upload an IDX financial statement workbook through the API to add a company."
              />
            )
          ) : (
            <div className="overflow-x-auto">
              <Table>
                <TableHeader>
                  <TableRow>
                    {header("ticker", "Company", "pl-4")}
                    {header("sector", "Sector / industry", "hidden md:table-cell")}
                    {header("quantScore", "Quant Score", "text-right")}
                    {header("periodCount", "Periods", "text-right")}
                    {header("latestPeriodEnd", "Latest period", "hidden sm:table-cell")}
                    {header("latestPriceDate", "Latest price", "hidden lg:table-cell")}
                    <TableHead className="hidden xl:table-cell">Currency</TableHead>
                    <TableHead className="pr-4 text-right">
                      <span className="sr-only">Details</span>
                    </TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {filtered.map((c) => {
                    const href = companyHref(c.exchange, c.ticker)
                    return (
                      <TableRow
                        key={c.companyId}
                        className="cursor-pointer"
                        onClick={() => router.push(href)}
                      >
                        <TableCell className="pl-4">
                          <div className="flex items-center gap-2.5">
                            <div className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-[11px] font-semibold text-primary">
                              {c.ticker.slice(0, 2)}
                            </div>
                            <div className="min-w-0">
                              <div className="flex items-center gap-1.5">
                                <Link
                                  href={href}
                                  className="font-medium hover:underline"
                                  onClick={(e) => e.stopPropagation()}
                                >
                                  {c.companyName}
                                </Link>
                                {!c.active && <Badge variant="outline">Inactive</Badge>}
                              </div>
                              <div className="text-xs text-muted-foreground">
                                <span className="font-mono">{c.ticker}</span>
                                {c.legalName && <span className="hidden sm:inline"> · {c.legalName}</span>}
                              </div>
                            </div>
                          </div>
                        </TableCell>
                        <TableCell className="hidden md:table-cell">
                          <div className="max-w-[260px]">
                            <div className="truncate">{c.sector ?? EMPTY}</div>
                            <div className="truncate text-xs text-muted-foreground">{c.industry ?? EMPTY}</div>
                          </div>
                        </TableCell>
                        <TableCell className="text-right">
                          <QuantScoreCell
                            scores={current}
                            score={byTicker?.get(c.ticker) ?? null}
                            href={`${href}?tab=quant`}
                          />
                        </TableCell>
                        <TableCell className="text-right tabular-nums">{c.periodCount}</TableCell>
                        <TableCell className="hidden sm:table-cell">
                          {c.latestPeriod ? (
                            <div>
                              <Badge variant="secondary" className="font-mono">{c.latestPeriod}</Badge>
                              <div className="mt-0.5 text-xs text-muted-foreground">
                                ends {formatDate(c.latestPeriodEnd)}
                              </div>
                            </div>
                          ) : (
                            <span className="text-muted-foreground">{EMPTY}</span>
                          )}
                        </TableCell>
                        <TableCell className="hidden tabular-nums lg:table-cell">
                          {c.latestPriceDate ? formatDate(c.latestPriceDate) : (
                            <span className="text-muted-foreground">No prices</span>
                          )}
                        </TableCell>
                        <TableCell className="hidden xl:table-cell">{c.currency ?? EMPTY}</TableCell>
                        <TableCell className="pr-4 text-right">
                          <Link
                            href={href}
                            onClick={(e) => e.stopPropagation()}
                            className={buttonVariants({ variant: "ghost", size: "sm" })}
                          >
                            Details <ChevronRightIcon />
                          </Link>
                        </TableCell>
                      </TableRow>
                    )
                  })}
                </TableBody>
              </Table>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  )
}

/** The overall Quant Score of a company with its verdict; opens the company's Quant Score tab. */
function QuantScoreCell({ scores, score, href }: { scores: Scores; score: QuantScore | null; href: string }) {
  if (scores.state === "loading") {
    return <Skeleton className="ml-auto h-4 w-10" aria-label="Computing the Quant Score" />
  }
  if (scores.state === "error" || !score || score.overall === null) {
    return <span className="text-muted-foreground">{EMPTY}</span>
  }
  const tone = scoreTone(score.overall)
  return (
    <Link
      href={href}
      onClick={(e) => e.stopPropagation()}
      className="inline-flex flex-col items-end hover:underline"
      title={`${score.agentsCounted} of 6 agents in the overall${score.financial ? " · scored as a financial company" : ""}`}
    >
      <span className={cn("font-mono font-semibold tabular-nums", TONE_TEXT[tone])}>
        {formatNumber(score.overall, 1)}
      </span>
      {score.verdict && <span className="text-xs text-muted-foreground">{VERDICT_LABELS[score.verdict]}</span>}
    </Link>
  )
}
