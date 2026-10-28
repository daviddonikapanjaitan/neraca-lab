"use client"

import { Fragment, useEffect, useMemo, useState } from "react"
import Link from "next/link"
import {
  ArrowRightLeftIcon,
  CalendarDaysIcon,
  ChevronDownIcon,
  ChevronLeftIcon,
  ChevronRightIcon,
  CircleAlertIcon,
  GlobeIcon,
  LayersIcon,
  LoaderIcon,
  PieChartIcon,
  UsersIcon,
} from "lucide-react"

import { MatchBadge, OnePercentMovementBadge, TypeBadge } from "@/components/ownership-one-percent/one-percent-badges"
import { SharesChange, StatusBadge } from "@/components/ownership/ownership-badges"
import { StatTile } from "@/components/stat-tile"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { requestJson } from "@/lib/client-api"
import { EMPTY, formatNumber } from "@/lib/format"
import { companyHref } from "@/lib/links"
import { fractionLabel, percentLabel, sharesLabel } from "@/lib/ownership"
import {
  GROUP_COLOR,
  GROUP_LABEL,
  monthLabel,
  type OnePercentCompany,
  type OnePercentRow,
  shortMonth,
} from "@/lib/ownership-one-percent"
import { cn } from "@/lib/utils"

/** The months shown side by side in the investors' table; older months are a click away. */
const MONTHS_SHOWN = 7

/** The holders' segments of the bar by investor, largest first; the rest of the shares is grey. */
const SEGMENT_COLORS = [
  "bg-sky-500",
  "bg-violet-500",
  "bg-emerald-500",
  "bg-amber-500",
  "bg-rose-500",
  "bg-teal-500",
  "bg-indigo-500",
  "bg-orange-500",
]

/** One investor through the months: its rows by date (the row of the month it left the list too). */
type Line = { id: number; name: string; byDate: Map<string, OnePercentRow>; latest: OnePercentRow }

/**
 * One company on Companies > Ownership (>=1%): who holds more than 1% of it at the latest month end (bars by investor
 * and by type of investor), each investor month by month (shares, percentage, change) followed through its matches
 * (also when its name changed), its details on a click (scripless and scrip shares, the other names it is listed
 * under, its name the month before), the investors that left the list, and the digest the AI analysis reads. The date
 * filters of the page apply; the table shows at most 7 months, the latest of a range of its own, and pages to older
 * months.
 */
