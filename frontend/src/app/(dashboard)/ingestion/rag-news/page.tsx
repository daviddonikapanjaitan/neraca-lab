import type { Metadata } from "next"

import { IngestionPage } from "@/components/ingestion/ingestion-page"
import type { IngestionSectionData } from "@/components/ingestion/ingestion-page-client"
import { getCompanies, getExchanges, getRagDocuments, getRagStatus } from "@/lib/api"
import { todayJakarta } from "@/lib/date-range"
import { RAG_DOCUMENT_LIMIT } from "@/lib/ingestion"
import { ingestionSection } from "@/lib/ingestion-sections"

export const metadata: Metadata = {
  title: ingestionSection("rag-news").heading,
}

/** The IDX companies stored (news is collected for IDX listings), the vector store settings and the stored news. */
async function section(): Promise<IngestionSectionData> {
  const [exchanges, companies, rag, documents] = await Promise.all([
    getExchanges(),
    getCompanies("IDX"),
    getRagStatus(),
    getRagDocuments("NEWS", RAG_DOCUMENT_LIMIT),
  ])
  return {
    section: "rag-news",
    exchanges: exchanges.filter((e) => e.code === "IDX"),
    companiesByExchange: { IDX: companies.companies },
    rag,
    documents,
    today: todayJakarta(),
  }
}

export default function Page() {
  return <IngestionPage section={section} />
}
