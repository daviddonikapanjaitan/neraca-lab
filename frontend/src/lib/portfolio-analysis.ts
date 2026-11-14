// Portfolio > Analysis (backend PortfolioAnalysisController, /api/v1/portfolio/analyses; instruction/INSTRUCTION.md):
// AI recommendations to sell, decrease, hold, increase or buy, and the cash to keep. Step 1 the Fundamental (Score),
// step 2 the Technical (Score) of step 1's stocks (never combined), then the fundamental and technical DeepSeek agents
// and the Opus synthesis; the limits (stocks held, 33% per stock and per sector, half-Kelly, lots) are enforced by the
// backend. Amounts in IDR, weights as fractions (0.33 = 33%).

import { PORTFOLIO_ANALYSIS_HREF } from "@/lib/portfolio"
import type { IngestionJobCreator, IngestionJobStatus, ScreeningUsage } from "@/lib/types"

export { PORTFOLIO_ANALYSIS_HREF }
export const ANALYSIS_RUN_LIMIT = 50

export function analysisReportHref(id: string): string {
  return `${PORTFOLIO_ANALYSIS_HREF}/${encodeURIComponent(id)}`
}

export type AnalysisMode = "BULK" | "SELECTED"
/** FUNDAMENTAL_TECHNICAL: the fundamental step then the technical step; FUNDAMENTAL: the fundamental step only */
export type AnalysisSteps = "FUNDAMENTAL_TECHNICAL" | "FUNDAMENTAL"

export const STEPS_LABEL: Record<AnalysisSteps, string> = {
  FUNDAMENTAL_TECHNICAL: "Fundamental + technical",
  FUNDAMENTAL: "Fundamental only",
}
export type AnalysisAction = "BUY" | "INCREASE" | "HOLD" | "DECREASE" | "SELL"
export type AnalysisStage =
  | "FUNDAMENTAL_OUT"
  | "TECHNICAL_OUT"
  | "FUNDAMENTAL_AI_OUT"
  | "TECHNICAL_AI_OUT"
  | "RECOMMENDED"
  | "NOT_CHOSEN"
  | "HELD"

export type AnalysisSelectableCompany = {
  ticker: string
  companyName: string
  sector: string | null
  fundamentalScore: number | null
  fundamentalVerdict: string | null
  technicalScore: number | null
  technicalStance: string | null
  held: boolean
}

/** GET /api/v1/portfolio/analyses/options */
export type AnalysisOptions = {
  exchange: string
  defaultMaxStocks: number
  maxStocksLimit: number
  maxSelected: number
  minFundamentalScore: number
  fundamentalMultiplier: number
  maxFundamentalShortlist: number
  technicalMultiplier: number
  maxTechnicalShortlist: number
  maxPositionWeight: number
  maxSectorWeight: number
  kellyFraction: number
  lotSize: number
  budgetUsd: number
  agentModel: string
  synthesisModel: string
  portfolio: { cash: number; marketValue: number; total: number; positions: number; held: string[] }
  companies: AnalysisSelectableCompany[]
  scores: { fundamentalScored: number; technicalScored: number; fundamentalComputedAt: string; technicalComputedAt: string }
}

export type AnalysisRun = {
  id: string
  status: IngestionJobStatus
  stage: string | null
  message: string | null
  exchange: string
  mode: AnalysisMode
  steps: AnalysisSteps
  tickers: string[] | null
  maxStocks: number
  addedCapital: number
  budgetUsd: number
  costUsd: number
  modelCalls: number
  promptTokens: number
  completionTokens: number
  requestedAt: string
  startedAt: string | null
  finishedAt: string | null
  createdBy: IngestionJobCreator | null
  /** stocks recommended (bought, increased, held, decreased) */
  recommended: number | null
  /** the capital at the start (market value + cash + added) */
  capital: number | null
}

export type AnalysisIssue = { kind: string; message: string }

export type AgentDoc<T> = {
  rulesScore: number | null
  finalScore: number | null
  status: "ASSESSED" | "REVISED" | "RULES_ONLY" | "NOT_ASKED"
  answer?: T | null
  original?: T | null
  issues?: AnalysisIssue[]
  remainingIssues?: AnalysisIssue[]
  error?: string | null
  note?: string
}

export type FundamentalAnswer = {
  score: number | null
  verdict: "PASS" | "FAIL" | null
  thesis: string | null
  strengths: string[]
  risks: string[]
  note: string | null
}

export type TechnicalAnswer = {
  score: number | null
  stance: string | null
  entry: number | null
  entryType: string | null
  stopLoss: number | null
  target: number | null
  thesis: string | null
  risks: string[]
  note: string | null
}

export type PlanLevel = { price: number; type: string; basis: string | null; distancePct: number | null }

export type TradePlanDoc = {
  buy: PlanLevel | null
  sell: PlanLevel | null
  stopLoss: PlanLevel | null
  rewardRisk: number | null
  source: string
  by: string
}