export function CompanyOnePercent({
  ticker,
  from,
  to,
  stored,
  onHolder,
}: {
  ticker: string
  from: string | null
  to: string | null
  /** the company is stored (it has a company page) */
  stored: boolean
  /** show the investor across its companies */
  onHolder: (name: string) => void
}) {
  const [data, setData] = useState<OnePercentCompany | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [open, setOpen] = useState<number | null>(null)
  /** the table's own range of months ("" = no bound) */
  const [rangeFrom, setRangeFrom] = useState("")
  const [rangeTo, setRangeTo] = useState("")
  /** how many months the window of MONTHS_SHOWN columns is moved back from the newest month of the range */
  const [back, setBack] = useState(0)

  useEffect(() => {
    let cancelled = false
    const query = new URLSearchParams({ ticker })
    if (from) query.set("from", from)
    if (to) query.set("to", to)
    requestJson<OnePercentCompany>(`/api/ownership/one-percent/holders?${query}`)
      .then(({ body }) => !cancelled && setData(body))
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : String(e)))
    return () => {
      cancelled = true
    }
  }, [ticker, from, to])

  const table = useMemo(() => {
    if (!data) return null
    const dates = [...new Set(data.rows.filter((r) => !r.leftList).map((r) => r.reportDate))].sort()
    const latest = dates[dates.length - 1] ?? null
    // oldest month first: a row matched with one of the month before continues that investor's line
    const ordered = [...data.rows].sort(
      (a, b) =>
        a.reportDate.localeCompare(b.reportDate) ||
        Number(a.leftList) - Number(b.leftList) ||
        a.listNumber - b.listNumber
    )
    const lineOf = new Map<number, Line>()
    const lines: Line[] = []
    for (const r of ordered) {
      if (r.leftList) {
        // no longer above 1%: the end of the line of its row of the month before
        const line = lineOf.get(r.holderId)
        if (line) line.byDate.set(r.reportDate, r)
        continue
      }
      let line = r.previousHolderId !== null ? lineOf.get(r.previousHolderId) : undefined
      if (!line) {
        line = { id: r.holderId, name: r.holderName, byDate: new Map(), latest: r }
        lines.push(line)
      }
      line.byDate.set(r.reportDate, r)
      line.latest = r
      line.name = r.holderName
      lineOf.set(r.holderId, line)
    }
    // the latest month's investors first, largest first; then the others by their last month
    lines.sort((a, b) => {
      const aNow = a.latest.reportDate === latest
      const bNow = b.latest.reportDate === latest
      if (aNow !== bNow) return aNow ? -1 : 1
      if (a.latest.reportDate !== b.latest.reportDate) return b.latest.reportDate.localeCompare(a.latest.reportDate)
      return (b.latest.percentCurrent ?? 0) - (a.latest.percentCurrent ?? 0)
    })
    return { dates, latest, lines }
  }, [data])

  // the months of the table's range (a reversed range read the other way round), then the window shown
  const view = useMemo(() => {
    if (!table) return null
    const [low, high] = rangeFrom && rangeTo && rangeFrom > rangeTo ? [rangeTo, rangeFrom] : [rangeFrom, rangeTo]
    const inRange = table.dates.filter((d) => (!low || d >= low) && (!high || d <= high))
    const offset = Math.min(back, Math.max(0, inRange.length - MONTHS_SHOWN))
    const end = inRange.length - offset
    const start = Math.max(0, end - MONTHS_SHOWN)
    const dates = inRange.slice(start, end)
    const lines = table.lines.filter((l) => dates.some((d) => l.byDate.has(d)))
    return { inRange, dates, lines, offset, start }
  }, [table, rangeFrom, rangeTo, back])

  function changeRange(nextFrom: string, nextTo: string) {
    setRangeFrom(nextFrom)
    setRangeTo(nextTo)
    setBack(0)
    setOpen(null)
  }

  const digest = data?.digest ?? null
  const totals = digest?.totals ?? null
  const rest = totals ? Math.max(0, 1 - totals.holdersOwnership) : 0

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex flex-wrap items-center gap-2">
          {ticker}: shareholders of more than 1%
          {data?.issuerName && <span className="text-sm font-normal text-muted-foreground">{data.issuerName}</span>}
        </CardTitle>
        <CardDescription>
          From KSEI&apos;s monthly lists: each investor&apos;s shares at the month end (scripless and scrip), their
          percentage of the shares listed, its type and whether it is local or foreign. Click an investor for its
          details.
          {stored && (
            <>
              {" "}
              <Link href={companyHref("IDX", ticker)} className="text-primary hover:underline">
                Company page
              </Link>
            </>
          )}
        </CardDescription>
        {table && (
          <CardAction className="hidden sm:block">
            <Badge variant="outline">
              {table.lines.length} investors · {table.dates.length} {table.dates.length === 1 ? "month" : "months"}
            </Badge>
          </CardAction>
        )}
      </CardHeader>
      <CardContent className="flex flex-col gap-4 px-0">
        {error && (
          <p role="alert" className="flex items-start gap-2 px-4 text-xs text-destructive">
            <CircleAlertIcon className="mt-px size-3.5 shrink-0" />
            <span>{error}</span>
          </p>
        )}
        {!data ? (
          !error && (
            <p className="flex items-center gap-2 px-4 text-sm text-muted-foreground">
              <LoaderIcon className="size-3.5 animate-spin" /> Loading the shareholders
            </p>
          )
        ) : !digest || !table || !view || !totals ? (
          <p className="px-4 text-sm text-muted-foreground">No stored list names {ticker} for these dates.</p>
        ) : (
          <>
            <p className="px-4 text-sm">{digest.reading}</p>

            <div className="grid gap-3 px-4 sm:grid-cols-2 lg:grid-cols-4">
              <StatTile
                label={`Held by investors of 1%+, ${monthLabel(digest.latestDate)}`}
                value={fractionLabel(totals.holdersOwnership)}
                icon={PieChartIcon}
                hint={`${totals.holders} ${totals.holders === 1 ? "investor" : "investors"} · the 3 largest ${fractionLabel(totals.top3Ownership)}`}
              />
              <StatTile
                label="Foreign investors"
                value={fractionLabel(totals.foreignOwnership)}
                icon={GlobeIcon}
                tone="muted"
                hint={`local ${fractionLabel(totals.localOwnership)}`}
              />
              <StatTile
                label={`Buys and sells, ${digest.months} ${digest.months === 1 ? "month" : "months"}`}
                value={`${totals.buys} / ${totals.sells}`}
                icon={UsersIcon}
                tone={totals.buys > totals.sells ? "positive" : totals.sells > totals.buys ? "negative" : "muted"}
                hint={`${totals.entered} new above 1% · ${totals.exited} no longer above 1%`}
              />
              <StatTile
                label="Names changed"
                value={`${totals.renamed + totals.moved}`}
                icon={ArrowRightLeftIcon}
                tone="muted"
                hint={`${totals.renamed} renamed · ${totals.moved} ${totals.moved === 1 ? "block" : "blocks"} moved`}
              />
            </div>

            {/* composition at the latest month end: each holder's share of the listed shares, then by type */}
            <div className="flex flex-col gap-3 px-4">
              <div className="flex flex-col gap-2">
                <span className="text-xs font-medium text-muted-foreground">By investor</span>
                <div
                  className="flex h-4 w-full overflow-hidden rounded-full bg-muted"
                  role="img"
                  aria-label={`Shareholders of more than 1% of ${ticker} at the end of ${monthLabel(digest.latestDate)}`}
                >
                  {digest.holders.map((h, i) => (
                    <div
                      key={`${h.name}-${i}`}
                      className={cn("h-full border-r border-background last:border-r-0", SEGMENT_COLORS[i % SEGMENT_COLORS.length])}
                      style={{ width: `${Math.max(0, (h.ownership ?? 0) * 100)}%` }}
                      title={`${h.name}: ${fractionLabel(h.ownership)}`}
                    />
                  ))}
                </div>
                <ul className="flex flex-wrap gap-x-4 gap-y-1 text-xs">
                  {digest.holders.slice(0, 8).map((h, i) => (
                    <li key={`${h.name}-${i}`} className="flex items-center gap-1.5">
                      <span className={cn("size-2.5 rounded-full", SEGMENT_COLORS[i % SEGMENT_COLORS.length])} />
                      <span className="max-w-[220px] truncate">{h.name}</span>
                      <span className="font-medium tabular-nums">{fractionLabel(h.ownership)}</span>
                    </li>
                  ))}
                  {digest.holders.length > 8 && (
                    <li className="text-muted-foreground">+{digest.holders.length - 8} more</li>
                  )}
                  <li className="flex items-center gap-1.5 text-muted-foreground">
                    <span className="size-2.5 rounded-full bg-muted ring-1 ring-border" />
                    Holders below 1% and the public <span className="tabular-nums">{fractionLabel(rest)}</span>
                  </li>
                </ul>
              </div>
              <div className="flex flex-col gap-2">
                <span className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
                  <LayersIcon className="size-3.5" /> By type of investor
                </span>
                <div className="flex h-4 w-full overflow-hidden rounded-full bg-muted" role="img" aria-label="By type of investor">
                  {digest.byType.map((g) => (
                    <div
                      key={g.group}
                      className={cn("h-full border-r border-background last:border-r-0", GROUP_COLOR[g.group])}
                      style={{ width: `${Math.max(0, g.ownership * 100)}%` }}
                      title={`${GROUP_LABEL[g.group]}: ${fractionLabel(g.ownership)} (${g.holders})`}
                    />
                  ))}
                </div>
                <ul className="flex flex-wrap gap-x-4 gap-y-1 text-xs">
                  {digest.byType.map((g) => (
                    <li key={g.group} className="flex items-center gap-1.5">
                      <span className={cn("size-2.5 rounded-full", GROUP_COLOR[g.group])} />
                      {GROUP_LABEL[g.group]}
                      <span className="font-medium tabular-nums">{fractionLabel(g.ownership)}</span>
                      <span className="text-muted-foreground">({g.holders})</span>
                    </li>
                  ))}
                </ul>
              </div>
            </div>

            <div className="flex flex-wrap items-end gap-3 px-4">
              <label className="flex flex-col gap-1.5">
                <span className="text-xs font-medium text-muted-foreground">Months from</span>
                <Input
                  type="date"
                  value={rangeFrom}
                  min={table.dates[0]}
                  max={table.dates[table.dates.length - 1]}
                  onChange={(e) => changeRange(e.target.value, rangeTo)}
                  aria-label="Months from"
                  className="w-40"
                />
              </label>
              <label className="flex flex-col gap-1.5">
                <span className="text-xs font-medium text-muted-foreground">To</span>
                <Input
                  type="date"
                  value={rangeTo}
                  min={table.dates[0]}
                  max={table.dates[table.dates.length - 1]}
                  onChange={(e) => changeRange(rangeFrom, e.target.value)}
                  aria-label="Months to"
                  className="w-40"
                />
              </label>
              {(rangeFrom || rangeTo) && (
                <Button type="button" variant="outline" size="sm" className="mb-0.5" onClick={() => changeRange("", "")}>
                  Every month
                </Button>
              )}
              {view.dates.length > 0 && (
                <div className="ml-auto flex items-center gap-1.5">
                  <span className="flex items-center gap-1.5 text-xs text-muted-foreground">
                    <CalendarDaysIcon className="size-3.5" />
                    {view.dates.length === 1
                      ? monthLabel(view.dates[0])
                      : `${shortMonth(view.dates[0])} – ${monthLabel(view.dates[view.dates.length - 1])}`}
                    {" · "}
                    {view.dates.length} of {view.inRange.length} {view.inRange.length === 1 ? "month" : "months"}
                  </span>
                  <Button
                    type="button"
                    variant="outline"
                    size="icon-sm"
                    aria-label="Older months"
                    title="Older months"
                    disabled={view.start === 0}
                    onClick={() => setBack(view.offset + MONTHS_SHOWN)}
                  >
                    <ChevronLeftIcon />
                  </Button>
                  <Button
                    type="button"
                    variant="outline"
                    size="icon-sm"
                    aria-label="Newer months"
                    title="Newer months"
                    disabled={view.offset === 0}
                    onClick={() => setBack(Math.max(0, view.offset - MONTHS_SHOWN))}
                  >
                    <ChevronRightIcon />
                  </Button>
                </div>
              )}
            </div>

            {view.dates.length === 0 ? (
              <p className="px-4 text-sm text-muted-foreground">No stored list names {ticker} between these dates.</p>
            ) : (
              <div className="overflow-x-auto">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead className="pl-4">Investor</TableHead>
                      {view.dates.map((d) => (
                        <TableHead key={d} className="text-right whitespace-nowrap last:pr-4">
                          {shortMonth(d)}
                        </TableHead>
                      ))}
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {view.lines.map((line) => {
                      const expanded = open === line.id
                      const current = line.latest
                      return (
                        <Fragment key={line.id}>
                          <TableRow
                            className="cursor-pointer"
                            onClick={() => setOpen(expanded ? null : line.id)}
                            aria-expanded={expanded}
                          >
                            <TableCell className="pl-4">
                              <div className="flex items-start gap-1.5">
                                {expanded ? (
                                  <ChevronDownIcon className="mt-0.5 size-3.5 shrink-0 text-muted-foreground" />
                                ) : (
                                  <ChevronRightIcon className="mt-0.5 size-3.5 shrink-0 text-muted-foreground" />
                                )}
                                <div className="min-w-0">
                                  <button
                                    type="button"
                                    onClick={(e) => {
                                      e.stopPropagation()
                                      onHolder(line.name)
                                    }}
                                    className="max-w-[280px] text-left font-medium hover:underline"
                                    title="Every company this investor holds"
                                  >
                                    {line.name}
                                  </button>
                                  <div className="flex flex-wrap items-center gap-1">
                                    <TypeBadge type={current.investorType} group={current.typeGroup} />
                                    <StatusBadge status={current.status} />
                                    {current.reportDate !== table.latest && (
                                      <Badge variant="outline" className="text-amber-700 dark:text-amber-300">
                                        Not listed {monthLabel(table.latest)}
                                      </Badge>
                                    )}
                                  </div>
                                </div>
                              </div>
                            </TableCell>
                            {view.dates.map((d) => {
                              const r = line.byDate.get(d)
                              return (
                                <TableCell key={d} className="text-right align-top tabular-nums last:pr-4">
                                  {!r ? (
                                    <span className="text-muted-foreground">{EMPTY}</span>
                                  ) : r.leftList ? (
                                    <OnePercentMovementBadge movement="EXITED" />
                                  ) : (
                                    <>
                                      <div>{sharesLabel(r.sharesCurrent)}</div>
                                      <div className="text-xs text-muted-foreground">{percentLabel(r.percentCurrent)}</div>
                                      <div className="text-xs">
                                        {r.movement === "ENTERED" ? (
                                          <OnePercentMovementBadge movement="ENTERED" />
                                        ) : r.movement === null ? (
                                          <span className="text-muted-foreground">{EMPTY}</span>
                                        ) : (
                                          <SharesChange value={r.change} />
                                        )}
                                      </div>
                                      {r.matchKind && r.matchKind !== "SAME" && (
                                        <div className="mt-0.5 flex justify-end">
                                          <MatchBadge kind={r.matchKind} previousName={r.previousName} />
                                        </div>
                                      )}
                                    </>
                                  )}
                                </TableCell>
                              )
                            })}
                          </TableRow>
                          {expanded && (
                            <TableRow className="bg-muted/30 hover:bg-muted/30">
                              <TableCell colSpan={view.dates.length + 1} className="px-4 py-3">
                                <InvestorDetails line={line} />
                              </TableCell>
                            </TableRow>
                          )}
                        </Fragment>
                      )
                    })}
                  </TableBody>
                </Table>
              </div>
            )}

            {digest.formerHolders.length > 0 && (
              <p className="px-4 text-xs text-muted-foreground">
                No longer listed (below 1%):{" "}
                {digest.formerHolders
                  .map((f) => `${f.name} (last listed ${monthLabel(f.lastListed)}, ${fractionLabel(f.ownershipThen)})`)
                  .join("; ")}
                .
              </p>
            )}
          </>
        )}
      </CardContent>
    </Card>
  )
}

