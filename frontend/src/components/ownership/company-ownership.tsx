"use client"

import { Fragment, useEffect, useMemo, useState } from "react"
import Link from "next/link"
import {
  ArrowRightLeftIcon,
  ChevronDownIcon,
  ChevronRightIcon,
  CircleAlertIcon,
  GlobeIcon,
  LoaderIcon,
  PieChartIcon,
  UsersIcon,
} from "lucide-react"

import { MovementBadge, SharesChange, StatusBadge } from "@/components/ownership/ownership-badges"
import { StatTile } from "@/components/stat-tile"
import { Badge } from "@/components/ui/badge"
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { requestJson } from "@/lib/client-api"
import { EMPTY } from "@/lib/format"
import { companyHref } from "@/lib/links"
import {
  dayLabel,
  fractionLabel,
  type HolderDay,
  type OwnershipCompany,
  percentLabel,
  sharesLabel,
  shortDay,
} from "@/lib/ownership"
import { cn } from "@/lib/utils"

/** The holders' segments of the composition bar, largest first; the rest of the shares is grey. */
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

/**
 * One company on Companies > Ownership (>=5%): who holds more than 5% of it on the latest day (a bar of their
 * shares of the listed shares, local and foreign), each holder day by day (shares, percentage, change) with its
 * securities accounts (broker or custodian) on a click, the investors that left the list and the digest the AI
 * analysis reads. The date filters of the page apply.
 */
