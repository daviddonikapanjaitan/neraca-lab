// The Screening pages, shared by the sidebar dropdown and the breadcrumb.

export interface ScreeningSectionInfo {
  /** sidebar entry and breadcrumb */
  title: string
  href: string
}

export const SCREENING_SECTIONS: ScreeningSectionInfo[] = [
  { title: "Screening Stocks", href: "/screening" },
  { title: "Analysis", href: "/screening/analysis" },
]

export const ANALYSIS_HREF = "/screening/analysis"
