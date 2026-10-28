// Companies > Ownership (>=5%) and Ingestion > Excel Documents (RAG): KSEI's daily lists of the shareholders of more
// than 5% of every IDX company (backend /api/v1/ownership and /api/v1/rag/xlsx).

import { EMPTY, formatNumber } from "@/lib/format"

export type OwnershipMovement = "BUY" | "SELL" | "UNCHANGED" | "ENTERED" | "EXITED"
export type OwnershipTransaction = "ALL" | "BUY" | "SELL" | "CHANGED" | "ENTERED" | "EXITED" | "CROSSED" | "ACTIVITY"
/**
 * The order of the table: latest day first, or by the change in shares / in percentage points (DESC: the largest buy or
 * rise first, ASC: the largest sell or fall first); rows without a change (crossed 5% or fell below it) come last.
 */
export type OwnershipSort = "DATE" | "CHANGE_DESC" | "CHANGE_ASC" | "POINTS_DESC" | "POINTS_ASC"
/** L = local, A = foreign (asing) */
export type InvestorStatus = "L" | "A"

/** An investor of a company in the list of a day (GET /api/v1/ownership). */
export interface MovementRow {
  /** its row (of the day before when it left the list) */
  holderId: number
  reportId: number
  exchange: string
  /** "2026-10-27": the list's date */
  reportDate: string
  /** the date it is compared with (the trading day before) */
  previousDate: string
  ticker: string
  issuerName: string
  /** its number in the list (No) */
  listNumber: number
  holderName: string
  holderKey: string
  /** listed again under another name: the number of the first listing */
  aliasOf: number | null
  /** the other names the same investor is listed under that day */
  aliases: string[]
  nationality: string | null
  domicile: string | null
  status: InvestorStatus | null
  /** null: not in the list on the previous date (it crossed 5%) */
  sharesPrevious: number | null
  /** percent of the shares listed, as printed (41.1 = 41.10%) */
  percentPrevious: number | null
  /** null: not in the list on the day (it fell below 5%) */
  sharesCurrent: number | null
  percentCurrent: number | null
  /** bought (+) or sold (-); null when either date has none */
  change: number | null
  /** percentage points */
  percentChange: number | null
  movement: OwnershipMovement
  accounts: number
  /** accounts whose shares changed (with UNCHANGED: moved between accounts) */
  accountMoves: number
  /** not in the day's list: it was in the list of the day before (EXITED) */
  leftList: boolean
}

export interface MovementPage {
  total: number
  limit: number
  offset: number
  rows: MovementRow[]
}

/** A securities account of an investor. */
export interface AccountRow {
  holderId: number
  index: number
  /** broker or custodian bank (Nama Pemegang Rekening Efek) */
  participant: string
  accountName: string
  nationality: string | null
  domicile: string | null
  status: InvestorStatus | null
  sharesPrevious: number | null
  sharesCurrent: number | null
  change: number | null
}

export interface HolderDay {
  holder: MovementRow
  accounts: AccountRow[]
}

/** A stored list (GET /api/v1/rag/xlsx/reports, /api/v1/ownership/reports). */
export interface OwnershipReport {
  reportId: number
  exchange: string
  documentType: string
  reportDate: string
  previousDate: string
  title: string | null
  announcementNumber: string | null
  fileName: string | null
  fileId: number | null
  issuers: number
  holders: number
  accounts: number
  aliases: number
  buys: number
  sells: number
  unchanged: number
  entered: number
  /** null: the list of the day before is not stored (who fell below 5% is unknown) */
  exited: number | null
  warnings: string[]
  notes: string[]
  updatedAt: string | null
}

export interface OwnershipReportPage {
  total: number
  limit: number
  offset: number
  reports: OwnershipReport[]
}

/** A company of the stored lists. */
export interface OwnershipTicker {
  ticker: string
  issuerName: string
  /** investors of 5% or more on the latest day it is listed */
  holders: number
  lastDate: string
  firstDate: string
  days: number
  /** stored in the company table: it has a company page */
  stored: boolean
}

export interface OwnershipOption<T extends string> {
  code: T
  label: string
}

/** GET /api/v1/ownership/options */
export interface OwnershipOptions {
  latest: OwnershipReport | null
  /** latest first */
  dates: string[]
  tickers: OwnershipTicker[]
  transactions: OwnershipOption<OwnershipTransaction>[]
  statuses: OwnershipOption<InvestorStatus>[]
  sorts: OwnershipOption<OwnershipSort>[]
  largestBuys: MovementRow[]
  largestSells: MovementRow[]
  entered: MovementRow[]
  exited: MovementRow[]
}

