"use client"

import { useEffect, useRef, useState } from "react"
import Link from "next/link"
import { CircleAlertIcon, ExternalLinkIcon, LoaderIcon, TriangleAlertIcon } from "lucide-react"

import { EmptyState } from "@/components/empty-state"
import { pageCount, TablePagination } from "@/components/table-pagination"
import { Badge } from "@/components/ui/badge"
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { requestJson } from "@/lib/client-api"
import { EMPTY, formatNumber, formatTimestamp } from "@/lib/format"
import { DEFAULT_TABLE_PAGE_SIZE, TABLE_PAGE_SIZES } from "@/lib/ingestion"
import { dayLabel, type ExcelStatus, OWNERSHIP_HREF, type OwnershipReportPage } from "@/lib/ownership"
import { cn } from "@/lib/utils"

function query(page: number, pageSize: number): string {
  return new URLSearchParams({ limit: String(pageSize), offset: String((page - 1) * pageSize) }).toString()
}

/**
 * The stored lists of Ownership (>=5%), latest day first, page by page: what each day held (investors, companies,
 * accounts, the same investor under another name) and what moved (buys, sells, investors crossing 5% and falling
 * below it). A day whose day before is not stored cannot tell who fell below 5%: it is marked, so the missing list
 * can be uploaded.
 */
