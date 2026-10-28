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
import type { ExcelStatus } from "@/lib/ownership"
import {
  monthLabel,
  ONE_PERCENT_DOCUMENT_TYPE,
  type OnePercentReportPage,
  OWNERSHIP_ONE_PERCENT_HREF,
} from "@/lib/ownership-one-percent"
import { cn } from "@/lib/utils"

function query(page: number, pageSize: number): string {
  return new URLSearchParams({
    documentType: ONE_PERCENT_DOCUMENT_TYPE,
    limit: String(pageSize),
    offset: String((page - 1) * pageSize),
  }).toString()
}

/**
 * The stored lists of Ownership (>=1%), latest month first, page by page: what each month held (investors, companies,
 * the sheet's rows) and what moved against the stored month before (buys, sells, new above 1%, no longer above 1%,
 * names that changed). The first stored month has nothing to be compared with: it is marked.
 */
export function OnePercentListsCard({ initial, status }: { initial: OnePercentReportPage; status: ExcelStatus }) {
  const [list, setList] = useState(initial)
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(DEFAULT_TABLE_PAGE_SIZE)
  const [error, setError] = useState<string | null>(null)
  const current = query(page, pageSize)
  const [loadedQuery, setLoadedQuery] = useState(() => query(1, DEFAULT_TABLE_PAGE_SIZE))
  /** the query and server data shown: the page is fetched again when either changes (a finished job) */
  const shown = useRef({ query: query(1, DEFAULT_TABLE_PAGE_SIZE), initial })
  const loading = loadedQuery !== current && !error
  const stored = status.stored?.find((s) => s.documentType === ONE_PERCENT_DOCUMENT_TYPE)

  useEffect(() => {
    if (shown.current.query === current && shown.current.initial === initial) return
    let cancelled = false
    requestJson<OnePercentReportPage>(`/api/rag/xlsx/reports?${current}`)
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

  const first = list.reports.filter((r) => r.previousDate === null)

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          Stored ownership lists (&gt;=1%)
          {loading && <LoaderIcon className="size-3.5 animate-spin text-muted-foreground" />}
        </CardTitle>
        <CardDescription>
          KSEI&apos;s monthly lists of the shareholders of more than 1%, latest month first. Buys and sells compare each
          investor&apos;s shares at the month end with the stored month before; a month uploaded late is fitted in
          between and the month after it is compared again.{" "}
          <Link href={OWNERSHIP_ONE_PERCENT_HREF} className="text-primary hover:underline">
            Companies › Ownership (&gt;=1%)
          </Link>
        </CardDescription>
        {stored && (
          <CardAction className="hidden sm:block">
            <Badge variant="outline" className="tabular-nums">
              {formatNumber(stored.reports, 0)} lists
              {stored.latestDate ? ` · latest ${monthLabel(stored.latestDate)}` : ""} ·{" "}
              {formatNumber(stored.documents, 0)} documents in the vector store
            </Badge>
          </CardAction>
        )}
      </CardHeader>
      <CardContent className={cn("flex flex-col gap-3 px-0 transition-opacity", loading && "opacity-60")}>
        {error && (
          <p role="alert" className="flex items-start gap-2 px-4 text-xs text-destructive">
            <CircleAlertIcon className="mt-px size-3.5 shrink-0" />
            <span>Cannot load the stored lists: {error}</span>
          </p>
        )}
        {first.length > 0 && list.total > 1 && (
          <p role="status" className="flex items-start gap-2 px-4 text-xs text-amber-600 dark:text-amber-400">
            <TriangleAlertIcon className="mt-px size-3.5 shrink-0" />
            <span>
              No earlier list is stored for {first.map((g) => monthLabel(g.reportDate)).join(", ")}: its movements are
              unknown until the month before is uploaded.
            </span>
          </p>
        )}
        {list.reports.length === 0 ? (
          <EmptyState
            variant="generic"
            title="No 1% ownership lists stored yet"
            description="Choose IDX and Ownership (>=1%) above and upload KSEI's monthly lists (…-satu-persen.xlsx)."
          />
        ) : (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="pl-4">Month</TableHead>
                  <TableHead>File</TableHead>
                  <TableHead className="text-right">Investors</TableHead>
                  <TableHead className="hidden text-right md:table-cell">Companies</TableHead>
                  <TableHead className="text-right">Buys</TableHead>
                  <TableHead className="text-right">Sells</TableHead>
                  <TableHead className="text-right">New</TableHead>
                  <TableHead className="text-right">No longer</TableHead>
                  <TableHead className="hidden text-right md:table-cell">Names changed</TableHead>
                  <TableHead className="hidden pr-4 text-right lg:table-cell">Stored</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {list.reports.map((r) => {
                  const month = new URLSearchParams({ from: r.reportDate, to: r.reportDate, transaction: "ACTIVITY" })
                  const compared = r.previousDate !== null
                  return (
                    <TableRow key={r.reportId}>
                      <TableCell className="pl-4 whitespace-nowrap">
                        <Link
                          href={`${OWNERSHIP_ONE_PERCENT_HREF}?${month}`}
                          className="inline-flex items-center gap-1 font-medium hover:underline"
                        >
                          {monthLabel(r.reportDate)} <ExternalLinkIcon className="size-3 text-muted-foreground" />
                        </Link>
                        <div className="text-xs text-muted-foreground">
                          {compared ? `vs ${monthLabel(r.previousDate)}` : "first list stored"}
                        </div>
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
                        {formatNumber(r.holders, 0)}
                        {r.sourceRows > r.holders && (
                          <div
                            className="text-xs text-muted-foreground"
                            title="Rows of the same investor (an SID and a scrip account, or its name spelled twice) counted as one"
                          >
                            {formatNumber(r.sourceRows, 0)} rows
                          </div>
                        )}
                      </TableCell>
                      <TableCell className="hidden text-right tabular-nums md:table-cell">
                        {formatNumber(r.issuers, 0)}
                      </TableCell>
                      <TableCell className="text-right text-emerald-600 tabular-nums dark:text-emerald-400">
                        {compared ? formatNumber(r.buys, 0) : EMPTY}
                      </TableCell>
                      <TableCell className="text-right text-rose-600 tabular-nums dark:text-rose-400">
                        {compared ? formatNumber(r.sells, 0) : EMPTY}
                      </TableCell>
                      <TableCell className="text-right tabular-nums">{compared ? formatNumber(r.entered, 0) : EMPTY}</TableCell>
                      <TableCell className="text-right tabular-nums">
                        {r.exited === null ? EMPTY : formatNumber(r.exited, 0)}
                      </TableCell>
                      <TableCell className="hidden text-right tabular-nums md:table-cell">
                        {compared ? (
                          <span title={`${r.renamed} renamed, ${r.moved} blocks of shares under another name`}>
                            {formatNumber(r.renamed + r.moved, 0)}
                          </span>
                        ) : (
                          EMPTY
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
