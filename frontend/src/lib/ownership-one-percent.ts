// Companies > Ownership (>=1%) and Ingestion > Excel Documents (RAG): KSEI's monthly lists of the shareholders of more
// than 1% of every IDX company (backend /api/v1/ownership/one-percent and /api/v1/rag/xlsx).

import { EMPTY } from "@/lib/format"
import type { InvestorStatus, OwnershipOption, OwnershipSort } from "@/lib/ownership"

export type OnePercentMovement = "BUY" | "SELL" | "UNCHANGED" | "ENTERED" | "EXITED"
export type OnePercentTransaction =
  | "ALL"
  | "BUY"
  | "SELL"
  | "CHANGED"
  | "ENTERED"
  | "EXITED"
  | "CROSSED"
  | "RENAMED"
  | "ACTIVITY"
/** INDIVIDUAL, CORPORATE, FUND, FINANCIAL, GOVERNMENT, OTHER (UNKNOWN in a digest: no type printed) */
export type TypeGroup = "INDIVIDUAL" | "CORPORATE" | "FUND" | "FINANCIAL" | "GOVERNMENT" | "OTHER"
/** how an investor was matched with one of the month before: the same name, spelled otherwise, the same block */
export type MatchKind = "SAME" | "RENAMED" | "MOVED"

/** An investor of a company in the list of a month (GET /api/v1/ownership/one-percent). */
export interface OnePercentRow {
  /** its row (of the month before when it left the list) */
  holderId: number
  reportId: number
  exchange: string
  /** "2026-09-30": the list's date */
  reportDate: string
  /** the stored list it is compared with; null when none is stored before it */
  previousDate: string | null
  ticker: string
  issuerName: string
  listNumber: number
  holderName: string
  holderKey: string
  /** the other names of its rows that month (an SID and a scrip account) */
  otherNames: string[]
  sourceRows: number
  /** Individual, Corporate, Mutual Funds, ...; null when not printed */
  investorType: string | null
  typeGroup: TypeGroup | null
  status: InvestorStatus | null
  nationality: string | null
  domicile: string | null
  sharesScripless: number
  sharesScrip: number
  /** its name the month before (another one when RENAMED or MOVED) */
  previousName: string | null
  matchKind: MatchKind | null
  previousHolderId: number | null
  sharesPrevious: number | null
  percentPrevious: number | null
  /** null: not in the month's list (no longer above 1%) */
  sharesCurrent: number | null
  percentCurrent: number | null
  change: number | null
  percentChange: number | null
  /** null: no list before it is stored */
  movement: OnePercentMovement | null
  leftList: boolean
}

export interface OnePercentPage {
  total: number
  limit: number
  offset: number
  rows: OnePercentRow[]
}

/** A stored monthly list (GET /api/v1/rag/xlsx/reports?documentType=OWNERSHIP_1_PERCENT). */
export interface OnePercentReport {
  reportId: number
  exchange: string
  documentType: string
  reportDate: string
  /** null: no list before it is stored (no movements known) */
  previousDate: string | null
  announcementNumber: string | null
  fileName: string | null
  fileId: number | null
  issuers: number
  holders: number
  sourceRows: number
  buys: number
  sells: number
  unchanged: number
  entered: number
  exited: number | null
  renamed: number
  moved: number
  warnings: string[]
  notes: string[]
  updatedAt: string | null
}

export interface OnePercentReportPage {
  total: number
  limit: number
  offset: number
  reports: OnePercentReport[]
}

export interface OnePercentTicker {
  ticker: string
  issuerName: string
  /** investors of 1% or more in the latest month it is listed */
  holders: number
  lastDate: string
  firstDate: string
  months: number
  stored: boolean
}

export interface TopInvestor {
  holderKey: string
  name: string
  investorType: string | null
  status: InvestorStatus | null
  companies: number
  largestPercent: number | null
  largestTicker: string
}

/** GET /api/v1/ownership/one-percent/highlights?date= */
export interface OnePercentHighlights {
  report: OnePercentReport
  largestBuys: OnePercentRow[]
  largestSells: OnePercentRow[]
  entered: OnePercentRow[]
  exited: OnePercentRow[]
  /** listed under another name than the month before (renamed or moved) */
  renamed: OnePercentRow[]
  topInvestors: TopInvestor[]
}

/** GET /api/v1/ownership/one-percent/options */
export interface OnePercentOptions {
  latest: OnePercentReport | null
  dates: string[]
  tickers: OnePercentTicker[]
  transactions: OwnershipOption<OnePercentTransaction>[]
  statuses: OwnershipOption<InvestorStatus>[]
  groups: OwnershipOption<TypeGroup>[]
  sorts: OwnershipOption<OwnershipSort>[]
  highlights: OnePercentHighlights | null
}

