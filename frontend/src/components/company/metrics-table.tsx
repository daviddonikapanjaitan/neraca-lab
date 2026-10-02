"use client"

import { Fragment, useMemo } from "react"

import { PeriodHead } from "@/components/company/statement-table"
import { EmptyState } from "@/components/empty-state"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { formatMetric, metricLabel, titleCase } from "@/lib/format"
import type { Period } from "@/lib/types"
import { cn } from "@/lib/utils"

const CATEGORY_ORDER = ["PROFITABILITY", "LIQUIDITY", "LEVERAGE", "EFFICIENCY", "CASH_FLOW", "BALANCE_SHEET", "PER_SHARE"]

/** Fundamental metrics (financial_metric) grouped by category, metrics x periods. */
export function MetricsTable({ periods }: { periods: Period[] }) {
  const shown = useMemo(() => periods.filter((p) => Object.keys(p.metrics).length > 0), [periods])

  const groups = useMemo(() => {
    const byCategory = new Map<string, Map<string, string | null>>()
    for (const p of shown) {
      for (const [name, metric] of Object.entries(p.metrics)) {
        const category = metric.category ?? "OTHER"
        if (!byCategory.has(category)) byCategory.set(category, new Map())
        byCategory.get(category)!.set(name, metric.unit)
      }
    }
    const rank = (c: string) => (CATEGORY_ORDER.indexOf(c) === -1 ? CATEGORY_ORDER.length : CATEGORY_ORDER.indexOf(c))
    return Array.from(byCategory.entries())
      .sort(([a], [b]) => rank(a) - rank(b) || a.localeCompare(b))
      .map(([category, names]) => ({
        category,
        names: Array.from(names.keys()).sort((a, b) => metricLabel(a).localeCompare(metricLabel(b))),
      }))
  }, [shown])

  if (shown.length === 0) {
    return (
      <EmptyState
        variant="chart"
        title="No metrics"
        description="No calculated metrics are stored for the selected periods."
      />
    )
  }

  return (
    <div className="overflow-x-auto">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className="sticky left-0 z-10 min-w-[240px] bg-card pl-4">Metric</TableHead>
            {shown.map((p) => (
              <PeriodHead key={p.periodId} period={p} className="last:pr-4" />
            ))}
          </TableRow>
        </TableHeader>
        <TableBody>
          {groups.map(({ category, names }) => (
            <Fragment key={category}>
              <TableRow className="hover:bg-transparent">
                <TableCell
                  colSpan={shown.length + 1}
                  className="bg-muted/60 pl-4 text-xs font-semibold uppercase tracking-wide text-muted-foreground"
                >
                  {titleCase(category)}
                </TableCell>
              </TableRow>
              {names.map((name) => (
                <TableRow key={name}>
                  <TableCell className="sticky left-0 z-10 bg-card pl-4">{metricLabel(name)}</TableCell>
                  {shown.map((p) => {
                    const metric = p.metrics[name]
                    const value = metric?.value ?? null
                    return (
                      <TableCell
                        key={p.periodId}
                        className={cn(
                          "text-right tabular-nums last:pr-4",
                          value !== null && value < 0 && "text-rose-600 dark:text-rose-400"
                        )}
                      >
                        {formatMetric(value, metric?.unit)}
                      </TableCell>
                    )
                  })}
                </TableRow>
              ))}
            </Fragment>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}
