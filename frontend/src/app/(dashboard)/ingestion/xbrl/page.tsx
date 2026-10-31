import type { Metadata } from "next"

import { IngestionPage } from "@/components/ingestion/ingestion-page"
import type { IngestionSectionData } from "@/components/ingestion/ingestion-page-client"
import { getRagDocuments, getRagStatus } from "@/lib/api"
import { DEFAULT_TABLE_PAGE_SIZE } from "@/lib/ingestion"
import { ingestionSection } from "@/lib/ingestion-sections"

export const metadata: Metadata = {
  title: ingestionSection("xbrl").heading,
}

/**
 * The vector store settings and the first page of the uploaded workbooks in it (each upload job stores the text of its
 * workbook as a document of the company).
 */
async function section(): Promise<IngestionSectionData> {
  const [rag, documents] = await Promise.all([getRagStatus(), getRagDocuments("XBRL", DEFAULT_TABLE_PAGE_SIZE)])
  return { section: "xbrl", rag, documents }
}

export default function Page() {
  return <IngestionPage name="xbrl" section={section} />
}
