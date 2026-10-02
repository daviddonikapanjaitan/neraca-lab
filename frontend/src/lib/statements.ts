// Line items of the three statements, in presentation order. Keys are the API field names;
// `total` rows are emphasised, `indent` rows are components of the row above.
import type { BalanceSheet, CashFlowStatement, IncomeStatement } from "@/lib/types"

export interface LineItem<T> {
  key: keyof T
  label: string
  total?: boolean
  indent?: boolean
  /** not an amount in the company currency (EPS, share counts): never scaled */
  unscaled?: boolean
}

export const INCOME_STATEMENT: LineItem<IncomeStatement>[] = [
  { key: "revenue", label: "Revenue", total: true },
  { key: "costOfRevenue", label: "Cost of revenue" },
  { key: "grossProfit", label: "Gross profit", total: true },
  { key: "operatingExpenses", label: "Operating expenses" },
  { key: "sgaExpense", label: "Selling, general & administrative", indent: true },
  { key: "rdExpense", label: "Research & development", indent: true },
  { key: "operatingIncome", label: "Operating income", total: true },
  { key: "depreciation", label: "Depreciation" },
  { key: "amortization", label: "Amortization" },
  { key: "ebit", label: "EBIT" },
  { key: "ebitda", label: "EBITDA" },
  { key: "interestIncome", label: "Interest income" },
  { key: "interestExpense", label: "Interest expense" },
  { key: "pretaxIncome", label: "Profit before tax", total: true },
  { key: "incomeTax", label: "Income tax" },
  { key: "netIncome", label: "Net income", total: true },
  { key: "netIncomeToParent", label: "Attributable to owners of the parent", indent: true },
  { key: "basicEps", label: "Basic EPS", unscaled: true },
  { key: "dilutedEps", label: "Diluted EPS", unscaled: true },
  { key: "basicShares", label: "Weighted shares (basic)", unscaled: true },
  { key: "dilutedShares", label: "Weighted shares (diluted)", unscaled: true },
]

export const BALANCE_SHEET: LineItem<BalanceSheet>[] = [
  { key: "cashAndEquivalents", label: "Cash and equivalents" },
  { key: "marketableSecurities", label: "Marketable securities" },
  { key: "accountsReceivable", label: "Accounts receivable" },
  { key: "inventory", label: "Inventory" },
  { key: "currentAssets", label: "Current assets", total: true },
  { key: "goodwill", label: "Goodwill" },
  { key: "intangibleAssets", label: "Intangible assets" },
  { key: "totalAssets", label: "Total assets", total: true },
  { key: "accountsPayable", label: "Accounts payable" },
  { key: "deferredRevenue", label: "Deferred revenue" },
  { key: "shortTermDebt", label: "Short-term debt" },
  { key: "currentLiabilities", label: "Current liabilities", total: true },
  { key: "longTermDebt", label: "Long-term debt" },
  { key: "leaseLiabilities", label: "Lease liabilities" },
  { key: "totalLiabilities", label: "Total liabilities", total: true },
  { key: "retainedEarnings", label: "Retained earnings" },
  { key: "shareholdersEquity", label: "Equity attributable to the parent" },
  { key: "nonControllingInterest", label: "Non-controlling interest" },
  { key: "totalEquity", label: "Total equity", total: true },
  { key: "sharesOutstanding", label: "Shares outstanding", unscaled: true },
]

export const CASH_FLOW_STATEMENT: LineItem<CashFlowStatement>[] = [
  { key: "operatingCashFlow", label: "Operating cash flow", total: true },
  { key: "capitalExpenditure", label: "Capital expenditure" },
  { key: "acquisitions", label: "Acquisitions" },
  { key: "investingCashFlow", label: "Investing cash flow", total: true },
  { key: "debtIssued", label: "Debt issued" },
  { key: "debtRepaid", label: "Debt repaid" },
  { key: "leasePayments", label: "Lease payments" },
  { key: "stockIssuance", label: "Stock issuance" },
  { key: "shareBuybacks", label: "Share buybacks" },
  { key: "dividendsPaid", label: "Dividends paid" },
  { key: "financingCashFlow", label: "Financing cash flow", total: true },
  { key: "cashChange", label: "Net change in cash" },
  { key: "endingCash", label: "Ending cash", total: true },
]

/** Period types in the order they are offered as filters. */
export const PERIOD_TYPES = ["FY", "Q1", "H1", "9M", "Q2", "Q3", "Q4", "TTM"] as const

export const PERIOD_TYPE_LABEL: Record<string, string> = {
  FY: "Full year",
  Q1: "Q1 (3 months)",
  H1: "H1 (6 months YTD)",
  "9M": "9M (9 months YTD)",
  Q2: "Q2 (3 months)",
  Q3: "Q3 (3 months)",
  Q4: "Q4 (3 months)",
  TTM: "Trailing 12 months",
}
