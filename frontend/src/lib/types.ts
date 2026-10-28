import type { OwnershipDigest } from "@/lib/ownership"
import type { OnePercentDigest } from "@/lib/ownership-one-percent"

// Response types of the Neraca Lab backend (docs/v1_docs/COMPANY_API_DOCS.md).
// Amounts are full units of the company currency; null = not reported.

export type Amount = number | null

export interface Exchange {
  code: string
  name: string
  country: string
}

export interface CompanySummary {
  companyId: number
  ticker: string
  exchange: string
  companyName: string
  legalName: string | null
  sector: string | null
  industry: string | null
  country: string | null
  currency: string | null
  fiscalYearEnd: string | null
  active: boolean
  periodCount: number
  firstPeriodEnd: string | null
  latestPeriod: string | null
  latestPeriodEnd: string | null
  latestPriceDate: string | null
}

export interface CompanyListResponse {
  exchange: string
  exchangeName: string
  count: number
  companies: CompanySummary[]
}

export interface Company {
  companyId: number
  ticker: string
  exchange: string
  exchangeName: string
  cik: string | null
  companyName: string
  legalName: string | null
  sector: string | null
  industry: string | null
  country: string | null
  currency: string | null
  fiscalYearEnd: string | null
  ipoDate: string | null
  active: boolean
  createdAt: string
  updatedAt: string
}

export interface Coverage {
  periods: number
  firstPeriodEnd: string | null
  latestPeriodEnd: string | null
  incomeStatements: number
  balanceSheets: number
  cashFlowStatements: number
  segments: number
  segmentFinancials: number
  priceDays: number
  firstPriceDate: string | null
  latestPriceDate: string | null
  shareSnapshots: number
  marketSnapshots: number
  valuationSnapshots: number
  financialMetrics: number
  corporateActions: number
}

export interface IncomeStatement {
  revenue: Amount
  costOfRevenue: Amount
  grossProfit: Amount
  operatingExpenses: Amount
  sgaExpense: Amount
  rdExpense: Amount
  depreciation: Amount
  amortization: Amount
  operatingIncome: Amount
  ebit: Amount
  ebitda: Amount
  interestIncome: Amount
  interestExpense: Amount
  pretaxIncome: Amount
  incomeTax: Amount
  netIncome: Amount
  netIncomeToParent: Amount
  basicEps: Amount
  dilutedEps: Amount
  basicShares: Amount
  dilutedShares: Amount
}

export interface BalanceSheet {
  cashAndEquivalents: Amount
  marketableSecurities: Amount
  accountsReceivable: Amount
  inventory: Amount
  currentAssets: Amount
  totalAssets: Amount
  accountsPayable: Amount
  deferredRevenue: Amount
  currentLiabilities: Amount
  totalLiabilities: Amount
  /** banks with a sharia unit: neither liabilities nor equity (assets = liabilities + these + equity) */
  temporarySyirkahFunds: Amount
  shortTermDebt: Amount
  longTermDebt: Amount
  leaseLiabilities: Amount
  shareholdersEquity: Amount
  nonControllingInterest: Amount
  totalEquity: Amount
  retainedEarnings: Amount
  goodwill: Amount
  intangibleAssets: Amount
  sharesOutstanding: Amount
}

export interface CashFlowStatement {
  operatingCashFlow: Amount
  capitalExpenditure: Amount
  investingCashFlow: Amount
  financingCashFlow: Amount
  acquisitions: Amount
  shareBuybacks: Amount
  stockIssuance: Amount
  dividendsPaid: Amount
  debtIssued: Amount
  debtRepaid: Amount
  leasePayments: Amount
  cashChange: Amount
  endingCash: Amount
}

export interface SegmentFigures {
  segmentId: number
  segmentType: string
  segmentName: string
  segmentNameEn: string | null
  revenue: Amount
  costOfRevenue: Amount
  grossProfit: Amount
  operatingIncome: Amount
  totalAssets: Amount
}

export interface Metric {
  category: string | null
  value: Amount
  unit: string | null
}

export interface Period {
  periodId: number
  /** e.g. "2026 H1" */
  period: string
  fiscalYear: number
  fiscalQuarter: number | null
  /** FY, Q1-Q4, H1, 9M or TTM (H1 / 9M are year-to-date) */
  periodType: string
  periodStart: string | null
  periodEnd: string
  filingDate: string | null
  sourceFiling: string | null
  audited: boolean | null
  incomeStatement: IncomeStatement | null
  balanceSheet: BalanceSheet | null
  cashFlowStatement: CashFlowStatement | null
  segments: SegmentFigures[]
  metrics: Record<string, Metric>
}

