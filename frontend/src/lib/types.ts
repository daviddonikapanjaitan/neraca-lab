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

export interface Price {
  tradingDate: string
  openPrice: Amount
  highPrice: Amount
  lowPrice: Amount
  closePrice: Amount
  adjustedClose: Amount
  volume: number | null
}

export interface MarketSnapshot {
  snapshotDate: string
  sharePrice: Amount
  sharesOutstanding: Amount
  marketCap: Amount
  enterpriseValue: Amount
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
  evSales: Amount
  evOp: Amount
  fcfYield: Amount
  earningsYield: Amount
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

export type IngestionJobType = "FINANCIAL_STATEMENT" | "PRICE"

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
  limit: number
  /** most recent first, without results */
  jobs: IngestionJob[]
}

/** GET /api/v1/prices/ingestions (its in-memory job list is not used: the page reads ingestion_job) */
export interface PriceQueue {
  /** yahoo | eodhd */
  provider: string
  /** jobs waiting in the price queue */
  pending: number
}

// Users, roles and permissions (docs/v1_docs/AUTH_DOCS.md)

export type Permission = "ADMIN" | "INGESTION" | "COMPANIES"

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
