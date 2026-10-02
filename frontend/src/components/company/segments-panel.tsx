"use client"

import { useMemo, useState } from "react"

import { EmptyState } from "@/components/empty-state"
import { Badge } from "@/components/ui/badge"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import {
  Table,
  TableBody,
  TableCell,
  TableFooter,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { EMPTY, formatCompact, formatDate, formatPercent, formatScaled, type AmountScale } from "@/lib/format"
import type { CompanyDetail } from "@/lib/types"

export function SegmentsPanel({ detail, scale }: { detail: CompanyDetail; scale: AmountScale }) {
  const currency = detail.company.currency
  const periodsWithSegments = useMemo(
    () => detail.periods.filter((p) => p.segments.length > 0),
    [detail.periods]
  )
  const [periodId, setPeriodId] = useState<string>(() => String(periodsWithSegments[0]?.periodId ?? ""))
  const period = periodsWithSegments.find((p) => String(p.periodId) === periodId) ?? periodsWithSegments[0]

  if (!period) {
    return (
      <Card>
        <CardContent>
          <EmptyState
            variant="chart"
            title="No segment figures"
            description="No revenue breakdown is stored for this company yet."
          />
        </CardContent>
      </Card>
    )
  }

  const total = period.segments.reduce((sum, s) => sum + (s.revenue ?? 0), 0)
  const reportedRevenue = period.incomeStatement?.revenue ?? null
  const items = periodsWithSegments.map((p) => ({
    value: String(p.periodId),
    label: `${p.period} · ${formatDate(p.periodEnd)}`,
  }))

  return (
    <div className="flex flex-col gap-4">
      <Card>
        <CardHeader>
          <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
            <div className="space-y-1">
              <CardTitle>Revenue by segment</CardTitle>
              <CardDescription>
                {period.segments.length} segments in {period.period}; total {formatCompact(total, currency)}
                {reportedRevenue !== null && ` (income statement revenue ${formatCompact(reportedRevenue, currency)})`}
              </CardDescription>
            </div>
            <Select items={items} value={String(period.periodId)} onValueChange={(v) => v && setPeriodId(v)}>
              <SelectTrigger aria-label="Period" className="min-w-[200px]">
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
        </CardHeader>
        <CardContent className="px-0">
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="pl-4">Segment</TableHead>
                  <TableHead className="hidden sm:table-cell">Type</TableHead>
                  <TableHead className="text-right">Revenue</TableHead>
                  <TableHead className="min-w-[160px] pr-4">Share of total</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {period.segments.map((s) => {
                  const share = total && s.revenue !== null ? s.revenue / total : null
                  return (
                    <TableRow key={s.segmentId}>
                      <TableCell className="pl-4">
                        <div className="max-w-[420px] whitespace-normal">
                          <div className="font-medium">{s.segmentNameEn ?? s.segmentName}</div>
                          {s.segmentNameEn && (
                            <div className="text-xs text-muted-foreground">{s.segmentName}</div>
                          )}
                        </div>
                      </TableCell>
                      <TableCell className="hidden sm:table-cell">
                        <Badge variant="secondary">{s.segmentType}</Badge>
                      </TableCell>
                      <TableCell className="text-right tabular-nums">{formatScaled(s.revenue, scale)}</TableCell>
                      <TableCell className="pr-4">
                        <div className="flex items-center gap-2">
                          <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-muted">
                            <div
                              className="h-full rounded-full bg-primary"
                              style={{ width: `${Math.max(0, Math.min(100, (share ?? 0) * 100))}%` }}
                            />
                          </div>
                          <span className="w-14 text-right text-xs tabular-nums">{formatPercent(share, 1)}</span>
                        </div>
                      </TableCell>
                    </TableRow>
                  )
                })}
              </TableBody>
              <TableFooter>
                <TableRow>
                  <TableCell className="pl-4 font-semibold">Total</TableCell>
                  <TableCell className="hidden sm:table-cell" />
                  <TableCell className="text-right font-semibold tabular-nums">{formatScaled(total, scale)}</TableCell>
                  <TableCell className="pr-4 text-xs text-muted-foreground">
                    {reportedRevenue !== null && Math.abs(reportedRevenue - total) < 1
                      ? "Matches reported revenue"
                      : reportedRevenue !== null
                        ? `Reported revenue ${formatScaled(reportedRevenue, scale)}`
                        : EMPTY}
                  </TableCell>
                </TableRow>
              </TableFooter>
            </Table>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Segments</CardTitle>
          <CardDescription>All segments and revenue lines stored for {detail.company.ticker}</CardDescription>
        </CardHeader>
        <CardContent className="px-0">
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="pl-4">Name (filing)</TableHead>
                  <TableHead>English</TableHead>
                  <TableHead className="pr-4">Type</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {detail.segments.map((s) => (
                  <TableRow key={s.segmentId}>
                    <TableCell className="whitespace-normal pl-4">{s.segmentName}</TableCell>
                    <TableCell className="whitespace-normal">{s.segmentNameEn ?? EMPTY}</TableCell>
                    <TableCell className="pr-4">
                      <Badge variant={s.active ? "secondary" : "outline"}>{s.segmentType}</Badge>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        </CardContent>
      </Card>
    </div>
  )
}