/** The digest of one company (OnePercentDigest; ownership is a fraction: 0.411 = 41.1%). */
export interface OnePercentDigestHolder {
  name: string
  otherNames: string[]
  investorType: string | null
  typeGroup: TypeGroup | null
  status: "LOCAL" | "FOREIGN" | null
  domicile: string | null
  shares: number | null
  scrip: number | null
  ownership: number | null
  latestMovement: OnePercentMovement | null
  latestChange: number | null
  previousName: string | null
  matchKind: MatchKind | null
  since: string
  sharesSince: number | null
  netChange: number | null
}

export interface OnePercentDigest {
  ticker: string
  issuerName: string
  firstDate: string
  latestDate: string
  months: number
  reading: string
  totals: {
    holders: number
    holdersOwnership: number
    localOwnership: number
    foreignOwnership: number
    top3Ownership: number
    buys: number
    sells: number
    entered: number
    exited: number
    renamed: number
    moved: number
  }
  byType: { group: TypeGroup | "UNKNOWN"; holders: number; ownership: number }[]
  holders: OnePercentDigestHolder[]
  transactions: {
    date: string
    name: string
    movement: OnePercentMovement | "RENAMED" | "MOVED"
    sharesBefore: number | null
    sharesAfter: number | null
    change: number | null
    ownershipBefore: number | null
    ownershipAfter: number | null
    previousName: string | null
    matchKind: MatchKind | null
  }[]
  formerHolders: { name: string; lastListed: string; sharesThen: number | null; ownershipThen: number | null }[]
}

/** GET /api/v1/ownership/one-percent/holders?ticker= */
export interface OnePercentCompany {
  exchange: string
  ticker: string
  issuerName: string | null
  digest: OnePercentDigest | null
  /** latest month first, then the list's order */
  rows: OnePercentRow[]
}

export interface OnePercentEntityHolding {
  ticker: string
  issuerName: string
  lastDate: string
  shares: number | null
  percent: number | null
  lastMovement: OnePercentMovement | null
  listed: boolean
  firstDate: string
  firstShares: number | null
  change: number | null
}

/** GET /api/v1/ownership/one-percent/entities?q= */
export interface OnePercentEntity {
  holderKey: string
  name: string
  investorType: string | null
  typeGroup: TypeGroup | null
  status: InvestorStatus | null
  nationality: string | null
  domicile: string | null
  holdings: OnePercentEntityHolding[]
}

/** The result of a RAG_XLSX job of Ownership (>=1%) (OnePercentIngestionService.OnePercentResult). */
export interface OnePercentResult {
  documentType: "OWNERSHIP_1_PERCENT"
  exchange: string
  fileName: string
  reportDate: string
  previousDate: string | null
  nextDate: string | null
  announcementNumber: string | null
  issuers: number
  holders: number
  sourceRows: number
  buys: number
  sells: number
  unchanged: number
  entered: number
  exited: number | null
  renamed: number
  moved: number
  outcome: "STORED" | "REPLACED"
  ragDocuments: number
  chunks: number
  companiesNotStored: number
  embeddingModel: string
  ragError: string | null
  warnings: string[]
  notes: string[]
}

/** The page's filters (in the URL: ?tickers=ADRO&holder=&transaction=BUY&status=A&group=FUND&from=&to=&sort=). */
export interface OnePercentFilters {
  tickers: string[]
  holder: string | null
  transaction: OnePercentTransaction
  status: InvestorStatus | null
  group: TypeGroup | null
  from: string | null
  to: string | null
  sort: OwnershipSort
}

export { OWNERSHIP_ONE_PERCENT_HREF } from "@/lib/companies-sections"
export const ONE_PERCENT_DOCUMENT_TYPE = "OWNERSHIP_1_PERCENT"

const SORTS: OwnershipSort[] = ["DATE", "CHANGE_DESC", "CHANGE_ASC", "POINTS_DESC", "POINTS_ASC"]
const TRANSACTIONS: OnePercentTransaction[] = [
  "ALL",
  "BUY",
  "SELL",
  "CHANGED",
  "ENTERED",
  "EXITED",
  "CROSSED",
  "RENAMED",
  "ACTIVITY",
]
const GROUPS: TypeGroup[] = ["INDIVIDUAL", "CORPORATE", "FUND", "FINANCIAL", "GOVERNMENT", "OTHER"]
const DATE = /^\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$/
const TICKER = /^[A-Z0-9][A-Z0-9.-]{0,19}$/

