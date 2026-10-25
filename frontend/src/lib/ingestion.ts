// Ingestion page helpers shared by the client components and the route handlers.

import { EMPTY } from "@/lib/format"
import type {
  CompanyDocumentType,
  IndexEntry,
  IndexPriceStatus,
  IngestionJobCreator,
  IngestionJobStatus,
  IngestionJobType,
} from "@/lib/types"

/** Backend upload limit (spring.servlet.multipart.max-file-size: 50MB). */
export const MAX_UPLOAD_BYTES = 50 * 1024 * 1024

/** The types of a company PDF (CompanyDocumentType on the backend), the default first. */
export const COMPANY_DOCUMENT_TYPES: { code: CompanyDocumentType; label: string; description: string }[] = [
  {
    code: "FINANCIAL_STATEMENT",
    label: "Financial Statement",
    description: "Financial statements and annual reports, e.g. FinancialStatement-2025-Tahunan-HRTA.pdf",
  },
  {
    code: "REGISTRATION",
    label: "Registration",
    description:
      "Monthly registration report of securities holders (Laporan Bulanan Registrasi Pemegang Efek) from idx.co.id",
  },
]

export const DEFAULT_COMPANY_DOCUMENT_TYPE: CompanyDocumentType = "FINANCIAL_STATEMENT"

export function companyDocumentTypeLabel(code: CompanyDocumentType | null | undefined): string {
  return COMPANY_DOCUMENT_TYPES.find((t) => t.code === code)?.label ?? EMPTY
}

/** A file name that looks like a registration report: "registrasi_hrta_juni.pdf", "Registration-HRTA.pdf", LBRE. */
export function looksLikeRegistration(fileName: string): boolean {
  return /registrasi|registration|lbre/i.test(fileName)
}

/** Rows per page of the jobs table and the stored documents (RAG), and the default. */
export const TABLE_PAGE_SIZES = [5, 10, 20, 50] as const
export const DEFAULT_TABLE_PAGE_SIZE = 10

export const ACTIVE_STATUSES: IngestionJobStatus[] = ["QUEUED", "RUNNING", "WAITING_RATE_LIMIT"]

export function isActive(status: IngestionJobStatus): boolean {
  return ACTIVE_STATUSES.includes(status)
}

export const STATUS_META: Record<IngestionJobStatus, { label: string; className: string }> = {
  QUEUED: { label: "Queued", className: "border-border bg-muted text-muted-foreground" },
  RUNNING: { label: "Running", className: "bg-sky-500/10 text-sky-600 dark:text-sky-400" },
  WAITING_RATE_LIMIT: { label: "Rate limited", className: "bg-amber-500/10 text-amber-600 dark:text-amber-400" },
  SUCCEEDED: { label: "Done", className: "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400" },
  INCOMPLETE: { label: "Incomplete", className: "bg-amber-500/10 text-amber-600 dark:text-amber-400" },
  FAILED: { label: "Failed", className: "bg-destructive/10 text-destructive" },
}

export const TYPE_LABEL: Record<IngestionJobType, string> = {
  FINANCIAL_STATEMENT: "Financial statement",
  PRICE: "Daily prices",
  FUNDAMENTALS: "Screening data",
  SCREENING: "AI screening",
  RAG_PDF: "PDF document (RAG)",
  RAG_NEWS: "News (RAG)",
  ANALYSIS: "AI analysis",
  MACRO: "Macro series",
  MACRO_ANALYSIS: "AI macro analysis",
  LISTING_PRICES: "Price history (screening)",
  TECHNICAL_SCREENING: "AI technical screening",
}

/** "Rina Wijaya (rina)", "rina (deleted user)", "Scheduled run" */
export function creatorLabel(creator: IngestionJobCreator | null): string {
  if (!creator) return "Scheduled run"
  if (creator.userId === null) return `${creator.username} (deleted user)`
  return creator.fullName ? `${creator.fullName} (${creator.username})` : creator.username
}

/** 523787 -> "511.5 KB" */
export function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined || !Number.isFinite(bytes)) return EMPTY
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

/** 95000 -> "1m 35s", 800 -> "0.8s" */
export function formatDuration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || !Number.isFinite(ms) || ms < 0) return EMPTY
  if (ms < 10_000) return `${(ms / 1000).toFixed(1)}s`
  const seconds = Math.round(ms / 1000)
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const rest = seconds % 60
  if (hours > 0) return `${hours}h ${minutes}m`
  return minutes > 0 ? `${minutes}m ${rest}s` : `${rest}s`
}

/** "yahoo" -> "Yahoo Finance" (neracalab.prices.provider) */
export function providerLabel(provider: string | null | undefined): string {
  switch (provider) {
    case "yahoo":
      return "Yahoo Finance"
    case "eodhd":
      return "EODHD"
    case "worldbank":
      return "the World Bank Pink Sheet"
    case "imf":
      return "the IMF Primary Commodity Prices"
    case "fred":
      return "FRED"
    default:
      return provider || "the price provider"
  }
}

/** "fred" -> "FRED" (macro_series_def.source) */
export function macroSourceLabel(source: string | null | undefined): string {
  switch (source) {
    case "fred":
      return "FRED"
    case "yahoo":
      return "Yahoo Finance"
    case "bi":
      return "Bank Indonesia"
    case "bi_seki":
      return "Bank Indonesia SEKI"
    case "cnbc":
      return "CNBC"
    case "cftc":
      return "CFTC"
    case "manual":
      return "CSV upload"
    default:
      return source || EMPTY
  }
}

const JOB_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/** A job id is a UUID. */
export function isJobId(id: string): boolean {
  return JOB_ID.test(id)
}

/** The indexes an index job stores (an older backend: the index alone). */
export function indexesOf(index: IndexPriceStatus): IndexEntry[] {
  return index.indexes && index.indexes.length > 0
    ? index.indexes
    : [{ code: index.code, symbol: index.symbol, name: index.name, rows: index.rows, firstDate: index.firstDate,
        lastDate: index.lastDate, upToDate: index.upToDate }]
}

/** "IHSG and LQ45" */
export function indexNames(index: IndexPriceStatus): string {
  const codes = indexesOf(index).map((i) => i.code)
  return codes.length <= 1 ? codes.join("") : `${codes.slice(0, -1).join(", ")} and ${codes[codes.length - 1]}`
}