export interface Segment {
  segmentId: number
  segmentType: string
  segmentName: string
  segmentNameEn: string | null
  description: string | null
  active: boolean
}

export interface ShareSnapshot {
  snapshotDate: string
  basicShares: Amount
  dilutedShares: Amount
  sharesOutstanding: Amount
  publicFloat: Amount
  treasuryShares: Amount
}

/**
 * Prices as quoted on the exchange, for a company whose prices are stored converted into its reporting currency
 * (CUAN, DSSA, INDY: USD reporters quoted in IDR): stored price x fxRate, to 2 decimals.
 */
export interface Quote {
  currency: string
  openPrice: Amount
  highPrice: Amount
  lowPrice: Amount
  closePrice: Amount
  /** exchange currency per reporting-currency unit (IDR per USD) */
  fxRate: Amount
  fxDate: string | null
}

export interface Price {
  tradingDate: string
  openPrice: Amount
  highPrice: Amount
  lowPrice: Amount
  closePrice: Amount
  adjustedClose: Amount
  volume: number | null
  /** null when the company reports in the listing currency */
  quote: Quote | null
}

export interface MarketSnapshot {
  snapshotDate: string
  sharePrice: Amount
  sharesOutstanding: Amount
  marketCap: Amount
  enterpriseValue: Amount
  quoteCurrency: string | null
  quotePrice: Amount
}

export interface Valuation {
  valuationDate: string
  periodId: number | null
  period: string | null
  sharePrice: Amount
  marketCap: Amount
  enterpriseValue: Amount
  epsTtm: Amount
  revenueTtm: Amount
  ebitdaTtm: Amount
  operatingIncomeTtm: Amount
  fcfTtm: Amount
  bookValue: Amount
  peRatio: Amount
  psRatio: Amount
  pbRatio: Amount
  evEbitda: Amount
  /** the EBITDA of evEbitda: "TTM", or "FY 2025" (the full year, when the trailing twelve months are not derivable) */
  ebitdaBasis: string | null
  evSales: Amount
  evOp: Amount
  fcfYield: Amount
  earningsYield: Amount
  quoteCurrency: string | null
  quotePrice: Amount
}

export interface CorporateAction {
  actionDate: string
  actionType: string
  ratioFrom: Amount
  ratioTo: Amount
  sharesIssued: Amount
  cashRaised: Amount
  description: string | null
}

export interface CompanyDetail {
  company: Company
  coverage: Coverage
  /** most recent first */
  periods: Period[]
  segments: Segment[]
  shareSnapshots: ShareSnapshot[]
  latestPrice: Price | null
  latestMarketSnapshot: MarketSnapshot | null
  valuations: Valuation[]
  corporateActions: CorporateAction[]
}

// Ingestion jobs (docs/v1_docs/INGESTION_JOBS_DOCS.md)

export type IngestionJobType =
  | "FINANCIAL_STATEMENT"
  | "PRICE"
  | "FUNDAMENTALS"
  | "SCREENING"
  | "RAG_PDF"
  | "RAG_NEWS"
  | "ANALYSIS"
  | "MACRO"
  | "MACRO_ANALYSIS"
  | "LISTING_PRICES"
  | "TECHNICAL_SCREENING"
  | "RAG_XLSX"

/** PDF: an uploaded PDF; NEWS: a news article; XLSX: a company's document built from Excel lists (its ownership) */
export type RagSourceType = "PDF" | "NEWS" | "XLSX"

/**
 * What a PDF of a company is (Ingestion > PDF Documents (RAG)): a financial statement (the default) or a monthly
 * registration report of securities holders, read into the shares of the directors and commissioners (Companies >
 * Registration).
 */
export type CompanyDocumentType = "FINANCIAL_STATEMENT" | "REGISTRATION"

export interface CompanyDocumentTypeOption {
  code: CompanyDocumentType
  label: string
  examples: string
}

