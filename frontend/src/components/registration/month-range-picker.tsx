"use client"

import { useState } from "react"
import { CalendarRangeIcon, ChevronLeftIcon, ChevronRightIcon, XIcon } from "lucide-react"

import { Button, buttonVariants } from "@/components/ui/button"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import { monthLabel, rangeLabel } from "@/lib/registration"
import { cn } from "@/lib/utils"

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]

/** "2026-05" from a year and a 0-based month */
function key(year: number, month: number): string {
  return `${year}-${String(month + 1).padStart(2, "0")}`
}

/** "2026-05" -> 2026 */
function yearOf(month: string): number {
  return Number(month.slice(0, 4))
}

/** The month n months before "2026-08" (n = 2: "2026-06"). */
export function monthsBefore(month: string, n: number): string {
  const year = yearOf(month)
  const index = year * 12 + Number(month.slice(5, 7)) - 1 - n
  return key(Math.floor(index / 12), ((index % 12) + 12) % 12)
}

/**
 * A month range calendar: a year of months to click, the first click the first month, the second the last (in
 * either order; the same month twice is that month alone); presets for the latest months; Clear for every month.
 * Months after {@code latest} are not offered (no report can exist for them).
 */
export function MonthRangePicker({
  from,
  to,
  onChange,
  latest,
  reportsFrom,
  reportsTo,
  disabled,
}: {
  from: string | null
  to: string | null
  onChange: (from: string | null, to: string | null) => void
  /** the latest month that can be chosen ("2026-10": the current month) */
  latest: string
  /** the first and last month with stored reports, shown as a hint and used by the presets */
  reportsFrom?: string | null
  reportsTo?: string | null
  disabled?: boolean
}) {
  const [open, setOpen] = useState(false)
  const [year, setYear] = useState(() => yearOf(to ?? from ?? reportsTo ?? latest))
  /** the first month clicked, waiting for the second */
  const [start, setStart] = useState<string | null>(null)
  const [hover, setHover] = useState<string | null>(null)

  const anchor = reportsTo && reportsTo <= latest ? reportsTo : latest
  const latestYear = yearOf(latest)

  // the range drawn: the one being chosen (first click and the hovered month), else the chosen one
  const [lo, hi] = start
    ? [start, hover ?? start].sort()
    : [from ?? (to ? "0000-01" : null), to ?? (from ? "9999-12" : null)]

  function changeOpen(next: boolean) {
    setOpen(next)
    if (next) {
      setYear(yearOf(to ?? from ?? anchor))
    }
    setStart(null)
    setHover(null)
  }

  function pick(month: string) {
    if (!start) {
      setStart(month)
      return
    }
    const [a, b] = [start, month].sort()
    onChange(a, b)
    setStart(null)
    setHover(null)
    setOpen(false)
  }

  function preset(months: number) {
    onChange(monthsBefore(anchor, months - 1), anchor)
    setStart(null)
    setOpen(false)
  }

  return (
    <Popover open={open} onOpenChange={changeOpen}>
      <PopoverTrigger
        disabled={disabled}
        aria-label="Months"
        className={cn(
          buttonVariants({ variant: "outline" }),
          "w-full justify-start gap-2 font-normal",
          !from && !to && "text-muted-foreground"
        )}
      >
        <CalendarRangeIcon className="size-4 shrink-0" />
        <span className="truncate">{rangeLabel(from, to)}</span>
      </PopoverTrigger>
      <PopoverContent className="w-72">
        <div className="flex items-center justify-between gap-2">
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            aria-label="Previous year"
            onClick={() => setYear(year - 1)}
          >
            <ChevronLeftIcon />
          </Button>
          <span className="text-sm font-medium tabular-nums">{year}</span>
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            aria-label="Next year"
            onClick={() => setYear(year + 1)}
            disabled={year >= latestYear}
          >
            <ChevronRightIcon />
          </Button>
        </div>

        <div className="mt-2 grid grid-cols-3 gap-1" onMouseLeave={() => setHover(null)}>
          {MONTHS.map((name, i) => {
            const month = key(year, i)
            const future = month > latest
            const inRange = lo !== null && hi !== null && month >= lo && month <= hi
            const edge = month === lo || month === hi
            return (
              <button
                key={month}
                type="button"
                disabled={future}
                aria-pressed={inRange}
                aria-label={monthLabel(month)}
                onClick={() => pick(month)}
                onMouseEnter={() => start && setHover(month)}
                className={cn(
                  "rounded-md px-2 py-1.5 text-sm tabular-nums transition-colors hover:bg-muted disabled:pointer-events-none disabled:opacity-40",
                  inRange && "bg-primary/15 text-foreground",
                  edge && "bg-primary text-primary-foreground hover:bg-primary/90"
                )}
              >
                {name}
              </button>
            )
          })}
        </div>

        <p className="mt-2 min-h-4 text-xs text-muted-foreground">
          {start
            ? `From ${monthLabel(start)}: choose the last month`
            : reportsFrom && reportsTo
              ? `Reports stored: ${rangeLabel(reportsFrom, reportsTo)}`
              : "Choose the first month, then the last"}
        </p>

        <div className="mt-2 flex flex-wrap gap-1">
          {[3, 6, 12].map((n) => (
            <Button key={n} type="button" variant="outline" size="xs" onClick={() => preset(n)}>
              Last {n} months
            </Button>
          ))}
          <Button
            type="button"
            variant="ghost"
            size="xs"
            onClick={() => {
              onChange(null, null)
              setStart(null)
              setOpen(false)
            }}
          >
            <XIcon />
            Clear
          </Button>
        </div>
      </PopoverContent>
    </Popover>
  )
}
