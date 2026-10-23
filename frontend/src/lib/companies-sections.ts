// The Companies pages, shared by the sidebar dropdown and the breadcrumb.

export interface CompaniesSectionInfo {
  /** sidebar entry and breadcrumb */
  title: string
  href: string
}

export const TECHNICALS_HREF = "/companies/technicals"
export const REGISTRATION_HREF = "/companies/registration"

/**
 * Fundamentals: the company list and the company pages (/companies/IDX/HRTA); Technicals: the chart page;
 * Registration: the directors' and commissioners' shares month by month (monthly registration reports).
 */
export const COMPANIES_SECTIONS: CompaniesSectionInfo[] = [
  { title: "Fundamentals", href: "/companies" },
  { title: "Technicals", href: TECHNICALS_HREF },
  { title: "Registration", href: REGISTRATION_HREF },
]
