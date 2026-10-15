// The Companies pages, shared by the sidebar dropdown and the breadcrumb.

export interface CompaniesSectionInfo {
  /** sidebar entry and breadcrumb */
  title: string
  href: string
}

export const TECHNICALS_HREF = "/companies/technicals"

/** Fundamentals: the company list and the company pages (/companies/IDX/HRTA); Technicals: the chart page. */
export const COMPANIES_SECTIONS: CompaniesSectionInfo[] = [
  { title: "Fundamentals", href: "/companies" },
  { title: "Technicals", href: TECHNICALS_HREF },
]