export function OwnershipListsCard({ initial, status }: { initial: OwnershipReportPage; status: ExcelStatus }) {
  const [list, setList] = useState(initial)
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(DEFAULT_TABLE_PAGE_SIZE)
  const [error, setError] = useState<string | null>(null)
  const current = query(page, pageSize)
  const [loadedQuery, setLoadedQuery] = useState(() => query(1, DEFAULT_TABLE_PAGE_SIZE))
  /** the query and server data shown: the page is fetched again when either changes (a finished job) */
  const shown = useRef({ query: query(1, DEFAULT_TABLE_PAGE_SIZE), initial })
  const loading = loadedQuery !== current && !error

  useEffect(() => {
    if (shown.current.query === current && shown.current.initial === initial) return
    let cancelled = false
    requestJson<OwnershipReportPage>(`/api/rag/xlsx/reports?${current}`)
      .then(({ body }) => {
        if (cancelled) return
        const last = pageCount(body.total, pageSize)
        if (page > last) {
          setPage(last)
          return
        }
        shown.current = { query: current, initial }
        setList(body)
        setLoadedQuery(current)
        setError(null)
      })
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : String(e)))
    return () => {
      cancelled = true
    }
  }, [current, initial, page, pageSize])

  const gaps = list.reports.filter((r) => r.exited === null)

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          Stored ownership lists (&gt;=5%)
          {loading && <LoaderIcon className="size-3.5 animate-spin text-muted-foreground" />}
        </CardTitle>
        <CardDescription>
          KSEI&apos;s daily lists of the shareholders of more than 5%, latest day first. Buys and sells compare each
          investor&apos;s combined shares with the trading day before; an investor that fell below 5% is the one missing
          from the day&apos;s list.{" "}
          <Link href={OWNERSHIP_HREF} className="text-primary hover:underline">
            Companies › Ownership (&gt;=5%)
          </Link>
        </CardDescription>
        <CardAction className="hidden sm:block">
          <Badge variant="outline" className="tabular-nums">
            {formatNumber(status.reports, 0)} lists
            {status.latestDate ? ` · latest ${dayLabel(status.latestDate)}` : ""} · {formatNumber(status.documents, 0)}{" "}
            documents in the vector store
          </Badge>
        </CardAction>
      </CardHeader>
      <CardContent className={cn("flex flex-col gap-3 px-0 transition-opacity", loading && "opacity-60")}>
        {error && (
          <p role="alert" className="flex items-start gap-2 px-4 text-xs text-destructive">
            <CircleAlertIcon className="mt-px size-3.5 shrink-0" />
            <span>Cannot load the stored lists: {error}</span>
          </p>
        )}
        {gaps.length > 0 && (
          <p role="status" className="flex items-start gap-2 px-4 text-xs text-amber-600 dark:text-amber-400">
            <TriangleAlertIcon className="mt-px size-3.5 shrink-0" />
            <span>
              The list of the day before is not stored for{" "}
              {gaps.map((g) => `${dayLabel(g.reportDate)} (upload ${dayLabel(g.previousDate)})`).join(", ")}: who fell
              below 5% on {gaps.length === 1 ? "that day" : "those days"} is unknown.
            </span>
          </p>
        )}
        {list.reports.length === 0 ? (
          <EmptyState
            variant="generic"
            title="No ownership lists stored yet"
            description="Choose IDX and Ownership (>=5%) above and upload KSEI's daily lists (…-lima-persen.xlsx)."
          />
        ) : (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="pl-4">Date</TableHead>
                  <TableHead>File</TableHead>
                  <TableHead className="text-right">Investors</TableHead>
                  <TableHead className="hidden text-right md:table-cell">Companies</TableHead>
                  <TableHead className="text-right">Buys</TableHead>
                  <TableHead className="text-right">Sells</TableHead>
                  <TableHead className="text-right">Crossed 5%</TableHead>
                  <TableHead className="text-right">Below 5%</TableHead>
                  <TableHead className="hidden pr-4 text-right lg:table-cell">Stored</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {list.reports.map((r) => {
                  const day = new URLSearchParams({ from: r.reportDate, to: r.reportDate, transaction: "ACTIVITY" })
                  return (
                    <TableRow key={r.reportId}>
                      <TableCell className="pl-4 whitespace-nowrap">
                        <Link href={`${OWNERSHIP_HREF}?${day}`} className="inline-flex items-center gap-1 font-medium hover:underline">
                          {dayLabel(r.reportDate)} <ExternalLinkIcon className="size-3 text-muted-foreground" />
                        </Link>
                        <div className="text-xs text-muted-foreground">vs {dayLabel(r.previousDate)}</div>
                      </TableCell>
                      <TableCell>
                        <div className="max-w-[260px] truncate text-sm" title={r.fileName ?? undefined}>
                          {r.fileName ?? EMPTY}
                        </div>
                        <div className="flex flex-wrap items-center gap-1 text-xs text-muted-foreground">
                          {r.announcementNumber && <span>Announcement {r.announcementNumber}</span>}
                          {r.warnings.length > 0 && (
                            <Badge variant="outline" className="text-amber-600" title={r.warnings.join("\n")}>
                              {r.warnings.length} {r.warnings.length === 1 ? "warning" : "warnings"}
                            </Badge>
                          )}
                          {r.notes.length > 0 && (
                            <Badge variant="outline" title={r.notes.join("\n")}>
                              {r.notes.length} {r.notes.length === 1 ? "note" : "notes"}
                            </Badge>
                          )}
                        </div>
                      </TableCell>
                      <TableCell className="text-right tabular-nums">
                        {formatNumber(r.holders - r.aliases, 0)}
                        {r.aliases > 0 && (
                          <div className="text-xs text-muted-foreground" title="The same investor (the same accounts) listed again under another name">
                            +{formatNumber(r.aliases, 0)} aliases
                          </div>
                        )}
                      </TableCell>
                      <TableCell className="hidden text-right tabular-nums md:table-cell">
                        {formatNumber(r.issuers, 0)}
                        <div className="text-xs text-muted-foreground">{formatNumber(r.accounts, 0)} accounts</div>
                      </TableCell>
                      <TableCell className="text-right text-emerald-600 tabular-nums dark:text-emerald-400">
                        {formatNumber(r.buys, 0)}
                      </TableCell>
                      <TableCell className="text-right text-rose-600 tabular-nums dark:text-rose-400">
                        {formatNumber(r.sells, 0)}
                      </TableCell>
                      <TableCell className="text-right tabular-nums">{formatNumber(r.entered, 0)}</TableCell>
                      <TableCell className="text-right tabular-nums">
                        {r.exited === null ? (
                          <span className="text-muted-foreground" title={`The list of ${dayLabel(r.previousDate)} is not stored`}>
                            ?
                          </span>
                        ) : (
                          formatNumber(r.exited, 0)
                        )}
                      </TableCell>
                      <TableCell className="hidden pr-4 text-right text-xs text-muted-foreground lg:table-cell">
                        {formatTimestamp(r.updatedAt)}
                      </TableCell>
                    </TableRow>
                  )
                })}
              </TableBody>
            </Table>
          </div>
        )}
        {list.total > 0 && (
          <div className="px-4">
            <TablePagination
              page={page}
              pageSize={pageSize}
              pageSizes={TABLE_PAGE_SIZES}
              total={list.total}
              noun="lists"
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
  )
}