/** GET /api/v1/ownership/highlights?date=: the movements of one day. */
export interface OwnershipHighlights {
  /** the list of the day (its counts) */
  report: OwnershipReport
  largestBuys: MovementRow[]
  largestSells: MovementRow[]
  entered: MovementRow[]
  exited: MovementRow[]
}

/** An investor of 5% or more on the latest day (the digest; ownership is a fraction: 0.411 = 41.1%). */
export interface DigestHolder {
  name: string
  aliases: string[]
  /** LOCAL, FOREIGN or null */
  status: "LOCAL" | "FOREIGN" | null
  domicile: string | null
  shares: number | null
  ownership: number | null
  accounts: number
  latestChange: number | null
  since: string
  sharesSince: number | null
  netChange: number | null
  participants: string[]
}

export interface DigestTransaction {
  date: string
  name: string
  movement: OwnershipMovement
  sharesBefore: number | null
  sharesAfter: number | null
  change: number | null
  ownershipBefore: number | null
  ownershipAfter: number | null
}

export interface DigestTotals {
  holders: number
  holdersOwnership: number
  localOwnership: number
  foreignOwnership: number
  buys: number
  sells: number
  entered: number
  exited: number
  accountMoveDays: number
}

/** What the lists say about one company over every stored day (OwnershipDigest; also what the AI analysis reads). */
export interface OwnershipDigest {
  ticker: string
  issuerName: string
  firstDate: string
  latestDate: string
  days: number
  reading: string
  totals: DigestTotals
  holders: DigestHolder[]
  transactions: DigestTransaction[]
  formerHolders: { name: string; lastListed: string; sharesThen: number | null; ownershipThen: number | null }[]
}

/** GET /api/v1/ownership/holders?ticker= */
export interface OwnershipCompany {
  exchange: string
  ticker: string
  issuerName: string | null
  digest: OwnershipDigest | null
  /** latest day first, then the list's order */
  days: HolderDay[]
}

export interface EntityHolding {
  ticker: string
  issuerName: string
  lastDate: string
  shares: number | null
  percent: number | null
  lastMovement: OwnershipMovement
  /** in the latest stored list */
  listed: boolean
  firstDate: string
  firstShares: number | null
  change: number | null
  accounts: number
}

/** GET /api/v1/ownership/entities?q= */
export interface OwnershipEntity {
  holderKey: string
  name: string
  status: InvestorStatus | null
  nationality: string | null
  domicile: string | null
  holdings: EntityHolding[]
}

/** An Excel document type of an exchange (GET /api/v1/rag/xlsx/status). */
export interface ExcelDocumentTypeOption {
  code: string
  label: string
  /** takes uploads now; the others are later features */
  available: boolean
  examples: string
}

export interface ExcelExchangeOption {
  code: string
  name: string
  /** "IDX - Indonesia Stock Exchange" */
  label: string
  documentTypes: ExcelDocumentTypeOption[]
}

/** The lists stored of one Excel document type (IDX). */
export interface ExcelStoredType {
  documentType: string
  label: string
  reports: number
  latestDate: string | null
  /** the companies' documents of the type in the vector store */
  documents: number
}

/** GET /api/v1/rag/xlsx/status */
export interface ExcelStatus {
  exchanges: ExcelExchangeOption[]
  /** Ownership (>=5%) lists stored */
  reports: number
  latestDate: string | null
  /** the companies' ownership documents in the vector store (both types) */
  documents: number
  pending: number
  /** the lists stored of each type */
  stored: ExcelStoredType[]
}

/** The result of a RAG_XLSX job (OwnershipIngestionService.OwnershipResult). */
export interface OwnershipResult {
  documentType: string
  exchange: string
  fileName: string
  reportDate: string
  previousDate: string
  title: string | null
  announcementNumber: string | null
  issuers: number
  holders: number
  accounts: number
  aliases: number
  buys: number
  sells: number
  unchanged: number
  entered: number
  exited: number | null
  outcome: "STORED" | "REPLACED"
  ragDocuments: number
  chunks: number
  companiesNotStored: number
  embeddingModel: string
  ragError: string | null
  warnings: string[]
  notes: string[]
}

/** The page's filters (in the URL: ?tickers=ADRO&holder=lo kheng hong&transaction=BUY&status=A&from=&to=). */
export interface OwnershipFilters {
  tickers: string[]
  holder: string | null
  transaction: OwnershipTransaction
  status: InvestorStatus | null
  from: string | null
  to: string | null
  /** also the investors listed again under another name */
  aliases: boolean
  sort: OwnershipSort
}

export { OWNERSHIP_HREF } from "@/lib/companies-sections"
export const EXCEL_INGESTION_HREF = "/ingestion/rag-excel"
export const OWNERSHIP_PAGE_SIZES = [25, 50, 100, 200] as const
export const DEFAULT_OWNERSHIP_PAGE_SIZE = 50

