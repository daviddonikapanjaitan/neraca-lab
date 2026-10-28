import type { Metadata } from "next"

import { IngestionPage } from "@/components/ingestion/ingestion-page"
import type { IngestionSectionData } from "@/components/ingestion/ingestion-page-client"
import { getExcelReports, getExcelStatus, getRagDocuments, getRagStatus } from "@/lib/api"
import { DEFAULT_TABLE_PAGE_SIZE } from "@/lib/ingestion"
import { ingestionSection } from "@/lib/ingestion-sections"

export const metadata: Metadata = {
  title: ingestionSection("rag-excel").heading,
}

/**
 * The exchanges and their Excel document types (IDX: Ownership (>=5%)), the first page of the stored ownership lists,
 * the vector store settings and the first page of the companies' ownership documents.
 */
async function section(): Promise<IngestionSectionData> {
  const [excel, reports, rag, documents] = await Promise.all([
    getExcelStatus(),
    getExcelReports(DEFAULT_TABLE_PAGE_SIZE),
    getRagStatus(),
    getRagDocuments("XLSX", DEFAULT_TABLE_PAGE_SIZE),
  ])
  return { section: "rag-excel", excel, reports, rag, documents }
}

export default function Page() {
  return <IngestionPage name="rag-excel" section={section} />
}