/** GET /api/v1/rag/status: settings of the RAG vector store and what it holds. */
export interface RagStatus {
  embeddingModel: string
  embeddingDimensions: number
  chunkChars: number
  chunkOverlap: number
  newsMaxArticles: number
  newsMaxPages: number
  maxRangeDays: number
  /** RAG jobs waiting in the queue */
  pending: number
  /**
   * pdfDocuments: of the companies; macroDocuments: of the countries; xlsxDocuments: the companies' ownership
   * documents (KSEI lists of the shareholders of more than 5%); chunks: all
   */
  stored: { pdfDocuments: number; newsDocuments: number; macroDocuments: number; chunks: number; xlsxDocuments?: number }
  /** the countries of the macro documents (only the available ones take uploads) */
  macroCountries: MacroDocumentCountry[]
  macroDocumentTypes: MacroDocumentTypeOption[]
  /** the types of a company PDF, the default first */
  companyDocumentTypes?: CompanyDocumentTypeOption[]
}

/** A country of the macro documents (Indonesia; the United States and China later) and its stored documents. */
export interface MacroDocumentCountry {
  code: string
  name: string
  available: boolean
  documents: number
}

export type MacroDocumentType =
  | "MONETARY_POLICY"
  | "FINANCIAL_STABILITY"
  | "FISCAL"
  | "EXTERNAL_SECTOR"
  | "ECONOMIC_OUTLOOK"
  | "STATISTICS"
  | "DEVELOPMENT_PLAN"
  | "RESEARCH"
  | "OTHER"

export interface MacroDocumentTypeOption {
  code: MacroDocumentType
  label: string
  /** e.g. "Bank Indonesia board statements, Monetary Policy Review and Report" */
  examples: string
}

/** A macro PDF of a country in the vector store (GET /api/v1/rag/macro/documents). */
export interface MacroRagDocument {
  documentId: number
  country: string
  title: string
  issuer: string | null
  documentType: MacroDocumentType
  /** publication date: 00:00 Jakarta time as an instant */
  publishedAt: string
  fileName: string | null
  pages: number | null
  characters: number
  chunks: number
  embeddingModel: string
  updatedAt: string
}

/** GET /api/v1/rag/macro/documents: one page, latest publication first. */
export interface MacroRagDocumentPage {
  total: number
  limit: number
  offset: number
  documents: MacroRagDocument[]
}

/** A chunk of a macro document found by GET /api/v1/rag/macro/search. */
export interface MacroRagHit {
  chunkId: number
  documentId: number
  country: string
  title: string
  issuer: string | null
  documentType: MacroDocumentType
  publishedAt: string
  fileName: string | null
  pageFrom: number | null
  pageTo: number | null
  content: string
  distance: number
}

/** A chunk found by GET /api/v1/rag/search (cosine distance: 0 = same direction). */
export interface RagHit {
  chunkId: number
  documentId: number
  ticker: string
  sourceType: RagSourceType
  title: string
  sourceUrl: string | null
  fileName: string | null
  publishedAt: string | null
  pageFrom: number | null
  pageTo: number | null
  content: string
  distance: number
}

/** A document of the RAG vector store (GET /api/v1/rag/documents): a PDF or a news article of a company. */
export interface RagDocument {
  documentId: number
  exchange: string
  ticker: string
  companyName: string
  sourceType: RagSourceType
  /** a PDF's type; null for a news article */
  documentType: CompanyDocumentType | null
  title: string
  /** "PDF upload", or the news site */
  sourceName: string | null
  sourceUrl: string | null
  fileName: string | null
  publishedAt: string | null
  pages: number | null
  characters: number
  chunks: number
  embeddingModel: string
  updatedAt: string
}

/** GET /api/v1/rag/documents: one page of stored documents. */
export interface RagDocumentPage {
  /** documents matching the filters (every page together) */
  total: number
  limit: number
  offset: number
  /** most recently stored first */
  documents: RagDocument[]
}

export type IngestionJobStatus =
  | "QUEUED"
  | "RUNNING"
  | "WAITING_RATE_LIMIT"
  | "SUCCEEDED"
  | "INCOMPLETE"
  | "FAILED"

export interface IngestionFile {
  fileId: number
  fileName: string
  sizeBytes: number
  checksumSha256: string
  /** the same content was already stored, so the stored file was used */
  reused: boolean
}

