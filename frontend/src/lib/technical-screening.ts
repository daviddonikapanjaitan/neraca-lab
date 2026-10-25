// AI technical screening (docs/v1_docs/TECHNICAL_SCREENING_DOCS.md): Screening > Technicals (All) of a market-cap tier
// and Technicals (Selected) of stocks of the companies table; GET /api/v1/technical-screenings and its report.

import { EMPTY } from "@/lib/format"
import { TIER_LABEL } from "@/lib/screening"
import { TECHNICALS_ALL_HREF, TECHNICALS_SELECTED_HREF } from "@/lib/screening-sections"
import type {
  IngestionJobCreator,
  IngestionJobStatus,
  MarketCapTier,
  ScreeningOption,
  ScreeningScope,
  ScreeningUsage,
} from "@/lib/types"

export type TechnicalAgentCode = "GILL" | "MURPHY" | "TUDOR_JONES" | "DRUCKENMILLER"
export type TechnicalStance = "BUY" | "ACCUMULATE" | "WAIT" | "AVOID"
export type TechnicalConviction = "HIGH" | "MEDIUM" | "LOW"

/** Runs shown on a Technicals page (most recent first). */
export const TECHNICAL_RUN_LIMIT = 50

/** Short column labels of the agents (the order of the instruction and of the backend). */
export const TECHNICAL_AGENT_SHORT: Record<TechnicalAgentCode, string> = {
  GILL: "Gill",
  MURPHY: "Murphy",
  TUDOR_JONES: "Tudor Jones",
  DRUCKENMILLER: "Druckenmiller",
}

export const STANCE_LABEL: Record<TechnicalStance, string> = {
  BUY: "Buy",
  ACCUMULATE: "Accumulate",
  WAIT: "Wait",
  AVOID: "Avoid",
}

/** The stance badges of Companies › Technicals' preset summaries. */
export const STANCE_STYLE: Record<TechnicalStance, string> = {
  BUY: "border-emerald-600/30 bg-emerald-600/10 text-emerald-700 dark:text-emerald-400",
  ACCUMULATE: "border-sky-600/30 bg-sky-600/10 text-sky-700 dark:text-sky-400",
  WAIT: "border-amber-600/30 bg-amber-600/10 text-amber-700 dark:text-amber-400",
  AVOID: "border-rose-600/30 bg-rose-600/10 text-rose-700 dark:text-rose-400",
}

/** GET /api/v1/technical-screenings/options */
export interface TechnicalScreeningOptions {
  exchanges: ScreeningOption[]
  marketCapTiers: ScreeningOption[]
  agents: ScreeningOption[]
  defaultTopN: number
  maxTopN: number
  shortlistMultiplier: number
  maxShortlist: number
  budgetUsd: number
  agentModel: string
  synthesisModel: string
  /** fewest stored daily bars a stock needs */
  minBars: number
  data: {
    exchange: string
    /** active listings of the screening universe */
    listings: number
    /** of them with stored daily bars (Ingestion › Screening Data IDX) */
    withBars: number
    latestBarDate: string | null
    historyYears: number
    /** newest market data of the universe (market cap, liquidity) */
    marketDataDate: string | null
    activePricesJobId: string | null
  }
}

/** One technical screening as listed (and the head of the report). */
export interface TechnicalScreeningRun {
  id: string
  status: IngestionJobStatus
  stage: string | null
  message: string | null
  exchange: string
  /** null for selected stocks */
  marketCapTier: MarketCapTier | null
  /** the selected stocks; null for a market-cap tier */
  tickers: string[] | null
  topN: number
  agents: TechnicalAgentCode[]
  /** the latest trading day of the bars the run read */
  asOfDate: string | null
  universeCount: number | null
  eligibleCount: number | null
  shortlistCount: number | null
  selectedCount: number | null
  budgetUsd: number
  costUsd: number
  promptTokens: number
  completionTokens: number
  reasoningTokens: number
  cachedTokens: number
  modelCalls: number
  requestedAt: string
  startedAt: string | null
  finishedAt: string | null
  createdBy: IngestionJobCreator | null
}

