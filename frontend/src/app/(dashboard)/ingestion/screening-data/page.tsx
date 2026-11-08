import type { Metadata } from "next"

import { IngestionPage } from "@/components/ingestion/ingestion-page"
import type { IngestionSectionData } from "@/components/ingestion/ingestion-page-client"
import { getCompanyProfilesStatus, getFundamentalsStatus, getListingPricesStatus } from "@/lib/api"
import { ingestionSection } from "@/lib/ingestion-sections"

export const metadata: Metadata = {
  title: ingestionSection("screening-data").heading,
}

/**
 * Screening data stored for IDX (market data and fundamentals, the price history, the IDX company profiles) and the
 * active runs.
 */
async function section(): Promise<IngestionSectionData> {
  const [fundamentals, listingPrices, profiles] = await Promise.all([
    getFundamentalsStatus("IDX"),
    getListingPricesStatus("IDX"),
    getCompanyProfilesStatus("IDX"),
  ])
  return { section: "screening-data", fundamentals, listingPrices, profiles }
}

export default function Page() {
  return <IngestionPage name="screening-data" section={section} />
}