export interface IngestionJob {
  id: string
  type: IngestionJobType
  status: IngestionJobStatus
  /** current step of an active job, or a one-line summary of a finished one */
  stage: string | null
  exchange: string | null
  ticker: string | null
  file: IngestionFile | null
  fullHistory: boolean | null
  attempts: number
  message: string | null
  requestedAt: string
  startedAt: string | null
  finishedAt: string | null
  resumeAt: string | null
  updatedAt: string
  /** who started the job (upload or price request); null for a scheduled run */
  createdBy: IngestionJobCreator | null
  /** only from GET /api/v1/ingestions/{id} */
  result: unknown
  /**
   * tokens and cost of the job's model calls so far; null for a job without model calls (prices, PDF, ...)
   * and for uploads from before the cost was recorded
   */
  cost: IngestionJobCost | null
  /** the model calls per stage and model; only from GET /api/v1/ingestions/{id} */
  usage: ScreeningUsage[] | null
}

/** Tokens and cost of all model calls of a job (llm_usage). */
export interface IngestionJobCost {
  /** attempts made, failed ones included */
  modelCalls: number
  /** attempts that failed (timeout, provider error): no tokens, no cost */
  failedCalls: number
  promptTokens: number
  completionTokens: number
  reasoningTokens: number
  cachedTokens: number
  /** USD, as reported by OpenRouter */
  costUsd: number
  /** a call had no reported cost and was priced from the list prices */
  costEstimated: boolean
}

export interface IngestionJobCreator {
  /** null when the user has been deleted since */
  userId: number | null
  /** username when the job was started */
  username: string
  /** current full name of the user */
  fullName: string | null
}

export interface IngestionJobList {
  counts: Record<IngestionJobStatus, number>
  active: number
  /** jobs matching the type and status filter (every page together) */
  total: number
  limit: number
  offset: number
  /** most recent first, one page (limit jobs from offset), without results */
  jobs: IngestionJob[]
}

/** GET /api/v1/prices/ingestions (its in-memory job list is not used: the page reads ingestion_job) */
export interface PriceQueue {
  /** yahoo | eodhd */
  provider: string
  /** jobs waiting in the price queue */
  pending: number
}

/** The exchange's index (IHSG) in index_daily */
export interface IndexPriceStatus {
  code: string
  /** at the source: ^JKSE */
  symbol: string
  name: string
  /** yahoo */
  source: string
  rows: number
  firstDate: string | null
  lastDate: string | null
  /** the index and the indexes stored with it (LQ45) all reach the last completed trading day */
  upToDate: boolean
  /** every index the job stores, IHSG first, then LQ45 */
  indexes?: IndexEntry[]
}

/** One index of the index job (IHSG, LQ45) */
export interface IndexEntry {
  code: string
  symbol: string
  name: string
  rows: number
  firstDate: string | null
  lastDate: string | null
  upToDate: boolean
}

/** GET /api/v1/prices/status?exchange= : how far the stored prices reach */
export interface PriceStatus {
  exchange: string
  /** the latest day whose bar is final: today from 17:00 WIB, else the trading day before */
  lastCompletedTradingDay: string
  /** active companies of the exchange */
  companies: number
  upToDate: number
  /** companies whose prices end before the last completed trading day */
  behind: number
  withoutPrices: number
  oldestLatestDate: string | null
  newestLatestDate: string | null
  index: IndexPriceStatus | null
  provider: string
  pending: number
}

/** POST /api/v1/prices/updates?exchange=&index= : what "Update all companies" queued */
export interface PriceUpdate {
  exchange: string
  lastCompletedTradingDay: string
  companies: number
  /** queued now: only the days after each company's latest stored day */
  queued: number
  /** already queued or running (kept) */
  alreadyActive: number
  /** skipped: their prices reach the last completed trading day */
  upToDate: number
  /** of the queued, companies without prices (their whole history) */
  withoutPrices: number
  index: { code: string; outcome: "QUEUED" | "ALREADY_ACTIVE" | "UP_TO_DATE"; job: IngestionJob | null } | null
  /** the first job queued, for the jobs table to follow */
  firstJob: IngestionJob | null
}

// Forex pairs and commodities (docs/v1_docs/PRICE_INGESTION_DOCS.md)

/** An entry of the Technicals dropdown: an exchange (IDX) or a market (Forex, Commodities) */
export interface TechnicalMarket {
  code: string
  name: string
  kind: "EXCHANGE" | "MARKET"
  /** Ticker, Currency, Commodity */
  itemLabel: string
}