/** A price level of a trade plan (the shape of the preset summaries' prices). */
export interface PlanLevel {
  price: number
  /** LIMIT: a pullback buy at or below the close; STOP: a breakout buy above it (the stop loss is a STOP) */
  type: "LIMIT" | "STOP"
  basis: string | null
  distancePct: number | null
}

/** A trade plan: buy, sell (target), stop loss. */
export interface TradePlan {
  buy: PlanLevel | null
  sell: PlanLevel | null
  stopLoss: PlanLevel | null
  rewardRisk: number | null
  /** RULES (a preset's rules), AI (an agent's own levels), SYNTHESIS (the final synthesis) */
  source: "RULES" | "AI" | "SYNTHESIS"
  /** the agent (AI / RULES) or the synthesis model */
  by: string | null
  /** why the plan is not the synthesis' own */
  note?: string | null
}

/** One preset summary of Companies › Technicals (the agent's rules prior). */
export interface RulesSummary {
  preset: "murphy" | "gill" | "ptj" | "druckenmiller"
  name: string
  style: string
  stance: TechnicalStance
  stanceLabel: string
  score: number
  maxScore: number
  buy: PlanLevel | null
  sell: PlanLevel | null
  stopLoss: PlanLevel | null
  rewardRisk: number | null
  thesis: string
  points: { tone: "POSITIVE" | "NEGATIVE" | "NEUTRAL"; text: string }[]
}

export interface TechnicalAgentScore {
  agent: TechnicalAgentCode
  label: string
  /** its preset's rules summary as 0 - 100 */
  priorScore: number | null
  llmScore: number | null
  finalScore: number | null
  stance: TechnicalStance | null
  thesis: string | null
  supports: string[] | null
  risks: string[] | null
  plan: TradePlan | null
  reflection: {
    issues?: { code: string; message: string }[]
    original?: { score: number; stance: string }
    revised?: { score: number; stance: string }
    note?: string | null
    changed?: boolean
    error?: string
  } | null
  status: "ASSESSED" | "REVISED" | "PRIOR_ONLY"
}

/** Key technicals of a candidate. */
export interface TechnicalMetrics {
  close?: number
  changePct?: number
  sma50?: number
  sma200?: number
  vsSma50Pct?: number
  vsSma200Pct?: number
  cross?: string
  adx14?: number
  rsi14?: number
  macdHistogram?: number
  momentum12_1Pct?: number
  atrPct?: number
  atrPctPercentile252?: number
  avgValue20?: number
  upDownVolumeRatio50?: number
  week52High?: number
  week52Low?: number
  range52Pct?: number
  athDrawdownPct?: number
  baseDetected?: boolean
  return6mPct?: number
  return12mPct?: number
  percentile6m?: number
  percentile12m?: number
  rsChange50Pct?: number
  tradingDays?: number
  firstDate?: string
}

export interface TechnicalCandidate {
  id: number
  ticker: string
  companyName: string
  sector: string | null
  close: number | null
  asOfDate: string | null
  marketCap: number | null
  /** in the companies table: its chart opens on Companies › Technicals */
  inCompanies: boolean
  metrics: TechnicalMetrics | null
  rules: { summaries: RulesSummary[]; caveats: string[] } | null
  priorOverall: number | null
  priorRank: number | null
  overallScore: number | null
  synthesisAdjustment: number | null
  finalRank: number | null
  selected: boolean
  action: TechnicalStance | null
  conviction: TechnicalConviction | null
  plan: TradePlan | null
  thesis: string | null
  redFlags: string[] | null
  agents: TechnicalAgentScore[]
}

