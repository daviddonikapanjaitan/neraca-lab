"use client"

import { ChevronLeftIcon, ChevronRightIcon, ChevronsLeftIcon, ChevronsRightIcon } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { formatNumber } from "@/lib/format"

/** Last page (1-based) of {@code total} rows; 1 for an empty list. */
export function pageCount(total: number, pageSize: number): number {
  return Math.max(1, Math.ceil(total / pageSize))
}

/**
 * Page controls under a table: "Showing 11-20 of 57 jobs", rows per page, "Page 2 of 6" and first / previous /
 * next / last buttons. Pages are 1-based.
 */
export function TablePagination({
  page,
  pageSize,
  pageSizes,
  total,
  noun,
  disabled = false,
  onPageChange,
  onPageSizeChange,
}: {
  page: number
  pageSize: number
  pageSizes: readonly number[]
  total: number
  /** plural of what the rows are ("jobs") */
  noun: string
  /** while a page loads */
  disabled?: boolean
  onPageChange: (page: number) => void
  onPageSizeChange: (pageSize: number) => void
}) {
  const last = pageCount(total, pageSize)
  const first = total === 0 ? 0 : (page - 1) * pageSize + 1
  const end = Math.min(total, page * pageSize)
  const sizeItems = pageSizes.map((size) => ({ value: String(size), label: String(size) }))

  return (
    <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2 text-xs text-muted-foreground">
      <p className="tabular-nums" aria-live="polite">
        {total === 0
          ? `No ${noun}`
          : `Showing ${formatNumber(first, 0)}–${formatNumber(end, 0)} of ${formatNumber(total, 0)} ${noun}`}
      </p>
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
        <div className="flex items-center gap-2">
          <span>Rows per page</span>
          <Select
            items={sizeItems}
            value={String(pageSize)}
            onValueChange={(v) => v && onPageSizeChange(Number(v))}
          >
            <SelectTrigger size="sm" aria-label="Rows per page" className="w-[70px]">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {sizeItems.map((s) => (
                <SelectItem key={s.value} value={s.value}>
                  {s.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <span className="tabular-nums">
          Page {formatNumber(page, 0)} of {formatNumber(last, 0)}
        </span>
        <div className="flex items-center gap-1">
          <Button
            variant="outline"
            size="icon-sm"
            aria-label="First page"
            disabled={disabled || page <= 1}
            onClick={() => onPageChange(1)}
          >
            <ChevronsLeftIcon />
          </Button>
          <Button
            variant="outline"
            size="icon-sm"
            aria-label="Previous page"
            disabled={disabled || page <= 1}
            onClick={() => onPageChange(page - 1)}
          >
            <ChevronLeftIcon />
          </Button>
          <Button
            variant="outline"
            size="icon-sm"
            aria-label="Next page"
            disabled={disabled || page >= last}
            onClick={() => onPageChange(page + 1)}
          >
            <ChevronRightIcon />
          </Button>
          <Button
            variant="outline"
            size="icon-sm"
            aria-label="Last page"
            disabled={disabled || page >= last}
            onClick={() => onPageChange(last)}
          >
            <ChevronsRightIcon />
          </Button>
        </div>
      </div>
    </div>
  )
}
