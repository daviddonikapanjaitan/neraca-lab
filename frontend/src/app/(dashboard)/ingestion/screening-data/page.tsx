import type { Metadata } from "next"

import { IngestionPage } from "@/components/ingestion/ingestion-page"
import type { IngestionSectionData } from "@/components/ingestion/ingestion-page-client"
import { getFundamentalsStatus, getListingPricesStatus } from "@/lib/api"
import { ingestionSection } from "@/lib/ingestion-sections"

export const metadata: Metadata = {
  title: ingestionSection("screening-data").heading,
}

/** Screening data stored for IDX (market data and fundamentals, the price history) and the active runs. */
async function section(): Promise<IngestionSectionData> {
  const [fundamentals, listingPrices] = await Promise.all([getFundamentalsStatus("IDX"), getListingPricesStatus("IDX")])
  return { section: "screening-data", fundamentals, listingPrices }
}

export default function Page() {
  return <IngestionPage name="screening-data" section={section} />
}
