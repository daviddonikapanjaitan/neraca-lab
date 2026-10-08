// AI analysis of one stock: helpers shared by the analysis components.

import { EMPTY, formatNumber, formatPercent } from "@/lib/format"
import { SENTIMENT_CLASS } from "@/lib/screening"
import type { AnalysisRun, FactSheetPeriod, ResearchBriefView } from "@/lib/types"

/** "HRTA · PT Hartadinata Abadi Tbk" */
export function analysisTitle(run: Pick<AnalysisRun, "ticker" | "companyName">): string {
  return run.companyName ? `${run.ticker} · ${run.companyName}` : run.ticker
}

export const RESEARCH_SENTIMENT_CLASS: Record<ResearchBriefView["newsSentiment"], string> = {
  ...SENTIMENT_CLASS,
  NONE: "border-border bg-muted text-muted-foreground",
}

/** A row of the key figures table: where the value is in a fact-sheet period and how to show it. */
export interface FigureRow {
  label: string
  section: "income" | "balance" | "cashFlow" | "metrics"
  key: string
  kind: "amount" | "percent" | "ratio" | "perShare"
}

export const FIGURE_ROWS: FigureRow[] = [
  { label: "Revenue", section: "income", key: "revenue", kind: "amount" },
  { label: "Operating income", section: "income", key: "operatingIncome", kind: "amount" },
  { label: "Net income to parent", section: "income", key: "netIncomeToParent", kind: "amount" },
  { label: "EPS", section: "income", key: "basicEps", kind: "perShare" },
  { label: "Total assets", section: "balance", key: "totalAssets", kind: "amount" },
  { label: "Total equity", section: "balance", key: "totalEquity", kind: "amount" },
  { label: "Operating cash flow", section: "cashFlow", key: "operatingCashFlow", kind: "amount" },
  { label: "Gross margin", section: "metrics", key: "gross_margin", kind: "percent" },
  { label: "Operating margin", section: "metrics", key: "operating_margin", kind: "percent" },
  { label: "Net margin", section: "metrics", key: "net_margin", kind: "percent" },
  { label: "ROE (annualized)", section: "metrics", key: "roe_annualized", kind: "percent" },
  { label: "ROA (annualized)", section: "metrics", key: "roa_annualized", kind: "percent" },
  { label: "Debt / equity", section: "metrics", key: "debt_to_equity", kind: "ratio" },
  { label: "Equity / assets", section: "metrics", key: "equity_to_assets", kind: "percent" },
  { label: "Current ratio", section: "metrics", key: "current_ratio", kind: "ratio" },
  { label: "Interest coverage", section: "metrics", key: "interest_coverage", kind: "ratio" },
]

/** The value of a figure in a period, formatted (EMPTY when not stored). */
export function figure(period: FactSheetPeriod, row: FigureRow): string {
  const value = period[row.section]?.[row.key]
  if (typeof value !== "number") return EMPTY
  switch (row.kind) {
    case "percent":
      return formatPercent(value, 1)
    case "ratio":
      return formatNumber(value, 2)
    case "perShare":
      return formatNumber(value, 2)
    default:
      return formatNumber(value, Math.abs(value) >= 100 ? 1 : 2)
  }
}

/** The rows with a value in at least one period (a bank has no gross margin or current ratio). */
export function figureRows(periods: FactSheetPeriod[]): FigureRow[] {
  return FIGURE_ROWS.filter((row) => periods.some((p) => typeof p[row.section]?.[row.key] === "number"))
}