/** A market beside the stock exchanges (Price Ingestion, Technicals) */
export type MarketCode = "FOREX" | "COMMODITY"

/** One instrument of a market and how far its bars reach */
export interface InstrumentStatus {
  /** USDIDR, GOLD */
  code: string
  /** USD/IDR, Gold */
  name: string
  /** at the source: IDR=X; the World Bank column of coal */
  symbol: string
  /** yahoo | worldbank | imf | fred */
  source: string
  /** "IDR per USD", "USD per barrel" */
  unit: string
  quoteCurrency: string
  frequency: "DAILY" | "MONTHLY"
  description: string | null
  rows: number
  firstDate: string | null
  lastDate: string | null
  /** the latest bar that is final now: the weekday before today, or the month before */
  lastCompleted: string
  upToDate: boolean
}

/** GET /api/v1/markets(/{market}) */
export interface MarketStatus {
  market: MarketCode
  /** Forex, Commodities */
  name: string
  /** Currency, Commodity */
  itemLabel: string
  itemsLabel: string
  instruments: number
  upToDate: number
  behind: number
  withoutPrices: number
  items: InstrumentStatus[]
  pending: number
}

/** POST /api/v1/markets/{market}/updates */
export interface MarketUpdate {
  market: MarketCode
  instruments: number
  queued: number
  alreadyActive: number
  upToDate: number
  withoutPrices: number
  firstJob: IngestionJob | null
}

// Macro data (docs/v1_docs/MACRO_DOCS.md)

export type MacroState = "UP_TO_DATE" | "BEHIND" | "NEVER_FETCHED" | "FAILING" | "MANUAL"

/** One macro series with what is stored of it */
export interface MacroSeriesStatus {
  code: string
  name: string
  /** ID, US, CN, GLOBAL */
  country: string
  category: string
  unit: string
  frequency: "DAILY" | "WEEKLY" | "MONTHLY" | "QUARTERLY" | "EVENT"
  /** fred | yahoo | bi | bi_seki | cnbc | cftc | manual */
  source: string
  sourceLabel: string
  sourceUrl: string | null
  releaseLagDays: number
  priority: number
  /** DRUCKENMILLER, TUDOR_JONES */
  usedBy: string[]
  description: string | null
  rows: number
  firstDate: string | null
  lastDate: string | null
  lastValue: number | null
  /** the day the latest observation became public */
  lastRelease: string | null
  updatedAt: string | null
  /** the latest observation that should be public today */
  expectedLatest: string
  state: MacroState
  lastJobStatus: string | null
  lastJobMessage: string | null
}

/** GET /api/v1/macro/series */
export interface MacroStatus {
  today: string
  series: number
  upToDate: number
  behind: number
  neverFetched: number
  failing: number
  manual: number
  pending: number
  items: MacroSeriesStatus[]
}

/** POST /api/v1/macro/updates */
export interface MacroUpdate {
  series: number
  queued: number
  alreadyActive: number
  upToDate: number
  manual: number
  firstJob: { id: string } | null
}

/** Result of a macro job or of a CSV upload */
export interface MacroResult {
  source: string
  requests: number
  requestedFrom: string | null
  fullHistory: boolean
  received: number
  kept: number
  inserted: number
  revised: number
  unchanged: number
  latestDate: string | null
  latestRelease: string | null
  warnings: string[]
  revisions: string[]
}

/** GET /api/v1/macro/series/{code}: [date, value, released] */
export interface MacroHistory {
  series: MacroSeriesStatus
  points: [string, number, string][]
}

// Users, roles and permissions (docs/v1_docs/AUTH_DOCS.md)

export type Permission = "ADMIN" | "INGESTION" | "COMPANIES" | "SCREENING"

export interface UserRoleRef {
  id: number
  name: string
  /** the built-in Administrator role */
  system: boolean
}

export interface User {
  id: number
  username: string
  email: string
  fullName: string | null
  address: string | null
  phone: string | null
  /** ISO date */
  dob: string | null
  active: boolean
  /** the root user (admin): cannot be deleted, deactivated or lose the Administrator role */
  root: boolean
  hasAvatar: boolean
  avatarUpdatedAt: string | null
  roles: UserRoleRef[]
  /** union of the permissions of all roles */
  permissions: Permission[]
  createdAt: string
  updatedAt: string
}

