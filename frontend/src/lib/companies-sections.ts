// The Companies pages, shared by the sidebar dropdown and the breadcrumb.

export interface CompaniesSectionInfo {
  /** sidebar entry and breadcrumb */
  title: string
  href: string
}

export const TECHNICALS_HREF = "/companies/technicals"
export const REGISTRATION_HREF = "/companies/registration"
export const OWNERSHIP_HREF = "/companies/ownership"
export const OWNERSHIP_ONE_PERCENT_HREF = "/companies/ownership-one-percent"

/**
 * Fundamentals: the company list and the company pages (/companies/IDX/HRTA); Technicals: the chart page;
 * Registration: the directors' and commissioners' shares month by month (monthly registration reports);
 * Ownership (>=5%): the shareholders of more than 5% day by day (KSEI's daily lists); Ownership (>=1%): the
 * shareholders of more than 1% month by month (KSEI's monthly lists).
 */
export const COMPANIES_SECTIONS: CompaniesSectionInfo[] = [
  { title: "Fundamentals", href: "/companies" },
  { title: "Technicals", href: TECHNICALS_HREF },
  { title: "Registration", href: REGISTRATION_HREF },
  { title: "Ownership (>=5%)", href: OWNERSHIP_HREF },
  { title: "Ownership (>=1%)", href: OWNERSHIP_ONE_PERCENT_HREF },
]
