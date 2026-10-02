"use client"

import { EmptyState } from "@/components/empty-state"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { formatCompact, formatDate, formatMultiple, formatNumber, formatPercent } from "@/lib/format"
import type { Valuation } from "@/lib/types"
import { cn } from "@/lib/utils"

type Column = {
  label: string
  title: string
  render: (v: Valuation) => string
  /** value whose sign colours the cell (negative = red) */
  numeric?: (v: Valuation) => number | null
}

const COLUMNS: Column[] = [
  { label: "Price", title: "Last close on or before the valuation date", render: (v) => formatNumber(v.sharePrice, 2) },
  { label: "Market cap", title: "Price x shares outstanding", render: (v) => formatCompact(v.marketCap) },
  { label: "EV", title: "Market cap + debt incl. leases - cash and investments + NCI", render: (v) => formatCompact(v.enterpriseValue) },
  { label: "Revenue TTM", title: "Trailing twelve months revenue", render: (v) => formatCompact(v.revenueTtm) },
  { label: "EPS TTM", title: "TTM profit to parent / shares outstanding", render: (v) => formatNumber(v.epsTtm, 2) },
  { label: "P/E", title: "Price / EPS TTM", render: (v) => formatMultiple(v.peRatio) },
  { label: "P/B", title: "Market cap / equity attributable to the parent", render: (v) => formatMultiple(v.pbRatio) },
  { label: "P/S", title: "Market cap / revenue TTM", render: (v) => formatMultiple(v.psRatio) },
  { label: "EV/EBITDA", title: "EV / EBITDA TTM", render: (v) => formatMultiple(v.evEbitda) },
  { label: "EV/Sales", title: "EV / revenue TTM", render: (v) => formatMultiple(v.evSales) },
  { label: "EV/OP", title: "EV / operating profit TTM", render: (v) => formatMultiple(v.evOp) },
  { label: "FCF yield", title: "FCF TTM / market cap", render: (v) => formatPercent(v.fcfYield), numeric: (v) => v.fcfYield },
  { label: "Earnings yield", title: "EPS TTM / price", render: (v) => formatPercent(v.earningsYield), numeric: (v) => v.earningsYield },
]

export function ValuationsTable({ valuations, currency }: { valuations: Valuation[]; currency: string | null }) {
  if (valuations.length === 0) {
    return (
      <EmptyState
        variant="chart"
        title="No valuations yet"
        description="Valuations need daily prices and share counts; none are stored for this company yet."
      />
    )
  }

  return (
    <div className="overflow-x-auto">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className="sticky left-0 z-10 bg-card pl-4">Date</TableHead>
            <TableHead>Based on</TableHead>
            {COLUMNS.map((c) => (
              <TableHead key={c.label} className="text-right last:pr-4" title={c.title}>
                {c.label}
              </TableHead>
            ))}
          </TableRow>
        </TableHeader>
        <TableBody>
          {valuations.map((v) => (
            <TableRow key={v.valuationDate}>
              <TableCell className="sticky left-0 z-10 bg-card pl-4 font-medium tabular-nums">
                {formatDate(v.valuationDate)}
              </TableCell>
              <TableCell className="font-mono text-xs">{v.period ?? "—"}</TableCell>
              {COLUMNS.map((c) => {
                const n = c.numeric ? c.numeric(v) : null
                return (
                  <TableCell
                    key={c.label}
                    className={cn(
                      "text-right tabular-nums last:pr-4",
                      n !== null && n < 0 && "text-rose-600 dark:text-rose-400"
                    )}
                  >
                    {c.render(v)}
                  </TableCell>
                )
              })}
            </TableRow>
          ))}
        </TableBody>
      </Table>
      <p className="px-4 pt-3 text-xs text-muted-foreground">
        Amounts in {currency ?? "the company currency"}. Hover a column header for its definition.
      </p>
    </div>
  )
}