export interface Role {
  id: number
  name: string
  description: string | null
  /** the built-in Administrator role: only its description can be changed */
  system: boolean
  permissions: Permission[]
  userCount: number
  createdAt: string
  updatedAt: string
}

export interface PermissionInfo {
  code: Permission
  label: string
  description: string
}

// AI stock screening (docs/v1_docs/SCREENING_DOCS.md)

export type MarketCapTier = "LARGE" | "MID" | "SMALL"

export type InvestorAgentCode = "BUFFETT" | "MUNGER" | "LYNCH" | "FISHER" | "GILL" | "RISK"

export interface ScreeningOption {
  code: string
  label: string
  description: string
}

/** GET /api/v1/screenings/options */
export interface ScreeningOptions {
  exchanges: ScreeningOption[]
  marketCapTiers: ScreeningOption[]
  agents: ScreeningOption[]
  defaultTopN: number
  maxTopN: number
  shortlistMultiplier: number
  maxShortlist: number
  budgetUsd: number
  data: {
    exchange: string
    listings: number
    latestSnapshotDate: string | null
    withFundamentals: number
    /** queued / running screening data ETL, if any */
    activeEtlJobId: string | null
  }
}

/** A company of the companies table that can be selected (GET /api/v1/screenings/companies) */
export interface SelectableCompany {
  ticker: string
  companyName: string
  sector: string | null
  /** latest market data of its Yahoo Finance listing; null when none is stored */
  marketDataDate: string | null
  marketCap: number | null
  /** fundamentals loaded */
  fundamentals: boolean
}

/** GET /api/v1/screenings/companies?exchange= */
export interface SelectableCompanies {
  exchange: string
  /** most stocks one screening of selected stocks takes */
  maxSelected: number
  companies: SelectableCompany[]
}

/** Which runs a list holds: screenings of a market-cap tier or of selected stocks. */
export type ScreeningScope = "TIER" | "SELECTION"

