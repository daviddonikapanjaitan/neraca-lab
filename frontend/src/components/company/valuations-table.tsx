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
import type { DateConversion } from "@/lib/currency"
import { EMPTY, formatCompact, formatDate, formatMultiple, formatNumber, formatPercent, formatPrice } from "@/lib/format"
import type { Valuation } from "@/lib/types"
import { cn } from "@/lib/utils"

/**
 * The factor of a row's amounts: 1 as reported; with the USD | IDR toggle the close of the valuation date (null: no
 * rate stored - the row shows no amounts, never unconverted ones). Ratios and yields need no factor: every amount of a
 * row is converted at the same rate, so they are the same in either currency.
 */
type Factor = number | null

function amount(value: number | null, k: Factor): number | null {
  return value === null || k === null ? null : value * k
}

type Column = {
  label: string
  title: string
  render: (v: Valuation, k: Factor) => string
  /** value whose sign colours the cell (negative = red) */
  numeric?: (v: Valuation, k: Factor) => number | null
}

const COLUMNS: Column[] = [
  { label: "Price", title: "Last close on or before the valuation date", render: (v, k) => formatPrice(amount(v.sharePrice, k)) },
  { label: "Market cap", title: "Price x shares outstanding", render: (v, k) => formatCompact(amount(v.marketCap, k)) },
  {
    label: "EV",
    title: "Market cap + debt incl. leases - cash and investments + NCI",
    render: (v, k) => formatCompact(amount(v.enterpriseValue, k)),
  },
  { label: "Revenue TTM", title: "Trailing twelve months revenue", render: (v, k) => formatCompact(amount(v.revenueTtm, k)) },
  { label: "EPS TTM", title: "TTM profit to parent / shares outstanding", render: (v, k) => formatPrice(amount(v.epsTtm, k)) },
  {
    label: "Forward EPS",
    title: "Profit to parent of the period made a year (Q1 x 4, H1 x 2, 9M x 4/3, FY x 1) / shares outstanding",
    render: (v, k) => formatPrice(amount(v.forwardEps, k)),
    numeric: (v) => v.forwardEps,
  },
  { label: "P/E", title: "Price / EPS TTM", render: (v) => formatMultiple(v.peRatio) },
  {
    label: "BVPS",
    title: "Equity attributable to the parent / shares outstanding",
    render: (v, k) => formatPrice(amount(v.bookValuePerShare, k)),
  },
  { label: "P/B", title: "Market cap / equity attributable to the parent", render: (v) => formatMultiple(v.pbRatio) },
  { label: "P/S", title: "Market cap / revenue TTM", render: (v) => formatMultiple(v.psRatio) },
  {
    label: "EV/EBITDA",
    title:
      "EV / EBITDA TTM; marked FY when the trailing twelve months are not derivable (the comparative interim period has no depreciation): EV / that full year's EBITDA",
    render: (v) =>
      v.evEbitda !== null && v.ebitdaBasis && v.ebitdaBasis !== "TTM"
        ? `${formatMultiple(v.evEbitda)} (${v.ebitdaBasis})`
        : formatMultiple(v.evEbitda),
  },
  { label: "EV/Sales", title: "EV / revenue TTM", render: (v) => formatMultiple(v.evSales) },
  { label: "EV/OP", title: "EV / operating profit TTM", render: (v) => formatMultiple(v.evOp) },
  { label: "FCF yield", title: "FCF TTM / market cap", render: (v) => formatPercent(v.fcfYield), numeric: (v) => v.fcfYield },
  { label: "Earnings yield", title: "EPS TTM / price", render: (v) => formatPercent(v.earningsYield), numeric: (v) => v.earningsYield },
]

/** The price as quoted on the exchange, for a company whose prices are stored converted (a USD reporter on IDX). */
const QUOTE: Column = {
  label: "Quoted price",
  title: "Last close on or before the valuation date as quoted on the exchange",
  render: (v) => (v.quotePrice === null ? EMPTY : `${v.quoteCurrency} ${formatPrice(v.quotePrice)}`),
}

export function ValuationsTable({
  valuations,
  currency,
  conversion = null,
}: {
  valuations: Valuation[]
  currency: string | null
  /** USD | IDR toggle on IDR: every amount of a row at the USD/IDR close of its valuation date */
  conversion?: DateConversion | null
}) {
  const quoted = valuations.find((v) => v.quotePrice !== null)
  const rateColumn: Column | null = conversion
    ? {
        label: `${conversion.pair} rate`,
        title: `The ${conversion.pair} close on or before the valuation date (Forex); every amount of the row is converted at it`,
        render: (v) => {
          const r = conversion.rate(v.valuationDate)
          return r ? formatNumber(r.rate, 2) : EMPTY
        },
      }
    : null
  const columns = [...(quoted ? [QUOTE] : []), ...COLUMNS, ...(rateColumn ? [rateColumn] : [])]
  const shown = conversion ? conversion.currency : currency
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
            {columns.map((c) => (
              <TableHead key={c.label} className="text-right last:pr-4" title={c.title}>
                {c.label}
              </TableHead>
            ))}
          </TableRow>
        </TableHeader>
        <TableBody>
          {valuations.map((v) => {
            const k: Factor = conversion ? (conversion.rate(v.valuationDate)?.rate ?? null) : 1
            return (
              <TableRow key={v.valuationDate}>
                <TableCell className="sticky left-0 z-10 bg-card pl-4 font-medium tabular-nums">
                  {formatDate(v.valuationDate)}
                </TableCell>
                <TableCell className="font-mono text-xs">{v.period ?? "—"}</TableCell>
                {columns.map((c) => {
                  const n = c.numeric ? c.numeric(v, k) : null
                  return (
                    <TableCell
                      key={c.label}
                      className={cn(
                        "text-right tabular-nums last:pr-4",
                        n !== null && n < 0 && "text-rose-600 dark:text-rose-400"
                      )}
                    >
                      {c.render(v, k)}
                    </TableCell>
                  )
                })}
              </TableRow>
            )
          })}
        </TableBody>
      </Table>
      <p className="px-4 pt-3 text-xs text-muted-foreground">
        Amounts in {shown ?? "the company currency"}
        {conversion
          ? `, converted from ${currency ?? "the reporting currency"} at the ${conversion.pair} close on or before each valuation date (Forex); ratios and yields are the same in either currency, and the quoted price is the exchange's own`
          : quoted
            ? `, also the price: the ${quoted.quoteCurrency} quote converted at the rate of the day before`
            : ""}
        . Hover a column header for its definition.
      </p>
    </div>
  )
}
