// Number and date formatting. A fixed locale keeps server and client output identical (no
// hydration mismatches). Missing values render as an em dash.

export const EMPTY = "—"

const LOCALE = "en-US"
const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]

function isNumber(value: number | null | undefined): value is number {
  return value !== null && value !== undefined && Number.isFinite(value)
}

/** 1234567.891 -> "1,234,567.89" */
export function formatNumber(value: number | null | undefined, maximumFractionDigits = 2): string {
  if (!isNumber(value)) return EMPTY
  return value.toLocaleString(LOCALE, { maximumFractionDigits, minimumFractionDigits: 0 })
}

/**
 * Share price: 2 decimals, 6 below 1 so that prices converted into a reporting currency stay
 * readable (INDY trades in IDR but reports in USD: 0.14666667 -> "0.146667", not "0.15").
 */
export function formatPrice(value: number | null | undefined): string {
  if (!isNumber(value)) return EMPTY
  return formatNumber(value, Math.abs(value) >= 1 ? 2 : 6)
}

/** 33806129682661 -> "33.81T" (with currency: "IDR 33.81T") */
export function formatCompact(value: number | null | undefined, currency?: string | null): string {
  if (!isNumber(value)) return EMPTY
  const text = value.toLocaleString(LOCALE, { notation: "compact", maximumFractionDigits: 2 })
  return currency ? `${currency} ${text}` : text
}

/** 0.0369 -> "3.69%" */
export function formatPercent(value: number | null | undefined, maximumFractionDigits = 2): string {
  if (!isNumber(value)) return EMPTY
  return (value * 100).toLocaleString(LOCALE, { maximumFractionDigits, minimumFractionDigits: maximumFractionDigits }) + "%"
}

/** 5.546 -> "5.55x" */
export function formatMultiple(value: number | null | undefined): string {
  if (!isNumber(value)) return EMPTY
  return value.toLocaleString(LOCALE, { maximumFractionDigits: 2, minimumFractionDigits: 2 }) + "x"
}

/** ISO date "2026-06-30" -> "30 Jun 2026" (no Date object: no time-zone shifts). */
export function formatDate(iso: string | null | undefined): string {
  if (!iso) return EMPTY
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso)
  if (!match) return iso
  const [, year, month, day] = match
  return `${Number(day)} ${MONTHS[Number(month) - 1]} ${year}`
}

/** ISO timestamp -> "2 Oct 2026, 05:43 UTC" */
export function formatTimestamp(iso: string | null | undefined): string {
  if (!iso) return EMPTY
  const match = /^(\d{4}-\d{2}-\d{2})T(\d{2}):(\d{2})/.exec(new Date(iso).toISOString())
  return match ? `${formatDate(match[1])}, ${match[2]}:${match[3]} UTC` : iso
}

/** Display scale for statement amounts. */
export type AmountScale = "full" | "millions" | "billions"

export const AMOUNT_SCALES: { value: AmountScale; label: string; divisor: number }[] = [
  { value: "billions", label: "Billions", divisor: 1e9 },
  { value: "millions", label: "Millions", divisor: 1e6 },
  { value: "full", label: "Full", divisor: 1 },
]

/** Scaled amount; negative values in parentheses as in financial statements: -1500000 (millions) -> "(1.50)". */
export function formatScaled(value: number | null | undefined, scale: AmountScale): string {
  if (!isNumber(value)) return EMPTY
  const divisor = AMOUNT_SCALES.find((s) => s.value === scale)?.divisor ?? 1
  const digits = scale === "full" ? 0 : 2
  const text = Math.abs(value / divisor).toLocaleString(LOCALE, {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  })
  return value < 0 ? `(${text})` : text
}

/** A metric value by its unit (financial_metric.unit): ratio, x, days or a currency amount. */
export function formatMetric(value: number | null | undefined, unit: string | null | undefined): string {
  if (!isNumber(value)) return EMPTY
  switch (unit) {
    case "ratio":
      return formatPercent(value)
    case "x":
      return formatMultiple(value)
    case "days":
      return `${formatNumber(value, 1)} d`
    default:
      // currency amounts (IDR) and per-share values (IDR/share)
      return unit?.endsWith("/share") ? formatNumber(value, 2) : formatCompact(value)
  }
}

/** "net_debt_to_ebitda_annualized" -> "Net debt to EBITDA (annualized)" */
export function metricLabel(name: string): string {
  const acronyms: Record<string, string> = {
    ebitda: "EBITDA", ebit: "EBIT", eps: "EPS", fcf: "FCF", ncav: "NCAV", ocf: "OCF",
    roe: "ROE", roa: "ROA", roic: "ROIC", ev: "EV", op: "OP", pe: "P/E", pb: "P/B", ps: "P/S",
  }
  const annualized = name.endsWith("_annualized")
  const words = name.replace(/_annualized$/, "").split("_").map((w) => acronyms[w] ?? w)
  const text = words.join(" ")
  const label = text.charAt(0).toUpperCase() + text.slice(1)
  return annualized ? `${label} (annualized)` : label
}

/** "PROFITABILITY" -> "Profitability", "CASH_FLOW" -> "Cash flow" */
export function titleCase(value: string | null | undefined): string {
  if (!value) return EMPTY
  const text = value.toLowerCase().replace(/_/g, " ")
  return text.charAt(0).toUpperCase() + text.slice(1)
}
