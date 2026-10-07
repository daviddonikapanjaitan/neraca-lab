// The Ingestion pages, shared by the sidebar dropdown, the breadcrumb and the pages themselves.

export type IngestionSection = "xbrl" | "prices" | "screening-data"

export interface IngestionSectionInfo {
  section: IngestionSection
  /** sidebar entry and breadcrumb */
  title: string
  /** page heading */
  heading: string
  description: string
  href: string
}

export const INGESTION_SECTIONS: IngestionSectionInfo[] = [
  {
    section: "xbrl",
    title: "IDX XBRL",
    heading: "IDX XBRL Ingestion",
    description:
      "Upload IDX XBRL financial statements (.xlsx); the AI agent stores and verifies every statement.",
    href: "/ingestion/xbrl",
  },
  {
    section: "prices",
    title: "Price Ingestion",
    heading: "Price Ingestion",
    description: "Fetch daily prices of a listed company; market and valuation data are recalculated afterwards.",
    href: "/ingestion/prices",
  },
  {
    section: "screening-data",
    title: "Screening Data IDX",
    heading: "Screening Data IDX",
    description: "Refresh the market data of every IDX listing that the AI screening starts from.",
    href: "/ingestion/screening-data",
  },
]

/** /ingestion opens the first page. */
export const INGESTION_HOME = INGESTION_SECTIONS[0].href

export function ingestionSection(section: IngestionSection): IngestionSectionInfo {
  return INGESTION_SECTIONS.find((s) => s.section === section)!
}