export function CompanyOwnership({
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
  const [data, setData] = useState<OwnershipCompany | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [open, setOpen] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    const query = new URLSearchParams({ ticker })
    if (from) query.set("from", from)
    if (to) query.set("to", to)
    requestJson<OwnershipCompany>(`/api/ownership/holders?${query}`)
      .then(({ body }) => !cancelled && setData(body))
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : String(e)))
    return () => {
      cancelled = true
    }
  }, [ticker, from, to])

  const table = useMemo(() => {
    if (!data) return null
    const dates = [...new Set(data.days.map((d) => d.holder.reportDate))].sort()
    const latest = dates[dates.length - 1] ?? null
    // one row per investor (aliases with their first listing), in the latest list's order, then those that left
    const rows = new Map<string, { name: string; key: string; byDate: Map<string, HolderDay>; latest: HolderDay | null }>()
    const ordered = [...data.days].sort((a, b) =>
      a.holder.reportDate === b.holder.reportDate
        ? Number(a.holder.leftList) - Number(b.holder.leftList) || a.holder.listNumber - b.holder.listNumber
        : b.holder.reportDate.localeCompare(a.holder.reportDate)
    )
    for (const d of ordered) {
      if (d.holder.aliasOf !== null) continue
      const key = d.holder.holderKey
      if (!rows.has(key)) rows.set(key, { name: d.holder.holderName, key, byDate: new Map(), latest: null })
      const row = rows.get(key)!
      if (!row.byDate.has(d.holder.reportDate)) row.byDate.set(d.holder.reportDate, d)
      if (!row.latest && !d.holder.leftList) row.latest = d
    }
    return { dates, latest, rows: [...rows.values()] }
  }, [data])

  const digest = data?.digest ?? null
  const holders = digest?.holders ?? []
  const totals = digest?.totals ?? null
  const rest = totals ? Math.max(0, 1 - totals.holdersOwnership) : 0

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex flex-wrap items-center gap-2">
          {ticker}: shareholders of more than 5%
          {data?.issuerName && <span className="text-sm font-normal text-muted-foreground">{data.issuerName}</span>}
        </CardTitle>
        <CardDescription>
          From KSEI&apos;s daily lists, by investor (SID): combined shares over all its securities accounts, and their
          percentage of the shares listed as KSEI prints it. Click an investor for its accounts.
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
              {table.rows.length} investors · {table.dates.length} {table.dates.length === 1 ? "day" : "days"}
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
        ) : !digest || !table ? (
          <p className="px-4 text-sm text-muted-foreground">No stored list names {ticker} for these dates.</p>
        ) : (
          <>
            <p className="px-4 text-sm">{digest.reading}</p>

            <div className="grid gap-3 px-4 sm:grid-cols-2 lg:grid-cols-4">
              <StatTile
                label={`Held by investors of 5%+, ${dayLabel(digest.latestDate)}`}
                value={fractionLabel(totals!.holdersOwnership)}
                icon={PieChartIcon}
                hint={`${totals!.holders} ${totals!.holders === 1 ? "investor" : "investors"}`}
              />
              <StatTile
                label="Foreign investors"
                value={fractionLabel(totals!.foreignOwnership)}
                icon={GlobeIcon}
                tone="muted"
                hint={`local ${fractionLabel(totals!.localOwnership)}`}
              />
              <StatTile
                label={`Buys and sells, ${digest.days} ${digest.days === 1 ? "day" : "days"}`}
                value={`${totals!.buys} / ${totals!.sells}`}
                icon={UsersIcon}
                tone={totals!.buys > totals!.sells ? "positive" : totals!.sells > totals!.buys ? "negative" : "muted"}
                hint={`${totals!.entered} crossed 5% · ${totals!.exited} fell below 5%`}
              />
              <StatTile
                label="Moves between accounts"
                value={`${totals!.accountMoveDays} ${totals!.accountMoveDays === 1 ? "day" : "days"}`}
                icon={ArrowRightLeftIcon}
                tone="muted"
                hint="shares moved, investor's total unchanged"
              />
            </div>

            {/* composition on the latest day: each holder's share of the listed shares, the rest grey */}
            <div className="flex flex-col gap-2 px-4">
              <div
                className="flex h-4 w-full overflow-hidden rounded-full bg-muted"
                role="img"
                aria-label={`Shareholders of more than 5% of ${ticker} on ${dayLabel(digest.latestDate)}`}
              >
                {holders.map((h, i) => (
                  <div
                    key={h.name}
                    className={cn("h-full border-r border-background last:border-r-0", SEGMENT_COLORS[i % SEGMENT_COLORS.length])}
                    style={{ width: `${Math.max(0, (h.ownership ?? 0) * 100)}%` }}
                    title={`${h.name}: ${fractionLabel(h.ownership)}`}
                  />
                ))}
              </div>
              <ul className="flex flex-wrap gap-x-4 gap-y-1 text-xs">
                {holders.map((h, i) => (
                  <li key={h.name} className="flex items-center gap-1.5">
                    <span className={cn("size-2.5 rounded-full", SEGMENT_COLORS[i % SEGMENT_COLORS.length])} />
                    <span className="max-w-[220px] truncate">{h.name}</span>
                    <span className="font-medium tabular-nums">{fractionLabel(h.ownership)}</span>
                  </li>
                ))}
                <li className="flex items-center gap-1.5 text-muted-foreground">
                  <span className="size-2.5 rounded-full bg-muted ring-1 ring-border" />
                  Holders below 5% and the public <span className="tabular-nums">{fractionLabel(rest)}</span>
                </li>
              </ul>
            </div>

            <div className="overflow-x-auto">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead className="pl-4">Investor</TableHead>
                    {table.dates.map((d) => (
                      <TableHead key={d} className="text-right whitespace-nowrap last:pr-4">
                        {shortDay(d)}
                      </TableHead>
                    ))}
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {table.rows.map((row) => {
                    const expanded = open === row.key
                    const current = row.latest
                    return (
                      <Fragment key={row.key}>
                        <TableRow
                          className="cursor-pointer"
                          onClick={() => setOpen(expanded ? null : row.key)}
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
                                    onHolder(row.name)
                                  }}
                                  className="text-left font-medium hover:underline"
                                  title="Every company this investor holds"
                                >
                                  {row.name}
                                </button>
                                <div className="flex flex-wrap items-center gap-1">
                                  <StatusBadge status={current?.holder.status ?? null} />
                                  {current?.holder.domicile && (
                                    <span className="text-xs text-muted-foreground">{current.holder.domicile}</span>
                                  )}
                                  {current && current.holder.reportDate !== table.latest && (
                                    <Badge variant="outline" className="text-amber-700 dark:text-amber-300">
                                      Not listed {dayLabel(table.latest)}
                                    </Badge>
                                  )}
                                </div>
                                {(current?.holder.aliases.length ?? 0) > 0 && (
                                  <div className="max-w-[280px] text-xs text-muted-foreground">
                                    Also listed as {current!.holder.aliases.join("; ")}
                                  </div>
                                )}
                              </div>
                            </div>
                          </TableCell>
                          {table.dates.map((d) => {
                            const day = row.byDate.get(d)
                            const h = day?.holder
                            return (
                              <TableCell key={d} className="text-right align-top tabular-nums last:pr-4">
                                {!h ? (
                                  <span className="text-muted-foreground">{EMPTY}</span>
                                ) : h.leftList ? (
                                  <MovementBadge movement="EXITED" />
                                ) : (
                                  <>
                                    <div>{sharesLabel(h.sharesCurrent)}</div>
                                    <div className="text-xs text-muted-foreground">{percentLabel(h.percentCurrent)}</div>
                                    <div className="text-xs">
                                      {h.movement === "ENTERED" ? (
                                        <MovementBadge movement="ENTERED" />
                                      ) : (
                                        <SharesChange value={h.change} />
                                      )}
                                    </div>
                                    {h.movement === "UNCHANGED" && h.accountMoves > 0 && (
                                      <div className="text-[11px] text-muted-foreground" title="Shares moved between its accounts">
                                        <ArrowRightLeftIcon className="inline size-3" /> accounts
                                      </div>
                                    )}
                                  </>
                                )}
                              </TableCell>
                            )
                          })}
                        </TableRow>
                        {expanded && current && (
                          <TableRow className="bg-muted/30 hover:bg-muted/30">
                            <TableCell colSpan={table.dates.length + 1} className="px-4 py-3">
                              <Accounts day={current} />
                            </TableCell>
                          </TableRow>
                        )}
                      </Fragment>
                    )
                  })}
                </TableBody>
              </Table>
            </div>

            {digest.formerHolders.length > 0 && (
              <p className="px-4 text-xs text-muted-foreground">
                No longer listed (below 5%):{" "}
                {digest.formerHolders
                  .map((f) => `${f.name} (last listed ${dayLabel(f.lastListed)}, ${fractionLabel(f.ownershipThen)})`)
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

/** The securities accounts of an investor on a day: broker or custodian, account name, shares before and after. */
function Accounts({ day }: { day: HolderDay }) {
  const h = day.holder
  return (
    <div className="flex flex-col gap-2">
      <p className="text-xs text-muted-foreground">
        {h.holderName}: {day.accounts.length} {day.accounts.length === 1 ? "account" : "accounts"} on{" "}
        {dayLabel(h.reportDate)} (compared with {dayLabel(h.previousDate)})
        {h.nationality ? ` · nationality ${h.nationality}` : ""}
      </p>
      <div className="overflow-x-auto rounded-lg border bg-background">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead className="pl-3">Broker / custodian</TableHead>
              <TableHead>Account</TableHead>
              <TableHead className="text-right">{shortDay(h.previousDate)}</TableHead>
              <TableHead className="text-right">{shortDay(h.reportDate)}</TableHead>
              <TableHead className="pr-3 text-right">Change</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {day.accounts.map((a) => (
              <TableRow key={a.index}>
                <TableCell className="pl-3 text-xs">{a.participant}</TableCell>
                <TableCell className="max-w-[320px] truncate text-xs" title={a.accountName}>
                  {a.accountName}
                </TableCell>
                <TableCell className="text-right text-xs tabular-nums">{sharesLabel(a.sharesPrevious)}</TableCell>
                <TableCell className="text-right text-xs tabular-nums">{sharesLabel(a.sharesCurrent)}</TableCell>
                <TableCell className="pr-3 text-right text-xs tabular-nums">
                  {a.sharesPrevious === null && a.sharesCurrent !== null ? (
                    <span className="text-sky-700 dark:text-sky-300">opened</span>
                  ) : a.sharesCurrent === null && a.sharesPrevious !== null ? (
                    <span className="text-amber-700 dark:text-amber-300">closed</span>
                  ) : (
                    <SharesChange value={a.change} />
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    </div>
  )
}