/** One run as listed (and the head of the report). */
export interface ScreeningRun {
  id: string
  status: IngestionJobStatus
  stage: string | null
  message: string | null
  exchange: string
  /** null for a screening of selected stocks */
  marketCapTier: MarketCapTier | null
  /** the selected stocks; null for a screening of a market-cap tier */
  tickers: string[] | null
  topN: number
  agents: InvestorAgentCode[]
  snapshotDate: string | null
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

export interface QuantPart {
  key: string
  label: string
  value: number | null
  weight: number
  points: number | null
}

export interface AgentReflection {
  issues?: { code: string; message: string }[]
  original?: { score: number; verdict: string }
  revised?: { score: number; verdict: string }
  note?: string | null
  changed?: boolean
  error?: string
}

export interface AgentScore {
  agent: InvestorAgentCode
  label: string
  quantScore: number | null
  quantDetail: { score: number; coverage: number; parts: QuantPart[] } | null
  llmScore: number | null
  finalScore: number | null
  verdict: string | null
  thesis: string | null
  strengths: string[] | null
  concerns: string[] | null
  reflection: AgentReflection | null
  /**
   * ASSESSED, REVISED (reviewed by the reflection critic), QUANT_ONLY (no model answer) or NO_SCORE (an analysis
   * without model answer and without market data)
   */
  status: "ASSESSED" | "REVISED" | "QUANT_ONLY" | "NO_SCORE"
}

export interface NewsBriefView {
  sentiment: "POSITIVE" | "NEUTRAL" | "NEGATIVE" | "MIXED"
  summary: string
  catalysts: string[]
  risks: string[]
  sources: string[]
}

export interface CandidateNews {
  brief: NewsBriefView | null
  headlines: { title: string; url: string; source: string; publishedAt: string | null }[]
  sources: { source: string; found: number; cached: boolean; error: string | null }[]
  trace: { iteration: number; thought: string | null; tools: string[] }[]
  modelUsed: boolean
  fromCache: boolean
  note: string | null
}

export interface ScreeningCandidate {
  id: number
  ticker: string
  companyName: string
  sector: string | null
  industry: string | null
  quantOverall: number | null
  quantRank: number | null
  overallScore: number | null
  synthesisAdjustment: number | null
  finalRank: number | null
  /** in the final top N */
  selected: boolean
  conviction: "HIGH" | "MEDIUM" | "LOW" | null
  thesis: string | null
  metrics: Record<string, unknown> | null
  news: CandidateNews | null
  redFlags: string[] | null
  agents: AgentScore[]
}

export interface ScreeningUsage {
  stage: string
  model: string
  calls: number
  promptTokens: number
  completionTokens: number
  reasoningTokens: number
  cachedTokens: number
  costUsd: number
  costEstimated: boolean
  errors: number
}

/** GET /api/v1/screenings/{id} */
export interface ScreeningReport {
  run: ScreeningRun
  funnel: { key: string; label: string; remaining: number }[] | null
  synthesis: {
    executiveSummary: string | null
    portfolioNotes: string[] | null
    model: string | null
    fallback: string | null
  } | null
  notes: {
    messages?: string[]
    lessonsLearned?: { agent: string; lesson: string; occurrencesInRun: number }[]
    lessonsApplied?: Record<string, string[]>
    budget?: { budgetUsd: number; spentUsd: number }
  } | null
  candidates: ScreeningCandidate[]
  usage: ScreeningUsage[]
}

// AI analysis of one stock (docs/v1_docs/ANALYSIS_DOCS.md)

export type Verdict = "STRONG_FIT" | "FIT" | "NEUTRAL" | "WEAK" | "REJECT"

export type Conviction = "HIGH" | "MEDIUM" | "LOW"

/** A company that can be analysed, with what the database holds for it. */
export interface AnalysisCompanyOption {
  exchange: string
  ticker: string
  companyName: string
  sector: string | null
  /** stored reporting periods */
  periods: number
  latestPeriod: string | null
  /** financial statement PDFs in the vector store */
  pdfDocuments: number
  newsDocuments: number
  /** latest Yahoo Finance snapshot (null: not in the screening data) */
  marketDataDate: string | null
  latestPriceDate: string | null
  /** monthly registration reports stored (Companies > Registration), their first and latest month ("2026-05") */
  registrationReports: number
  firstRegistrationPeriod: string | null
  latestRegistrationPeriod: string | null
  /** KSEI daily lists of the shareholders of more than 5% naming the company (Companies > Ownership (>=5%)) */
  ownershipDays?: number
  latestOwnershipDate?: string | null
  /** KSEI monthly lists of the shareholders of more than 1% naming the company (Companies > Ownership (>=1%)) */
  onePercentMonths?: number
  latestOnePercentDate?: string | null
}

/** GET /api/v1/analyses/options */
export interface AnalysisOptions {
  companies: AnalysisCompanyOption[]
  agents: ScreeningOption[]
  budgetUsd: number
  researchModel: string
  agentModel: string
  synthesisModel: string
}

/** One analysis as listed (and the head of the report). */
export interface AnalysisRun {
  id: string
  status: IngestionJobStatus
  stage: string | null
  message: string | null
  exchange: string
  ticker: string
  companyName: string | null
  agents: InvestorAgentCode[]
  /** after the synthesis adjustment; null until finished */
  overallScore: number | null
  verdict: Verdict | null
  conviction: Conviction | null
  marketDataDate: string | null
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

/** GET /api/v1/analyses: one page, most recent first */
export interface AnalysisPage {
  total: number
  limit: number
  offset: number
  analyses: AnalysisRun[]
}

export interface ResearchBriefView {
  business: string | null
  moat: string | null
  management: string | null
  growth: string | null
  /** controlling shareholder, holders of 5% or more, free float (from the registration reports); null when none read */
  ownership?: string | null
  risks: string[]
  catalysts: string[]
  newsSentiment: "POSITIVE" | "NEUTRAL" | "NEGATIVE" | "MIXED" | "NONE"
  newsSummary: string | null
  /** facts with the ref of the excerpt they come from (F1 = filing, R1 = registration report, N1 = news) */
  evidence: { ref: string; fact: string }[]
}

/** An excerpt the research agent retrieved from the vector store. */
export interface RetrievedExcerpt {
  ref: string
  /**
   * PDF (a financial statement), REGISTRATION (a monthly registration report), OWNERSHIP (KSEI's 5% lists),
   * OWNERSHIP_1_PERCENT (KSEI's monthly 1% lists) or NEWS
   */
  source: RagSourceType | "REGISTRATION" | "OWNERSHIP" | "OWNERSHIP_1_PERCENT"
  title: string | null
  /** pages of a PDF, date of a news article */
  where: string | null
  url: string | null
  distance: number
  query: string
}

export interface AnalysisResearch {
  brief: ResearchBriefView | null
  trace: { iteration: number; thought: string | null; tools: string[] }[]
  retrieved: RetrievedExcerpt[]
  modelUsed: boolean
  /** evidence dropped because it cited an excerpt that was never retrieved */
  droppedRefs: number
  note: string | null
}

/** One period of the fact sheet: amounts scaled as amountUnit says, ratios as fractions. */
export interface FactSheetPeriod {
  period: string
  periodEnd: string
  audited?: boolean
  income?: Record<string, number>
  balance?: Record<string, number>
  cashFlow?: Record<string, number>
  metrics?: Record<string, number>
}

/** What the agents saw. */
export interface AnalysisContext {
  factSheet: {
    company: { ticker: string; name: string; sector?: string; industry?: string; reportingCurrency: string }
    amountUnit: string
    periods: FactSheetPeriod[]
    latestValuation?: Record<string, number | string>
    latestPrice?: { date: string; close?: number; historyFrom?: string }
  } | null
  marketData: { source: string; date: string | null; metrics: Record<string, unknown> } | null
  documents: {
    pdfDocuments: number
    newsArticles: number
    pdfTitles: string[]
    latestNews: string[]
    registrationReports?: number
    registrationTitles?: string[]
    /** the title of the company's ownership document (KSEI lists); null when none is stored */
    ownershipDocument?: string | null
    /** the title of the company's 1% ownership document (KSEI monthly lists); null when none is stored */
    onePercentDocument?: string | null
  } | null
  /** the directors' and commissioners' shares from the monthly registration reports; null when none is stored */
  insiderOwnership?: InsiderOwnershipView | null
  /** the holders of more than 5% from KSEI's daily lists; null when no list names the company */
  majorShareholders?: OwnershipDigest | null
  /** the holders of more than 1% from KSEI's monthly lists; null when no list names the company */
  shareholdersAbove1Percent?: OnePercentDigest | null
  periods: string[]
  quantitativeScorecards: boolean
}

/** The insider section of an analysis (InsiderOwnership on the backend): ownership is a fraction of the shares listed. */
export interface InsiderOwnershipView {
  months: string[]
  latestMonth: string
  missingMonths?: string[]
  listedShares: number | null
  insiders: {
    name: string
    role: "DIRECTOR" | "COMMISSIONER" | "OTHER"
    position: string
    shares: number | null
    ownership: number | null
    changeLatestMonth: number | null
    netChange: number | null
    controller?: boolean
    controllerAffiliate?: boolean
  }[]
  notInLatestReport?: string[]
  totals: {
    insiders: number
    shares: number | null
    ownership: number | null
    sharesBought: number
    sharesSold: number
    netChange: number
    buys: number
    sells: number
  }
  transactions: { month: string; name: string; role: string; change: number; sharesAfter: number | null }[]
  transactionsNote?: string
  reportWarnings?: string
  reading: string
}

export interface AnalysisSynthesisView {
  executiveSummary: string | null
  conviction: Conviction | null
  thesis: string | null
  bullCase: string[]
  bearCase: string[]
  keyRisks: string[]
  monitor: string[]
  dataGaps: string[]
  adjustmentReason: string | null
  model: string | null
  fallback: string | null
}

/** GET /api/v1/analyses/{id} */
export interface AnalysisReport {
  run: AnalysisRun
  /** the overall of the quantitative scorecards alone (null without market data) */
  quantOverall: number | null
  synthesisAdjustment: number | null
  context: AnalysisContext | null
  research: AnalysisResearch | null
  synthesis: AnalysisSynthesisView | null
  notes: ScreeningReport["notes"]
  agents: AgentScore[]
  usage: ScreeningUsage[]
}

/** GET /api/v1/fundamentals/status */
export interface FundamentalsStatus {
  exchange: string
  listings: number
  latestSnapshotDate: string | null
  withFundamentals: number
  oldestFundamentalsAt: string | null
  lastSyncAt: string | null
  activeJobId: string | null
}

/** GET /api/v1/listing-prices/status?exchange= (daily bars of the screening universe, the technical screening) */
export interface ListingPricesStatus {
  exchange: string
  /** active listings of the screening universe */
  listings: number
  /** of them with stored daily bars */
  withBars: number
  latestDate: string | null
  rows: number
  /** how far back the first load of a listing reaches */
  historyYears: number
  activeJobId: string | null
}