/** GET /api/v1/technical-screenings/{id} */
export interface TechnicalScreeningReport {
  run: TechnicalScreeningRun
  funnel: { key: string; label: string; remaining: number }[] | null
  market: {
    index?: string
    indexDate?: string
    indexClose?: number
    indexSma200?: number
    indexAboveSma200?: boolean
    indexVsSma200Pct?: number
    breadthAbove200Pct?: number
    breadthCounted?: number
    breadthUniverse?: number
  } | null
  synthesis: {
    executiveSummary: string | null
    marketNotes: string[] | null
    model: string | null
    fallback: string | null
  } | null
  notes: {
    messages?: string[]
    lessonsLearned?: { agent: string; lesson: string; occurrencesInRun: number }[]
    lessonsApplied?: Record<string, string[]>
  } | null
  candidates: TechnicalCandidate[]
  usage: ScreeningUsage[]
}

/** GET /api/v1/technical-screenings/companies */
export interface TechnicalSelectableCompanies {
  exchange: string
  maxSelected: number
  minBars: number
  companies: {
    ticker: string
    companyName: string
    sector: string | null
    /** stored daily bars (null: none) */
    tradingDays: number | null
    firstDate: string | null
    lastDate: string | null
    marketCap: number | null
  }[]
}

/** "Large cap", or "5 selected stocks". */
export function technicalScopeLabel(run: Pick<TechnicalScreeningRun, "marketCapTier" | "tickers">): string {
  if (run.marketCapTier) return TIER_LABEL[run.marketCapTier]
  const n = run.tickers?.length ?? 0
  return `${n} selected ${n === 1 ? "stock" : "stocks"}`
}

export function technicalScope(run: Pick<TechnicalScreeningRun, "tickers">): ScreeningScope {
  return run.tickers ? "SELECTION" : "TIER"
}

/** The page a run was started from: Technicals (All) or Technicals (Selected). */
export function technicalRunsHref(run: Pick<TechnicalScreeningRun, "tickers">): string {
  return run.tickers ? TECHNICALS_SELECTED_HREF : TECHNICALS_ALL_HREF
}

/** The report page of a run, under the page it was started from. */
export function technicalReportHref(run: Pick<TechnicalScreeningRun, "id" | "tickers">): string {
  return `${technicalRunsHref(run)}/${run.id}`
}

/** "IDX Large cap · top 25 · 4 agents" */
export function technicalRunTitle(
  run: Pick<TechnicalScreeningRun, "marketCapTier" | "tickers" | "topN" | "agents" | "exchange">
): string {
  return `${run.exchange} ${technicalScopeLabel(run)} · top ${run.topN} · ${run.agents.length} ${
    run.agents.length === 1 ? "agent" : "agents"
  }`
}

/** An IDX price in whole rupiah (decimals only where it has them). */
export function formatIdr(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return EMPTY
  return value.toLocaleString("en-US", { maximumFractionDigits: 2 })
}

/** +3.4% / -12.0% */
export function formatSignedPct(value: number | null | undefined, digits = 1): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return EMPTY
  return `${value > 0 ? "+" : ""}${value.toFixed(digits)}%`
}

/** "Buy at or below" (a LIMIT, a pullback entry) or "Buy at or above" (a STOP, a breakout entry). */
export function buyLabel(level: PlanLevel | null | undefined): string {
  return level?.type === "STOP" ? "Buy at or above" : "Buy at or below"
}

/** Who set a plan, for its caption. */
export function planSourceLabel(plan: TradePlan | null | undefined): string {
  if (!plan) return EMPTY
  if (plan.source === "SYNTHESIS") return `Synthesis${plan.by ? ` (${plan.by})` : ""}`
  const agent = plan.by && plan.by in TECHNICAL_AGENT_SHORT ? TECHNICAL_AGENT_SHORT[plan.by as TechnicalAgentCode] : plan.by
  return plan.source === "AI" ? `${agent ?? "Agent"} (AI)` : `${agent ?? "Preset"} (rules)`
}
