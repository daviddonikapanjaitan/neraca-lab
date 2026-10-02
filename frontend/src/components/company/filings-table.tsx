"use client"

import { CheckIcon, MinusIcon } from "lucide-react"

import { AuditBadge } from "@/components/company/statement-table"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { EMPTY, formatDate } from "@/lib/format"
import { PERIOD_TYPE_LABEL } from "@/lib/statements"
import type { Period } from "@/lib/types"

function Has({ present, label }: { present: boolean; label: string }) {
  return present ? (
    <CheckIcon className="mx-auto size-4 text-emerald-600 dark:text-emerald-400" aria-label={`${label} stored`} />
  ) : (
    <MinusIcon className="mx-auto size-4 text-muted-foreground/40" aria-label={`No ${label}`} />
  )
}

/** Every reporting period with its provenance and which data exists for it. */
export function FilingsTable({ periods }: { periods: Period[] }) {
  return (
    <div className="overflow-x-auto">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className="pl-4">Period</TableHead>
            <TableHead>Start</TableHead>
            <TableHead>End</TableHead>
            <TableHead>Status</TableHead>
            <TableHead className="text-center">Income</TableHead>
            <TableHead className="text-center">Balance</TableHead>
            <TableHead className="text-center">Cash flow</TableHead>
            <TableHead className="text-right">Segments</TableHead>
            <TableHead className="text-right">Metrics</TableHead>
            <TableHead className="pr-4">Source filing</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {periods.map((p) => (
            <TableRow key={p.periodId}>
              <TableCell className="pl-4">
                <div className="font-mono font-medium">{p.period}</div>
                <div className="text-xs text-muted-foreground">{PERIOD_TYPE_LABEL[p.periodType] ?? p.periodType}</div>
              </TableCell>
              <TableCell className="tabular-nums">{formatDate(p.periodStart)}</TableCell>
              <TableCell className="tabular-nums">{formatDate(p.periodEnd)}</TableCell>
              <TableCell><AuditBadge audited={p.audited} /></TableCell>
              <TableCell><Has present={p.incomeStatement !== null} label="income statement" /></TableCell>
              <TableCell><Has present={p.balanceSheet !== null} label="balance sheet" /></TableCell>
              <TableCell><Has present={p.cashFlowStatement !== null} label="cash flow statement" /></TableCell>
              <TableCell className="text-right tabular-nums">{p.segments.length}</TableCell>
              <TableCell className="text-right tabular-nums">{Object.keys(p.metrics).length}</TableCell>
              <TableCell className="pr-4 font-mono text-xs text-muted-foreground">{p.sourceFiling ?? EMPTY}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}