const SORTS: OwnershipSort[] = ["DATE", "CHANGE_DESC", "CHANGE_ASC", "POINTS_DESC", "POINTS_ASC"]
const TRANSACTIONS: OwnershipTransaction[] = ["ALL", "BUY", "SELL", "CHANGED", "ENTERED", "EXITED", "CROSSED", "ACTIVITY"]
const DATE = /^\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$/
const TICKER = /^[A-Z0-9][A-Z0-9.-]{0,19}$/

/** "adro, BBCA adro" -> ["ADRO", "BBCA"]; invalid tickers are dropped. */
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
  holder?: string | null
  transaction?: string | null
  status?: string | null
  from?: string | null
  to?: string | null
  aliases?: string | null
  sort?: string | null
}): OwnershipFilters {
  const sort = params.sort?.toUpperCase().replace("-", "_") as OwnershipSort | undefined
  const transaction = params.transaction?.toUpperCase() as OwnershipTransaction | undefined
  const status = params.status?.toUpperCase()
  const from = params.from && DATE.test(params.from) ? params.from : null
  const to = params.to && DATE.test(params.to) ? params.to : null
  const holder = params.holder?.trim().slice(0, 100) || null
  return {
    tickers: parseTickers(params.tickers),
    holder,
    transaction: transaction && TRANSACTIONS.includes(transaction) ? transaction : "ALL",
    status: status === "L" || status === "A" ? status : null,
    // a first date after the last one is dropped (the backend would refuse it)
    from: from && to && from > to ? null : from,
    to,
    aliases: params.aliases === "true",
    sort: sort && SORTS.includes(sort) ? sort : "DATE",
  }
}

/** The backend query of one page of rows (also the page's URL, without limit / offset). */
export function ownershipQuery(filters: OwnershipFilters, limit?: number, offset?: number): URLSearchParams {
  const query = new URLSearchParams()
  if (filters.tickers.length > 0) query.set("tickers", filters.tickers.join(","))
  if (filters.holder) query.set("holder", filters.holder)
  if (filters.transaction !== "ALL") query.set("transaction", filters.transaction)
  if (filters.status) query.set("status", filters.status)
  if (filters.from) query.set("from", filters.from)
  if (filters.to) query.set("to", filters.to)
  if (filters.aliases) query.set("aliases", "true")
  if (filters.sort !== "DATE") query.set("sort", filters.sort)
  if (limit !== undefined) query.set("limit", String(limit))
  if (offset !== undefined) query.set("offset", String(offset))
  return query
}

/** "2026-10-27" -> "27 Oct 2026" */
export function dayLabel(date: string | null | undefined): string {
  const match = date ? /^(\d{4})-(\d{2})-(\d{2})$/.exec(date) : null
  if (!match) return date ?? EMPTY
  const names = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
  return `${Number(match[3])} ${names[Number(match[2]) - 1]} ${match[1]}`
}

/** "2026-10-27" -> "27 Oct" (column headers) */
export function shortDay(date: string): string {
  return dayLabel(date).replace(/ \d{4}$/, "")
}

/** 41.1 (percent) -> "41.10%" */
export function percentLabel(value: number | null | undefined): string {
  return value === null || value === undefined ? EMPTY : `${value.toFixed(2)}%`
}

/** 0.411 (fraction) -> "41.10%" */
export function fractionLabel(value: number | null | undefined): string {
  return value === null || value === undefined ? EMPTY : `${(value * 100).toFixed(2)}%`
}

/** +1.25 -> "+1.25 pp" */
export function pointsLabel(value: number | null | undefined): string {
  if (value === null || value === undefined) return EMPTY
  if (value === 0) return "0 pp"
  return `${value > 0 ? "+" : ""}${value.toFixed(2)} pp`
}

export function sharesLabel(value: number | null | undefined): string {
  return formatNumber(value, 0)
}

export const STATUS_LABEL: Record<InvestorStatus, string> = { L: "Local", A: "Foreign" }

export const MOVEMENT_LABEL: Record<OwnershipMovement, string> = {
  BUY: "Buy",
  SELL: "Sell",
  UNCHANGED: "No change",
  ENTERED: "Crossed 5%",
  EXITED: "Below 5%",
}

/** "peng-2026-10-27-00104-lima-persen.xlsx" -> "2026-10-27" (the list's date in its file name), else null. */
export function dateInFileName(fileName: string): string | null {
  const match = /(\d{4}-\d{2}-\d{2})/.exec(fileName)
  return match && DATE.test(match[1]) ? match[1] : null
}
