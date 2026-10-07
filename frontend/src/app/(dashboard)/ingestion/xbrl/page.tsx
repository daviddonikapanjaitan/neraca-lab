import type { Metadata } from "next"

import { IngestionPage } from "@/components/ingestion/ingestion-page"
import type { IngestionSectionData } from "@/components/ingestion/ingestion-page-client"
import { ingestionSection } from "@/lib/ingestion-sections"

export const metadata: Metadata = {
  title: ingestionSection("xbrl").heading,
}

async function section(): Promise<IngestionSectionData> {
  return { section: "xbrl" }
}

export default function Page() {
  return <IngestionPage section={section} />
}