function tickersOf(value: string | null | undefined): string[] {
  const out: string[] = []
  for (const part of (value ?? "").toUpperCase().split(/[\s,;]+/)) {
    if (part && TICKER.test(part) && !out.includes(part)) out.push(part)
  }
  return out.slice(0, 50)
}

/** The filters of URL parameters; unknown values fall back to "all". */
export function onePercentFiltersOf(params: {
  tickers?: string | null
  holder?: string | null
  transaction?: string | null
  status?: string | null
  group?: string | null
  from?: string | null
  to?: string | null
  sort?: string | null
}): OnePercentFilters {
  const sort = params.sort?.toUpperCase().replace("-", "_") as OwnershipSort | undefined
  const transaction = params.transaction?.toUpperCase() as OnePercentTransaction | undefined
  const status = params.status?.toUpperCase()
  const group = params.group?.toUpperCase() as TypeGroup | undefined
  const from = params.from && DATE.test(params.from) ? params.from : null
  const to = params.to && DATE.test(params.to) ? params.to : null
  return {
    tickers: tickersOf(params.tickers),
    holder: params.holder?.trim().slice(0, 100) || null,
    transaction: transaction && TRANSACTIONS.includes(transaction) ? transaction : "ALL",
    status: status === "L" || status === "A" ? status : null,
    group: group && GROUPS.includes(group) ? group : null,
    // a first date after the last one is dropped (the backend would refuse it)
    from: from && to && from > to ? null : from,
    to,
    sort: sort && SORTS.includes(sort) ? sort : "DATE",
  }
}

/** The backend query of one page of rows (also the page's URL, without limit / offset). */
export function onePercentQuery(filters: OnePercentFilters, limit?: number, offset?: number): URLSearchParams {
  const query = new URLSearchParams()
  if (filters.tickers.length > 0) query.set("tickers", filters.tickers.join(","))
  if (filters.holder) query.set("holder", filters.holder)
  if (filters.transaction !== "ALL") query.set("transaction", filters.transaction)
  if (filters.status) query.set("status", filters.status)
  if (filters.group) query.set("group", filters.group)
  if (filters.from) query.set("from", filters.from)
  if (filters.to) query.set("to", filters.to)
  if (filters.sort !== "DATE") query.set("sort", filters.sort)
  if (limit !== undefined) query.set("limit", String(limit))
  if (offset !== undefined) query.set("offset", String(offset))
  return query
}

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]

/** "2026-09-30" -> "Sep 2026" (a monthly list) */
export function monthLabel(date: string | null | undefined): string {
  const match = date ? /^(\d{4})-(\d{2})-\d{2}$/.exec(date) : null
  if (!match) return date ?? EMPTY
  return `${MONTHS[Number(match[2]) - 1]} ${match[1]}`
}

/** "2026-09-30" -> "Sep 26" (column headers) */
export function shortMonth(date: string): string {
  const match = /^(\d{2})(\d{2})-(\d{2})-\d{2}$/.exec(date)
  return match ? `${MONTHS[Number(match[3]) - 1]} ${match[2]}` : date
}

/**
 * The month of a list in its file name: "peng-2026-09-00024-satu-persen.xlsx" -> "2026-09"; the older names carry
 * the month only ("peng-02-00011-satu-persen.xlsx" -> "02"); null for another name.
 */
export function monthInFileName(fileName: string): string | null {
  const full = /peng-(\d{4})-(\d{2})-\d+-satu/i.exec(fileName)
  if (full) return `${full[1]}-${full[2]}`
  const short = /peng-(\d{2})-\d+-satu/i.exec(fileName)
  return short ? short[1] : null
}

export const GROUP_LABEL: Record<TypeGroup | "UNKNOWN", string> = {
  INDIVIDUAL: "Individuals",
  CORPORATE: "Corporates",
  FUND: "Funds",
  FINANCIAL: "Financial institutions",
  GOVERNMENT: "Government",
  OTHER: "Other",
  UNKNOWN: "Type not given",
}

/** The colour of a type group (the composition bar and its legend). */
export const GROUP_COLOR: Record<TypeGroup | "UNKNOWN", string> = {
  INDIVIDUAL: "bg-sky-500",
  CORPORATE: "bg-violet-500",
  FUND: "bg-emerald-500",
  FINANCIAL: "bg-amber-500",
  GOVERNMENT: "bg-rose-500",
  OTHER: "bg-teal-500",
  UNKNOWN: "bg-slate-400",
}

export const MOVEMENT_LABEL_1: Record<OnePercentMovement, string> = {
  BUY: "Buy",
  SELL: "Sell",
  UNCHANGED: "No change",
  ENTERED: "New above 1%",
  EXITED: "No longer above 1%",
}
