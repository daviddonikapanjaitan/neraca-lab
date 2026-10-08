import type { Metadata } from "next"

import { IngestionPage } from "@/components/ingestion/ingestion-page"
import type { IngestionSectionData } from "@/components/ingestion/ingestion-page-client"
import { getCompanies, getExchanges, getRagDocuments, getRagStatus } from "@/lib/api"
import { DEFAULT_TABLE_PAGE_SIZE } from "@/lib/ingestion"
import { ingestionSection } from "@/lib/ingestion-sections"
import type { CompanySummary } from "@/lib/types"

export const metadata: Metadata = {
  title: ingestionSection("rag-pdf").heading,
}

/** Stored companies (the company of a PDF), the vector store settings and the first page of the stored PDF documents. */
async function section(): Promise<IngestionSectionData> {
  const [exchanges, rag, documents] = await Promise.all([
    getExchanges(),
    getRagStatus(),
    getRagDocuments("PDF", DEFAULT_TABLE_PAGE_SIZE),
  ])
  const lists = await Promise.all(exchanges.map((e) => getCompanies(e.code)))
  const companiesByExchange: Record<string, CompanySummary[]> = {}
  exchanges.forEach((e, i) => {
    companiesByExchange[e.code] = lists[i].companies
  })
  return { section: "rag-pdf", exchanges, companiesByExchange, rag, documents }
}

export default function Page() {
  return <IngestionPage name="rag-pdf" section={section} />
}
