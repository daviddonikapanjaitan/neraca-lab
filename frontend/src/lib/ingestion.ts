// Ingestion page helpers shared by the client components and the route handlers.

import { EMPTY } from "@/lib/format"
import type { IngestionJobCreator, IngestionJobStatus, IngestionJobType } from "@/lib/types"

/** Backend upload limit (spring.servlet.multipart.max-file-size: 20MB). */
export const MAX_UPLOAD_BYTES = 20 * 1024 * 1024

/** Jobs shown on the ingestion page (most recent first). */
export const JOB_LIMIT = 100

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
    default:
      return provider || "the price provider"
  }
}

const JOB_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/** A job id is a UUID. */
export function isJobId(id: string): boolean {
  return JOB_ID.test(id)
}
