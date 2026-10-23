import type { Metadata } from "next"

import { IngestionPage } from "@/components/ingestion/ingestion-page"
import type { IngestionSectionData } from "@/components/ingestion/ingestion-page-client"
import { getCompanies, getExchanges, getRagDocuments, getRagMacroDocuments, getRagStatus } from "@/lib/api"
import { todayJakarta } from "@/lib/date-range"
import { DEFAULT_TABLE_PAGE_SIZE } from "@/lib/ingestion"
import { ingestionSection } from "@/lib/ingestion-sections"
import type { CompanySummary } from "@/lib/types"

export const metadata: Metadata = {
  title: ingestionSection("rag-pdf").heading,
}

/** The macro documents listed first: Indonesia's (the only country taking uploads for now). */
const MACRO_COUNTRY = "ID"

/**
 * Stored companies (the company of a PDF), the vector store settings (with the countries and types of the macro
 * documents) and the first page of the stored PDF documents of the companies and of the macro documents.
 */
async function section(): Promise<IngestionSectionData> {
  const [exchanges, rag, documents, macroDocuments] = await Promise.all([
    getExchanges(),
    getRagStatus(),
    getRagDocuments("PDF", DEFAULT_TABLE_PAGE_SIZE),
    getRagMacroDocuments(MACRO_COUNTRY, DEFAULT_TABLE_PAGE_SIZE),
  ])
  const lists = await Promise.all(exchanges.map((e) => getCompanies(e.code)))
  const companiesByExchange: Record<string, CompanySummary[]> = {}
  exchanges.forEach((e, i) => {
    companiesByExchange[e.code] = lists[i].companies
  })
  return {
    section: "rag-pdf",
    exchanges,
    companiesByExchange,
    rag,
    documents,
    macroDocuments,
    macroCountry: MACRO_COUNTRY,
    today: todayJakarta(),
  }
}

export default function Page() {
  return <IngestionPage name="rag-pdf" section={section} />
}
