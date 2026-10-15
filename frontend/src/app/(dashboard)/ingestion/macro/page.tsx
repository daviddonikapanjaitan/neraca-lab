import type { Metadata } from "next"

import { IngestionPage } from "@/components/ingestion/ingestion-page"
import type { IngestionSectionData } from "@/components/ingestion/ingestion-page-client"
import { getMacroStatus } from "@/lib/api"
import { ingestionSection } from "@/lib/ingestion-sections"

export const metadata: Metadata = {
  title: ingestionSection("macro").heading,
}

/** Every macro series with what is stored of it and its state. */
async function section(): Promise<IngestionSectionData> {
  return { section: "macro", macro: await getMacroStatus() }
}

export default function Page() {
  return <IngestionPage name="macro" section={section} />
}
