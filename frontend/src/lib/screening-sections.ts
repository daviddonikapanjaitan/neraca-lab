// The Screening pages, shared by the sidebar dropdown and the breadcrumb.

export interface ScreeningSectionInfo {
  /** sidebar entry and breadcrumb */
  title: string
  href: string
}

export const SELECTED_STOCKS_HREF = "/screening/selected"

export const ANALYSIS_HREF = "/screening/analysis"

export const SCREENING_SECTIONS: ScreeningSectionInfo[] = [
  { title: "Screening Stocks", href: "/screening" },
  { title: "Selected Stocks", href: SELECTED_STOCKS_HREF },
  { title: "Analysis", href: ANALYSIS_HREF },
]
