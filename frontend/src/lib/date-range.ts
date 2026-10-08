// Date ranges of the news RAG ingestion: ISO dates (yyyy-mm-dd) in Jakarta time, as the backend checks them.

export type RangePreset = "this-month" | "last-7" | "last-30" | "previous-month" | "custom"

export const RANGE_PRESETS: { value: RangePreset; label: string }[] = [
  { value: "this-month", label: "This month" },
  { value: "last-7", label: "Last 7 days" },
  { value: "last-30", label: "Last 30 days" },
  { value: "previous-month", label: "Previous month" },
  { value: "custom", label: "Custom range" },
]

/** Longest range of one news job (backend RagController.MAX_RANGE_DAYS). */
export const MAX_RANGE_DAYS = 366

/** Today in Jakarta, "2026-10-08". */
export function todayJakarta(now: Date = new Date()): string {
  // en-CA formats as yyyy-mm-dd
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Jakarta",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(now)
}

function parse(iso: string): Date {
  const [y, m, d] = iso.split("-").map(Number)
  return new Date(Date.UTC(y, m - 1, d))
}

function format(date: Date): string {
  return date.toISOString().slice(0, 10)
}

export function addDays(iso: string, days: number): string {
  const date = parse(iso)
  date.setUTCDate(date.getUTCDate() + days)
  return format(date)
}

/** Days from `from` to `to`, both included. */
export function daysInRange(from: string, to: string): number {
  return Math.round((parse(to).getTime() - parse(from).getTime()) / 86_400_000) + 1
}

/** The range of a preset ending today (Jakarta); null for a custom range. */
export function presetRange(preset: RangePreset, today: string): { from: string; to: string } | null {
  const date = parse(today)
  switch (preset) {
    case "this-month":
      return { from: format(new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), 1))), to: today }
    case "last-7":
      return { from: addDays(today, -6), to: today }
    case "last-30":
      return { from: addDays(today, -29), to: today }
    case "previous-month":
      return {
        from: format(new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth() - 1, 1))),
        to: format(new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), 0))),
      }
    case "custom":
      return null
  }
}

const ISO = /^\d{4}-\d{2}-\d{2}$/

/** Why a range cannot be requested, or null when it can. */
export function rangeProblem(from: string, to: string, today: string): string | null {
  if (!ISO.test(from) || !ISO.test(to)) return "Choose a start and an end date."
  if (from > to) return "The start date is after the end date."
  if (to > today) return "The end date is in the future."
  if (daysInRange(from, to) > MAX_RANGE_DAYS) return `The range is longer than ${MAX_RANGE_DAYS} days.`
  return null
}
