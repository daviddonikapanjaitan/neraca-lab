// AI stock screening helpers shared by the screening components.

import { EMPTY } from "@/lib/format"
import type { InvestorAgentCode, MarketCapTier, NewsBriefView, ScreeningRun } from "@/lib/types"

/** Runs shown on the screening page (most recent first). */
export const RUN_LIMIT = 50

export const TIER_LABEL: Record<MarketCapTier, string> = {
  LARGE: "Large cap",
  MID: "Mid cap",
  SMALL: "Small cap",
}

/** Short column labels of the agents. */
export const AGENT_SHORT: Record<InvestorAgentCode, string> = {
  BUFFETT: "Buffett",
  MUNGER: "Munger",
  LYNCH: "Lynch",
  FISHER: "Fisher",
  GILL: "Gill",
  RISK: "Risk",
}

export const AGENT_ORDER: InvestorAgentCode[] = ["BUFFETT", "MUNGER", "LYNCH", "FISHER", "GILL", "RISK"]

/** Text color of a 0-100 score: green from 65, amber from 45, red below. */
export function scoreClass(score: number | null | undefined): string {
  if (score === null || score === undefined || !Number.isFinite(score)) return "text-muted-foreground"
  if (score >= 65) return "text-emerald-600 dark:text-emerald-400"
  if (score >= 45) return "text-amber-600 dark:text-amber-400"
  return "text-rose-600 dark:text-rose-400"
}

/** 72.345 -> "72.3" */
export function formatScore(score: number | null | undefined): string {
  if (score === null || score === undefined || !Number.isFinite(score)) return EMPTY
  return score.toFixed(1)
}

/** 0.31234 -> "$0.312"; small amounts keep 4 decimals. */
export function formatUsd(usd: number | null | undefined): string {
  if (usd === null || usd === undefined || !Number.isFinite(usd)) return EMPTY
  return usd < 0.01 ? `$${usd.toFixed(4)}` : `$${usd.toFixed(3)}`
}

/** 1234567 -> "1,234,567" */
export function formatTokens(tokens: number | null | undefined): string {
  if (tokens === null || tokens === undefined || !Number.isFinite(tokens)) return EMPTY
  return tokens.toLocaleString("en-US")
}

export const SENTIMENT_CLASS: Record<NewsBriefView["sentiment"], string> = {
  POSITIVE: "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400",
  NEUTRAL: "border-border bg-muted text-muted-foreground",
  NEGATIVE: "bg-destructive/10 text-destructive",
  MIXED: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
}

export const CONVICTION_CLASS: Record<"HIGH" | "MEDIUM" | "LOW", string> = {
  HIGH: "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400",
  MEDIUM: "bg-sky-500/10 text-sky-600 dark:text-sky-400",
  LOW: "border-border bg-muted text-muted-foreground",
}

/** "STRONG_FIT" -> "Strong fit" */
export function verdictLabel(verdict: string | null | undefined): string {
  if (!verdict) return EMPTY
  const text = verdict.replace(/_/g, " ").toLowerCase()
  return text.charAt(0).toUpperCase() + text.slice(1)
}

/** "Large cap · top 25 · 3 agents" */
export function runTitle(run: Pick<ScreeningRun, "marketCapTier" | "topN" | "agents" | "exchange">): string {
  return `${run.exchange} ${TIER_LABEL[run.marketCapTier]} · top ${run.topN} · ${run.agents.length} ${
    run.agents.length === 1 ? "agent" : "agents"
  }`
}

/** Yahoo Finance quote page of a listing (IDX: BBCA -> BBCA.JK). */
export function yahooQuoteUrl(exchange: string, ticker: string): string {
  const symbol = exchange === "IDX" ? `${ticker}.JK` : ticker
  return `https://finance.yahoo.com/quote/${encodeURIComponent(symbol)}`
}

/** Stage label of the usage table. */
export const STAGE_LABEL: Record<string, string> = {
  RESEARCH: "Research agent (ReAct + tools)",
  RETRIEVAL: "Document search (embeddings)",
  AGENT: "Investor agents",
  REFLECTION: "Reflection critic",
  SYNTHESIS: "Synthesis",
}

/** Metric keys of the report and how to show them. */
export const METRIC_FORMAT: { key: string; label: string; kind: "percent" | "multiple" | "number" | "compact" }[] = [
  { key: "marketCap", label: "Market cap", kind: "compact" },
  { key: "pe", label: "P/E", kind: "multiple" },
  { key: "pb", label: "P/B", kind: "multiple" },
  { key: "peg", label: "PEG", kind: "number" },
  { key: "evToEbitda", label: "EV/EBITDA", kind: "multiple" },
  { key: "roe", label: "ROE", kind: "percent" },
  { key: "roa", label: "ROA", kind: "percent" },
  { key: "operatingMargin", label: "Operating margin", kind: "percent" },
  { key: "profitMargin", label: "Net margin", kind: "percent" },
  { key: "debtToEquity", label: "Debt / equity", kind: "number" },
  { key: "revenueCagr", label: "Revenue CAGR", kind: "percent" },
  { key: "netIncomeCagr", label: "Earnings CAGR", kind: "percent" },
  { key: "fcfYield", label: "FCF yield", kind: "percent" },
  { key: "dividendYield", label: "Dividend yield", kind: "percent" },
  { key: "altmanZ", label: "Altman Z", kind: "number" },
  { key: "avgDailyValue", label: "Traded per day", kind: "compact" },
]
