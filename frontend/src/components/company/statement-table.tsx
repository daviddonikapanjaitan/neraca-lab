"use client"

import { Badge } from "@/components/ui/badge"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { formatDate, formatPrice, formatScaled, type AmountScale } from "@/lib/format"
import type { LineItem } from "@/lib/statements"
import type { Period } from "@/lib/types"
import { cn } from "@/lib/utils"

/** Period column header: "2026 H1", end date, audit status. */
export function PeriodHead({ period, className }: { period: Period; className?: string }) {
  return (
    <TableHead className={cn("min-w-[124px] text-right align-bottom", className)}>
      <div className="flex flex-col items-end gap-0.5 py-1.5">
        <span className="font-mono text-xs font-semibold text-foreground">{period.period}</span>
        <span className="text-[11px] font-normal text-muted-foreground">{formatDate(period.periodEnd)}</span>
        <AuditBadge audited={period.audited} />
      </div>
    </TableHead>
  )
}

export function AuditBadge({ audited }: { audited: boolean | null }) {
  if (audited === null) return <Badge variant="ghost" className="h-4 px-1.5 text-[10px]">Unknown</Badge>
  return audited ? (
    <Badge variant="secondary" className="h-4 px-1.5 text-[10px]">Audited</Badge>
  ) : (
    <Badge variant="outline" className="h-4 px-1.5 text-[10px] font-normal text-muted-foreground">Unaudited</Badge>
  )
}

/**
 * One statement as line items x periods. `pick` returns the statement of a period; periods
 * without it are not passed in. Amounts follow the database sign convention.
 */
export function StatementTable<T>({
  items,
  periods,
  pick,
  scale,
}: {
  items: LineItem<T>[]
  periods: Period[]
  pick: (period: Period) => T | null
  scale: AmountScale
}) {
  // hide line items that no shown period reports (e.g. R&D for most IDX companies)
  const visible = items.filter((item) =>
    periods.some((p) => {
      const value = pick(p)?.[item.key]
      return value !== null && value !== undefined
    })
  )

  return (
    <div className="overflow-x-auto">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className="sticky left-0 z-10 min-w-[220px] bg-card pl-4">Line item</TableHead>
            {periods.map((p) => (
              <PeriodHead key={p.periodId} period={p} className="last:pr-4" />
            ))}
          </TableRow>
        </TableHeader>
        <TableBody>
          {visible.map((item) => (
            <TableRow key={String(item.key)} className={cn(item.total && "bg-muted/40 font-medium")}>
              <TableCell
                className={cn(
                  "sticky left-0 z-10 bg-card pl-4",
                  item.total && "bg-muted font-semibold",
                  item.indent && "pl-8 text-muted-foreground"
                )}
              >
                {item.label}
              </TableCell>
              {periods.map((p) => {
                const value = pick(p)?.[item.key] as number | null | undefined
                return (
                  <TableCell
                    key={p.periodId}
                    className={cn(
                      "text-right tabular-nums last:pr-4",
                      typeof value === "number" && value < 0 && "text-rose-600 dark:text-rose-400"
                    )}
                  >
                    {item.unscaled ? formatPrice(value) : formatScaled(value, scale)}
                  </TableCell>
                )
              })}
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}
