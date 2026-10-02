/** Tabs of the company detail page; the value is kept in the URL as ?tab= (overview = no param). */
export const DETAIL_TABS = [
  { value: "overview", label: "Overview" },
  { value: "income", label: "Income statement" },
  { value: "balance", label: "Balance sheet" },
  { value: "cashflow", label: "Cash flow" },
  { value: "segments", label: "Segments" },
  { value: "metrics", label: "Metrics" },
  { value: "valuation", label: "Valuation" },
  { value: "market", label: "Market & shares" },
  { value: "filings", label: "Filings" },
] as const

export type DetailTab = (typeof DETAIL_TABS)[number]["value"]

export function isDetailTab(value: unknown): value is DetailTab {
  return DETAIL_TABS.some((t) => t.value === value)
}