export type Recommendation = {
  action?: AnalysisAction
  price?: number | null
  currentShares?: number
  currentValue?: number
  currentWeight?: number
  proposedWeight?: number | null
  targetWeight?: number
  targetShares?: number
  targetValue?: number
  tradeShares?: number
  tradeLots?: number
  tradeValue?: number
  reason?: string | null
  adjustments?: string[]
}

export type AnalysisCandidate = {
  id: number
  ticker: string
  companyName: string | null
  sector: string | null
  held: boolean
  position: {
    shares: number
    averagePrice: number
    lastPrice: number | null
    priceDate: string | null
    averageValue: number
    marketValue: number | null
    gainLoss: number | null
    gainLossPct: number | null
    weight: number | null
  } | null
  close: number | null
  priceDate: string | null
  fundamentalScore: number | null
  fundamentalVerdict: string | null
  fundamentalRank: number | null
  technicalScore: number | null
  technicalStance: string | null
  technicalRank: number | null
  stage: AnalysisStage
  fundamentalAi: (AgentDoc<FundamentalAnswer> & { verdict: "PASS" | "FAIL" }) | null
  technicalAi: (AgentDoc<TechnicalAnswer> & { stance: string | null; plan: TradePlanDoc | null; planSource: string | null }) | null
  kelly: { winProbability: number; payoff: number | null; kellyFraction: number | null; weight: number; note: string } | null
  action: AnalysisAction | null
  recommendation: Recommendation | null
  conviction: string | null
  thesis: string | null
  redFlags: string[]
  finalRank: number | null
}

export type AnalysisCheck = { name: string; ok: boolean; detail: string }

/** GET /api/v1/portfolio/analyses/{id} */
export type AnalysisReport = {
  run: AnalysisRun
  capital: {
    cash: number
    addedCapital: number
    marketValue: number
    averageValue: number
    total: number
    positions: number
    priced: number
    totalInvestment: number
    computedAt: string
  } | null
  funnel: {
    universe: number
    held: number
    maxStocks: number
    mode: AnalysisMode
    candidates: number
    fundamental: { scored: number; minScore: number; aboveMin: number; keep: number; passed: number; rule: string }
    technical: { used?: boolean; inputs: number; scored: number; keep: number; passed: number; rule: string }
    steps?: AnalysisSteps
  } | null
  synthesis: {
    executiveSummary: string | null
    riskNotes: string[]
    cashTarget: number | null
    model: string | null
    fallback: string | null
  } | null
  allocation: {
    capital: number
    cashBefore: number
    cashAfter: number
    cashWeightBefore: number
    cashWeightAfter: number
    sectorsBefore: Record<string, number>
    sectorsAfter: Record<string, number>
    buyValue: number
    sellValue: number
    stocksBefore: number
    stocksAfter: number
    limits: {
      maxStocks: number
      maxPositionWeight: number
      maxSectorWeight: number
      kellyFraction: number
      lotSize: number
      minPositionWeight: number
    }
  } | null
  checks: AnalysisCheck[] | null
  notes: {
    messages?: string[]
    lessonsLearned?: { agent: string; lesson: string; occurrencesInRun: number }[]
    lessonsApplied?: Record<string, string[]>
    budget?: { budgetUsd: number; spentUsd: number; reviewed: number }
  } | null
  candidates: AnalysisCandidate[]
  usage: ScreeningUsage[]
  disclaimer: string
}

export const ACTION_ORDER: AnalysisAction[] = ["BUY", "INCREASE", "HOLD", "DECREASE", "SELL"]

export const ACTION_LABEL: Record<AnalysisAction, string> = {
  BUY: "Buy",
  INCREASE: "Increase",
  HOLD: "Hold",
  DECREASE: "Decrease",
  SELL: "Sell",
}

export const STAGE_LABEL: Record<AnalysisStage, string> = {
  FUNDAMENTAL_OUT: "Left out at step 1 (Fundamental)",
  TECHNICAL_OUT: "Left out at step 2 (Technical)",
  FUNDAMENTAL_AI_OUT: "Rejected by the fundamental agent",
  TECHNICAL_AI_OUT: "Not timely (technical agent)",
  RECOMMENDED: "Recommended",
  NOT_CHOSEN: "Passed, not chosen",
  HELD: "Held",
}

export function analysisRunTitle(run: AnalysisRun): string {
  const scope = run.mode === "BULK" ? "All companies" : `${run.tickers?.length ?? 0} selected`
  return `${scope} · ${STEPS_LABEL[run.steps] ?? STEPS_LABEL.FUNDAMENTAL_TECHNICAL} · at most ${run.maxStocks} stocks`
}

/** The run has the technical step and agent (runs before the choice existed have both). */
export function hasTechnicalSteps(run: AnalysisRun): boolean {
  return run.steps !== "FUNDAMENTAL"
}