/** An investor month by month: its names, type, scripless and scrip shares, total, percentage and change. */
function InvestorDetails({ line }: { line: Line }) {
  const months = [...line.byDate.values()].sort((a, b) => b.reportDate.localeCompare(a.reportDate))
  const latest = line.latest
  const names = new Set<string>()
  months.forEach((r) => {
    names.add(r.holderName)
    r.otherNames.forEach((n) => names.add(n))
  })
  names.delete(line.name)
  return (
    <div className="flex flex-col gap-2">
      <p className="text-xs text-muted-foreground">
        {line.name}
        {latest.investorType ? ` · ${latest.investorType}` : ""}
        {latest.nationality ? ` · nationality ${latest.nationality}` : ""}
        {latest.domicile ? ` · domicile ${latest.domicile}` : ""}
        {names.size > 0 && <> · also listed as {[...names].join("; ")}</>}
      </p>
      <div className="overflow-x-auto rounded-lg border bg-background">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead className="pl-3">Month</TableHead>
              <TableHead>Listed as</TableHead>
              <TableHead className="text-right">Scripless</TableHead>
              <TableHead className="text-right">Scrip</TableHead>
              <TableHead className="text-right">Total</TableHead>
              <TableHead className="text-right">% listed</TableHead>
              <TableHead className="pr-3 text-right">Change</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {months.map((r) => (
              <TableRow key={`${r.reportDate}-${r.leftList}`}>
                <TableCell className="pl-3 text-xs whitespace-nowrap">{monthLabel(r.reportDate)}</TableCell>
                <TableCell className="max-w-[280px] text-xs">
                  {r.leftList ? (
                    <OnePercentMovementBadge movement="EXITED" />
                  ) : (
                    <>
                      <span className="truncate" title={r.holderName}>
                        {r.holderName}
                      </span>
                      {r.sourceRows > 1 && (
                        <span className="text-muted-foreground"> ({r.sourceRows} rows counted together)</span>
                      )}
                      {r.matchKind && r.matchKind !== "SAME" && r.previousName && (
                        <div className="text-muted-foreground">
                          {r.matchKind === "MOVED" ? "the same shares as " : "listed before as "}
                          {r.previousName}
                        </div>
                      )}
                    </>
                  )}
                </TableCell>
                <TableCell className="text-right text-xs tabular-nums">
                  {r.leftList ? EMPTY : sharesLabel(r.sharesScripless)}
                </TableCell>
                <TableCell className="text-right text-xs tabular-nums">
                  {r.leftList ? EMPTY : sharesLabel(r.sharesScrip)}
                </TableCell>
                <TableCell className="text-right text-xs tabular-nums">
                  {r.leftList ? `was ${sharesLabel(r.sharesPrevious)}` : sharesLabel(r.sharesCurrent)}
                </TableCell>
                <TableCell className="text-right text-xs tabular-nums">
                  {r.leftList ? `was ${percentLabel(r.percentPrevious)}` : percentLabel(r.percentCurrent)}
                </TableCell>
                <TableCell className="pr-3 text-right text-xs tabular-nums">
                  {r.leftList ? (
                    EMPTY
                  ) : r.movement === "ENTERED" ? (
                    <span className="text-sky-700 dark:text-sky-300">new</span>
                  ) : r.movement === null ? (
                    <span className="text-muted-foreground">first list</span>
                  ) : (
                    <SharesChange value={r.change} />
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      <p className="text-[11px] text-muted-foreground">
        {formatNumber(months.filter((r) => !r.leftList).length, 0)} of the stored months list it.
      </p>
    </div>
  )
}
