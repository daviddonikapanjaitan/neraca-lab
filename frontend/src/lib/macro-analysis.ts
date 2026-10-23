// AI macro analysis of a country (docs/v1_docs/MACRO_ANALYSIS_DOCS.md): GET /api/v1/macro/analyses and its report.

import { EMPTY } from "@/lib/format"
import {
  MACRO_ANALYSIS_HREF,
  type MacroCountry,
  type MarketRow,
  type MacroIndexRow,
  type MacroPreset,
  type RegionalCurrencies,
  type Tone,
} from "@/lib/macro"
import type { IngestionJobCreator, IngestionJobStatus, ScreeningOption, ScreeningUsage } from "@/lib/types"

export type MacroAgentCode = "TUDOR_JONES" | "DRUCKENMILLER" | "SOROS" | "DALIO"
export type MacroStance = "BUY" | "ACCUMULATE" | "WAIT" | "AVOID"
export type MacroAnalysisAction = "BUY" | "ACCUMULATE" | "WAIT" | "SELL"
export type InvestabilityVerdict = "INVESTABLE" | "SELECTIVE" | "NOT_INVESTABLE"
export type Conviction = "HIGH" | "MEDIUM" | "LOW"

export const MACRO_AGENT_SHORT: Record<MacroAgentCode, string> = {
  TUDOR_JONES: "Tudor Jones",
  DRUCKENMILLER: "Druckenmiller",
  SOROS: "Soros",
  DALIO: "Dalio",
}

/** GET /api/v1/macro/analyses/options */
export interface MacroAnalysisOptions {
  countries: MacroCountry[]
  agents: ScreeningOption[]
  budgetUsd: number
  researchModel: string
  agentModel: string
  synthesisModel: string
  data: {
    macroSeries: number
    macroSeriesTotal: number
    commodities: number
    currencyPairs: number
    /** macro documents (RAG) stored per country code; a country without any is absent */
    macroDocuments: Record<string, number>
  }
}

