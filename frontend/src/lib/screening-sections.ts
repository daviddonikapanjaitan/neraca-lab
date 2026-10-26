// The Screening pages, shared by the sidebar dropdown and the breadcrumb.

export interface ScreeningSectionInfo {
  /** sidebar entry and breadcrumb */
  title: string
  href: string
}

export const SELECTED_STOCKS_HREF = "/screening/selected"

/** Technicals (All): the AI technical screening of a market-cap tier */
export const TECHNICALS_ALL_HREF = "/screening/technicals"

/** Technicals (Selected): the AI technical screening of stocks chosen from the companies table */
export const TECHNICALS_SELECTED_HREF = "/screening/technicals/selected"

export const ANALYSIS_HREF = "/screening/analysis"

export const SCREENING_SECTIONS: ScreeningSectionInfo[] = [
  { title: "Fundamental (All)", href: "/screening" },
  { title: "Fundamental (Selected)", href: SELECTED_STOCKS_HREF },
  { title: "Technicals (All)", href: TECHNICALS_ALL_HREF },
  { title: "Technicals (Selected)", href: TECHNICALS_SELECTED_HREF },
  { title: "Analysis", href: ANALYSIS_HREF },
]
