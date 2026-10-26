// Companies > Registration: the shares of the directors and commissioners and of the shareholders of 5% or more,
// month by month, from the monthly registration reports of securities holders (backend /api/v1/registrations).

export type RegistrationTransaction = "ALL" | "BUY" | "SELL" | "CHANGED"
export type InsiderRole = "DIRECTOR" | "COMMISSIONER" | "OTHER"

/** The shares of a director or commissioner at the end of a month (GET /api/v1/registrations). */
export interface InsiderRow {
  insiderId: number
  reportId: number
  exchange: string
  ticker: string
  companyName: string
  /** "2026-06": the month of the report */
  period: string
  holderName: string
  /** as filed: "Direksi", "Komisaris", "Direktur Utama", ... */
  position: string
  role: InsiderRole
  sharesPrevious: number | null
  /** percent of the shares listed, e.g. 0.05 = 0.05 % */
  percentPrevious: number | null
  sharesCurrent: number | null
  percentCurrent: number | null
  /** bought (+) or sold (-) during the month; null when a share count is missing */
  change: number | null
  controller: boolean
  controllerAffiliate: boolean
  correction: boolean
  filedAt: string | null
  letterNumber: string | null
  documentId: number | null
  fileName: string | null
}

export interface InsiderPage {
  total: number
  limit: number
  offset: number
  rows: InsiderRow[]
}

/** The shares by type of holder at the end of a month (the report's summary); null = not printed. */
export interface Ownership {
  controller: number | null
  controllerAffiliates: number | null
  /** the directors and commissioners together, as the summary prints them */
  directorsAndCommissioners: number | null
  treasury: number | null
}

/** A stored report (GET /api/v1/registrations/reports?ticker=). */
export interface ReportRow {
  reportId: number
  exchange: string
  ticker: string
  period: string
  letterNumber: string | null
  subject: string | null
  correction: boolean
  filedAt: string | null
  registrar: string | null
  listedSharesPrevious: number | null
  listedSharesCurrent: number | null
  insiders: number
  /** shareholders of 5% or more read; null: stored before they were read (upload the report again) */
  holders: number | null
  ownership: Ownership
  warnings: string[]
  documentId: number | null
  fileName: string | null
  updatedAt: string | null
}

/** A shareholder of 5% or more at the end of a month (GET /api/v1/registrations/holders?ticker=). */
export interface HolderRow {
  holderId: number
  reportId: number
  exchange: string
  ticker: string
  period: string
  row: number
  holderName: string
  sharesPrevious: number | null
  /** as printed: of the shares issued or of those listed, as the company reports it */
  percentPrevious: number | null
  sharesCurrent: number | null
  percentCurrent: number | null
  change: number | null
  controller: boolean
  controllerAffiliate: boolean
}

/** A company with registration reports. */
export interface CompanyReports {
  exchange: string
  ticker: string
  companyName: string
  reports: number
  firstPeriod: string
  lastPeriod: string
}

export interface RegistrationOption<T extends string> {
  code: T
  label: string
}

/** GET /api/v1/registrations/companies: the companies with reports and the filter options. */
export interface RegistrationOptions {
  companies: CompanyReports[]
  transactions: RegistrationOption<RegistrationTransaction>[]
  roles: RegistrationOption<InsiderRole>[]
}

/** The page's filters (in the URL: ?tickers=HRTA,BBRI&transaction=BUY&role=DIRECTOR&from=2026-05&to=2026-08). */
export interface RegistrationFilters {
  tickers: string[]
  transaction: RegistrationTransaction
  role: InsiderRole | null
  from: string | null
  to: string | null
}

export { REGISTRATION_HREF } from "@/lib/companies-sections"
export const REGISTRATION_PAGE_SIZES = [25, 50, 100, 200] as const
export const DEFAULT_REGISTRATION_PAGE_SIZE = 50

const TRANSACTIONS: RegistrationTransaction[] = ["ALL", "BUY", "SELL", "CHANGED"]
const ROLES: InsiderRole[] = ["DIRECTOR", "COMMISSIONER", "OTHER"]
const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/
const TICKER = /^[A-Z0-9][A-Z0-9.-]{0,19}$/

/** "hrta, BBRI hrta" -> ["HRTA", "BBRI"]; invalid tickers are dropped. */
export function parseTickers(value: string | null | undefined): string[] {
  const out: string[] = []
  for (const part of (value ?? "").toUpperCase().split(/[\s,;]+/)) {
    if (part && TICKER.test(part) && !out.includes(part)) out.push(part)
  }
  return out.slice(0, 50)
}

/** The filters of URL parameters; unknown values fall back to "all". */
export function filtersOf(params: {
  tickers?: string | null
  transaction?: string | null
  role?: string | null
  from?: string | null
  to?: string | null
}): RegistrationFilters {
  const transaction = params.transaction?.toUpperCase() as RegistrationTransaction | undefined
  const role = params.role?.toUpperCase() as InsiderRole | undefined
  const from = params.from && MONTH.test(params.from) ? params.from : null
  const to = params.to && MONTH.test(params.to) ? params.to : null
  return {
    tickers: parseTickers(params.tickers),
    transaction: transaction && TRANSACTIONS.includes(transaction) ? transaction : "ALL",
    role: role && ROLES.includes(role) ? role : null,
    // a first month after the last one is dropped (the backend would refuse it)
    from: from && to && from > to ? null : from,
    to,
  }
}

/** The backend query of one page of rows (also the page's URL, without limit / offset). */
export function registrationQuery(filters: RegistrationFilters, limit?: number, offset?: number): URLSearchParams {
  const query = new URLSearchParams()
  if (filters.tickers.length > 0) query.set("tickers", filters.tickers.join(","))
  if (filters.transaction !== "ALL") query.set("transaction", filters.transaction)
  if (filters.role) query.set("role", filters.role)
  if (filters.from) query.set("from", filters.from)
  if (filters.to) query.set("to", filters.to)
  if (limit !== undefined) query.set("limit", String(limit))
  if (offset !== undefined) query.set("offset", String(offset))
  return query
}

/** "2026-06" -> "Jun 2026" */
export function monthLabel(period: string): string {
  const match = /^(\d{4})-(\d{2})$/.exec(period)
  if (!match) return period
  const names = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
  return `${names[Number(match[2]) - 1]} ${match[1]}`
}

/** "May 2026 - Aug 2026", "From May 2026", "Up to Aug 2026", "May 2026", "All months" */
export function rangeLabel(from: string | null, to: string | null): string {
  if (from && to) return from === to ? monthLabel(from) : `${monthLabel(from)} - ${monthLabel(to)}`
  if (from) return `From ${monthLabel(from)}`
  if (to) return `Up to ${monthLabel(to)}`
  return "All months"
}

export const ROLE_LABEL: Record<InsiderRole, string> = {
  DIRECTOR: "Director",
  COMMISSIONER: "Commissioner",
  OTHER: "Other",
}