/** One macro analysis as listed (and the head of the report). */
export interface MacroAnalysisRun {
  id: string
  status: IngestionJobStatus
  stage: string | null
  message: string | null
  country: string
  countryName: string | null
  indexCode: string
  indexName: string | null
  agents: MacroAgentCode[]
  /** investability 0 - 100 after the synthesis adjustment; null until finished */
  overallScore: number | null
  /** the Macro page's rules score as of the analysis */
  rulesScore: number | null
  verdict: InvestabilityVerdict | null
  action: MacroAnalysisAction | null
  conviction: Conviction | null
  asOfDate: string | null
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

/** GET /api/v1/macro/analyses: one page, most recent first */
export interface MacroAnalysisPage {
  total: number
  limit: number
  offset: number
  analyses: MacroAnalysisRun[]
}

export interface MacroAgentView {
  agent: MacroAgentCode
  label: string
  focus: string
  /** the agent's preset of the rules on 0 - 100 */
  priorScore: number | null
  priorDetail: { stance: string | null; stanceLabel: string | null; preset: string } | null
  aiScore: number | null
  finalScore: number | null
  stance: MacroStance | null
  thesis: string | null
  supports: string[] | null
  risks: string[] | null
  buyWhen: string[] | null
  sellWhen: string[] | null
  reflection: {
    issues?: { code: string; message: string }[]
    original?: { score: number; stance: string }
    revised?: { score: number; stance: string }
    note?: string | null
    changed?: boolean
    error?: string
  } | null
  status: "ASSESSED" | "REVISED" | "PRIOR_ONLY" | "NO_SCORE"
}

export interface MacroBriefView {
  regime: string | null
  liquidity: string | null
  inflationRates: string | null
  external: string | null
  growth: string | null
  risks: string[]
  catalysts: string[]
  /** facts with the ref of the tool result they come from (T1, T2, ...) */
  evidence: { ref: string; fact: string }[]
}

/** A macro document of the country the research agent could search. */
export interface MacroResearchDocument {
  documentId: number
  title: string
  issuer: string | null
  documentType: string
  documentTypeLabel: string
  /** publication date (yyyy-mm-dd) */
  publishedOn: string | null
  pages: number | null
  chunks: number
}

export interface MacroResearch {
  brief: MacroBriefView | null
  trace: { iteration: number; thought: string | null; tools: string[] }[]
  /** tool results: T1, T2, ... (history, correlation), D1, D2, ... (searchDocuments: excerpts of macro documents) */
  retrieved: { ref: string; tool: string; arguments: string; summary: string }[]
  /** the country's macro documents published by the as-of date, which the research could search (older reports: absent) */
  documents?: MacroResearchDocument[]
  modelUsed: boolean
  droppedRefs: number
  note: string | null
}

export interface SectorTilt {
  sector: string
  tilt: "OVERWEIGHT" | "NEUTRAL" | "UNDERWEIGHT"
  why: string | null
}

export interface MacroSynthesisView {
  executiveSummary: string
  conviction: Conviction | null
  thesis: string | null
  bullCase: string[]
  bearCase: string[]
  keyRisks: string[]
  monitor: string[]
  sectorTilts: SectorTilt[]
  dataGaps: string[]
  adjustmentReason: string | null
  agentsMean: number | null
  model: string | null
  fallback: string | null
}

/** What the agents saw: the Macro page's rules without the chart points. */
export interface MacroAnalysisContext {
  subject: { country: string; countryName: string; index: string; indexName: string }
  asOfDate: string
  index: MacroIndexRow & { high52w?: number | null; low52w?: number | null; sma200?: number | null }
  indexes: MacroIndexRow[]
  regimes: Record<"liquidity" | "risk", { state: string | null; score: number | null; signals: number; stateThen: string | null; scoreThen: number | null }>
  series: { code: string; group: string; title: string; unit: string; lastValue: number | null; lastDate: string | null; tone: Tone; summary: string; source: string | null }[]
  presets: MacroPreset[]
  rulesConclusion: { score: number | null; verdict: InvestabilityVerdict | null; verdictLabel: string; actionLabel: string; reason: string; summary: string } | null
  markets?: {
    termsOfTrade: { state: string | null; score: number | null; signals: number; summary: string }
    commodities: Omit<MarketRow, "points">[]
    currencyPairs: Omit<MarketRow, "points">[]
    /** the rupiah against its Asian peers; absent in analyses of before 2026-10-23 */
    regionalCurrencies?: RegionalCurrencies | null
  }
  rulesVersion: string
}

export interface MacroAnalysisReport {
  run: MacroAnalysisRun
  synthesisAdjustment: number | null
  context: MacroAnalysisContext | null
  research: MacroResearch | null
  synthesis: MacroSynthesisView | null
  notes: {
    messages?: string[]
    lessonsLearned?: { agent: string; lesson: string }[]
    lessonsApplied?: Record<string, string[]>
    budget?: { budgetUsd: number; spentUsd: number }
  } | null
  agents: MacroAgentView[]
  usage: ScreeningUsage[]
}

/** "Indonesia · IHSG" */
export function macroAnalysisTitle(run: Pick<MacroAnalysisRun, "countryName" | "country" | "indexCode">): string {
  return `${run.countryName ?? run.country} · ${run.indexCode}`
}

export function macroAnalysisHref(id: string): string {
  return `${MACRO_ANALYSIS_HREF}/${encodeURIComponent(id)}`
}

/** Text color of an investability score: green from 60, amber from 40, red below (the verdict bands). */
export function investabilityClass(score: number | null | undefined): string {
  if (score === null || score === undefined || !Number.isFinite(score)) return "text-muted-foreground"
  if (score >= 60) return "text-emerald-700 dark:text-emerald-400"
  if (score >= 40) return "text-amber-700 dark:text-amber-400"
  return "text-rose-700 dark:text-rose-400"
}

export const VERDICT_LABEL: Record<InvestabilityVerdict, string> = {
  INVESTABLE: "Investable",
  SELECTIVE: "Selective",
  NOT_INVESTABLE: "Not investable now",
}

export function verdictText(verdict: InvestabilityVerdict | null | undefined): string {
  return verdict ? VERDICT_LABEL[verdict] : EMPTY
}

export const ACTION_LABEL: Record<MacroAnalysisAction | MacroStance, string> = {
  BUY: "Buy",
  ACCUMULATE: "Accumulate",
  WAIT: "Wait",
  SELL: "Sell / reduce",
  AVOID: "Avoid",
}

export const ACTION_STYLE: Record<MacroAnalysisAction | MacroStance, string> = {
  BUY: "border-emerald-600/30 bg-emerald-600/10 text-emerald-700 dark:text-emerald-400",
  ACCUMULATE: "border-sky-600/30 bg-sky-600/10 text-sky-700 dark:text-sky-400",
  WAIT: "border-amber-600/30 bg-amber-600/10 text-amber-700 dark:text-amber-400",
  AVOID: "border-rose-600/30 bg-rose-600/10 text-rose-700 dark:text-rose-400",
  SELL: "border-rose-600/30 bg-rose-600/10 text-rose-700 dark:text-rose-400",
}

export const TILT_STYLE: Record<SectorTilt["tilt"], string> = {
  OVERWEIGHT: "text-emerald-700 dark:text-emerald-400",
  NEUTRAL: "text-muted-foreground",
  UNDERWEIGHT: "text-rose-700 dark:text-rose-400",
}
