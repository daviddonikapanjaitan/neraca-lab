import type { Metadata } from "next"

import { IngestionPage } from "@/components/ingestion/ingestion-page"
import type { IngestionSectionData } from "@/components/ingestion/ingestion-page-client"
import { getFundamentalsStatus } from "@/lib/api"
import { ingestionSection } from "@/lib/ingestion-sections"

export const metadata: Metadata = {
  title: ingestionSection("screening-data").heading,
}

/** Screening data stored for IDX and the active ETL run. */
async function section(): Promise<IngestionSectionData> {
  return { section: "screening-data", fundamentals: await getFundamentalsStatus("IDX") }
}

export default function Page() {
  return <IngestionPage name="screening-data" section={section} />
}
