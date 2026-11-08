// The Quant Score tab of a company (backend ScorecardService): the six fundamental agents' scorecards from preset
// rules, no model call.

import { formatMultiple, formatNumber, formatPercent } from "@/lib/format"

export type MetricUnit = "percent" | "points" | "x" | "log10" | "number"
export type MetricSource = "FILINGS" | "VALUATION" | "PRICES" | "DERIVED" | "YAHOO"
export type Verdict = "STRONG_FIT" | "FIT" | "NEUTRAL" | "WEAK" | "REJECT"

export type ScorecardCriterion = {
  key: string
  label: string
  unit: MetricUnit
  value: number | null
  /** worth 0 points */
  poor: number
  /** worth full points (below poor when lower is better) */
  good: number
  weight: number
  /** false: not applicable to this company, left out of the score */
  applies: boolean
  /** 0 - 1 of the weight earned; null = unknown or not applicable */
  points: number | null
  source: MetricSource | null
  basis: string | null
}

export type AgentScorecard = {
  agent: string
  label: string
  focus: string
  score: number | null
  coverage: number
  verdict: Verdict | null
  inOverall: boolean
  criteria: ScorecardCriterion[]
}

/** GET /api/v1/companies/{exchange}/{ticker}/scorecards */
export type Scorecards = {
  exchange: string
  ticker: string
  companyName: string
  financial: boolean
  overall: number | null
  verdict: Verdict | null
  riskWeight: number
  minCoverage: number
  screeningDate: string | null
  valuationDate: string | null
  fiscalYear: string | null
  pricesDate: string | null
  agents: AgentScorecard[]
  metrics: { key: string; value: number; unit: MetricUnit; source: MetricSource; basis: string | null }[]
  notes: string[]
}

/** One company of GET /api/v1/companies/quant-scores (the company list's column; backend QuantScoreBoard). */
export type QuantScore = {
  ticker: string
  /** the overall of the Quant Score tab; null when no agent has enough of its scorecard known */
  overall: number | null
  verdict: Verdict | null
  financial: boolean
  /** agents in the overall (of 6) */
  agentsCounted: number
}

/** GET /api/v1/companies/quant-scores?exchange= */
export type QuantScoreBoard = {
  exchange: string
  computedAt: string
  millis: number
  cached: boolean
  companies: number
  scored: number
  minCoverage: number
  riskWeight: number
  scores: QuantScore[]
}

export function quantScoresUrl(exchange: string): string {
  return `/api/companies/quant-scores?${new URLSearchParams({ exchange })}`
}

export const VERDICT_LABELS: Record<Verdict, string> = {
  STRONG_FIT: "Strong fit",
  FIT: "Fit",
  NEUTRAL: "Neutral",
  WEAK: "Weak",
  REJECT: "Reject",
}

export const SOURCE_LABELS: Record<MetricSource, string> = {
  FILINGS: "Filings",
  VALUATION: "Valuation",
  PRICES: "Prices",
  DERIVED: "Derived",
  YAHOO: "Yahoo",
}

/** A metric value in its unit: 0.182 percent -> "18.2%", 0.012 points -> "+1.2 pt", 14.1 x -> "14.10x". */
export function formatMetricValue(value: number | null | undefined, unit: MetricUnit): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return "—"
  switch (unit) {
    case "percent":
      return formatPercent(value, 1)
    case "points":
      return `${value >= 0 ? "+" : ""}${formatNumber(value * 100, 1)} pt`
    case "x":
      return formatMultiple(value)
    case "log10":
      // log10 of IDR traded per day: show the amount (10 -> "IDR 10B / day")
      return `IDR ${formatCompactAmount(10 ** value)} / day`
    default:
      return formatNumber(value, 2)
  }
}

function formatCompactAmount(value: number): string {
  return value.toLocaleString("en-US", { notation: "compact", maximumFractionDigits: 1 })
}

/** Tone of a score / points share. */
export function scoreTone(score: number | null): "good" | "mid" | "poor" | "none" {
  if (score === null) return "none"
  if (score >= 65) return "good"
  if (score >= 45) return "mid"
  return "poor"
}

/**
 * Rule-based sentences from the numbers (no AI): a criterion with at least 80% of its points is a strength, one with
 * at most 20% a concern, an unknown one a data gap.
 */
export function findings(card: AgentScorecard): { strengths: string[]; concerns: string[]; gaps: string[] } {
  const strengths: string[] = []
  const concerns: string[] = []
  const gaps: string[] = []
  for (const c of card.criteria) {
    if (!c.applies) continue
    const bounds = `poor ${formatMetricValue(c.poor, c.unit)}, good ${formatMetricValue(c.good, c.unit)}`
    if (c.points === null) {
      gaps.push(`${c.label}: unknown`)
    } else if (c.points >= 0.8) {
      strengths.push(`${c.label} ${formatMetricValue(c.value, c.unit)} (${bounds})`)
    } else if (c.points <= 0.2) {
      concerns.push(`${c.label} ${formatMetricValue(c.value, c.unit)} (${bounds})`)
    }
  }
  return { strengths, concerns, gaps }
}
