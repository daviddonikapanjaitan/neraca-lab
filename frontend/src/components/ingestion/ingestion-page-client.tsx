"use client"

import { useCallback, useState } from "react"
import { useRouter } from "next/navigation"

import { FundamentalsCard } from "@/components/ingestion/fundamentals-card"
import { IndexPriceCard } from "@/components/ingestion/index-price-card"
import { JobsTable } from "@/components/ingestion/jobs-table"
import { ListingPricesCard } from "@/components/ingestion/listing-prices-card"
import { MacroCard } from "@/components/ingestion/macro-card"
import { OwnershipListsCard } from "@/components/ingestion/ownership-lists-card"
import { PriceCard } from "@/components/ingestion/price-card"
import { PriceUpdateCard } from "@/components/ingestion/price-update-card"
import { RagDocumentsCard } from "@/components/ingestion/rag-documents-card"
import { RagExcelCard } from "@/components/ingestion/rag-excel-card"
import { RagMacroDocumentsCard } from "@/components/ingestion/rag-macro-documents-card"
import { RagMacroPdfCard } from "@/components/ingestion/rag-macro-pdf-card"
import { RagNewsCard } from "@/components/ingestion/rag-news-card"
import { RagPdfCard } from "@/components/ingestion/rag-pdf-card"
import { UploadCard } from "@/components/ingestion/upload-card"
import { ingestionSection } from "@/lib/ingestion-sections"
import type { ExcelStatus, OwnershipReportPage } from "@/lib/ownership"
import type {
  CompanySummary,
  Exchange,
  FundamentalsStatus,
  IngestionJob,
  IngestionJobList,
  IngestionJobType,
  ListingPricesStatus,
  MacroRagDocumentPage,
  MacroStatus,
  MarketStatus,
  PriceStatus,
  RagDocumentPage,
  RagStatus,
} from "@/lib/types"

/** The data of one Ingestion page: each page shows its own card. */
export type IngestionSectionData =
  | { section: "xbrl" }
  | {
      section: "prices"
      exchanges: Exchange[]
      companiesByExchange: Record<string, CompanySummary[]>
      /** how far the prices and the index of each exchange reach */
      statusByExchange: Record<string, PriceStatus>
      /** Forex and Commodities with their instruments */
      markets: MarketStatus[]
      provider: string
    }
  | { section: "macro"; macro: MacroStatus }
  | { section: "screening-data"; fundamentals: FundamentalsStatus; listingPrices: ListingPricesStatus }
  | {
      section: "rag-pdf"
      exchanges: Exchange[]
      companiesByExchange: Record<string, CompanySummary[]>
      rag: RagStatus
      documents: RagDocumentPage
      /** first page of the macro documents of {@code macroCountry} */
      macroDocuments: MacroRagDocumentPage
      macroCountry: string
      /** today in Jakarta (yyyy-mm-dd): the latest publication date of a macro document */
      today: string
    }
  | {
      section: "rag-excel"
      /** the exchanges and their Excel document types, the lists stored */
      excel: ExcelStatus
      /** first page of the stored ownership lists */
      reports: OwnershipReportPage
      rag: RagStatus
      /** first page of the companies' ownership documents in the vector store */
      documents: RagDocumentPage
    }
  | {
      section: "rag-news"
      exchanges: Exchange[]
      companiesByExchange: Record<string, CompanySummary[]>
      rag: RagStatus
      documents: RagDocumentPage
      /** today in Jakarta (yyyy-mm-dd) */
      today: string
    }

/**
 * One Ingestion page (IDX XBRL, Price Ingestion, Screening Data IDX, PDF Documents (RAG), Excel Documents (RAG), News
 * (RAG) or Macro): its card,
 * the stored documents on the RAG pages, then the jobs table, which opens on the page's own job type (tabs for
 * the other types and all jobs, a status filter, page by page).
 */
export function IngestionPageClient({ jobs, ...data }: IngestionSectionData & { jobs: IngestionJobList }) {
  const router = useRouter()
  const info = ingestionSection(data.section)
  /** the job submitted last; the table reloads at once and watches it */
  const [watch, setWatch] = useState<{ id: string; seq: number; type: IngestionJobType } | null>(null)

  const submitted = useCallback((job: IngestionJob) => {
    setWatch((previous) => ({ id: job.id, seq: (previous?.seq ?? 0) + 1, type: job.type }))
  }, [])

  // a finished job can add a company, prices or RAG documents: re-render the server data (ticker list, latest
  // price date, stored documents)
  const jobFinished = useCallback(() => router.refresh(), [router])

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-1">
        <h1 className="text-xl font-semibold tracking-tight">{info.heading}</h1>
        <p className="text-sm text-muted-foreground">
          {info.description} Every ingestion runs in the background; follow its progress in the jobs table below.
        </p>
      </div>

      {data.section === "prices" && (
        // one company / instrument on the left; the whole exchange or market and the index (IHSG) on the right
        <div className="grid w-full items-start gap-4 lg:grid-cols-2">
          <PriceCard
            exchanges={data.exchanges}
            companiesByExchange={data.companiesByExchange}
            markets={data.markets}
            provider={data.provider}
            onSubmitted={submitted}
          />
          <div className="flex min-w-0 flex-col gap-4">
            <PriceUpdateCard
              exchanges={data.exchanges}
              statusByExchange={data.statusByExchange}
              markets={data.markets}
              onSubmitted={submitted}
            />
            <IndexPriceCard exchanges={data.exchanges} statusByExchange={data.statusByExchange} onSubmitted={submitted} />
          </div>
        </div>
      )}

      {data.section === "macro" && <MacroCard status={data.macro} onSubmitted={submitted} />}

      {data.section === "rag-pdf" && (
        // a company's document on the left; a country's macro document on the right
        <div className="grid w-full items-start gap-4 lg:grid-cols-2">
          <RagPdfCard
            exchanges={data.exchanges}
            companiesByExchange={data.companiesByExchange}
            onSubmitted={submitted}
          />
          <RagMacroPdfCard
            countries={data.rag.macroCountries}
            documentTypes={data.rag.macroDocumentTypes}
            today={data.today}
            onSubmitted={submitted}
          />
        </div>
      )}

      {data.section === "rag-excel" && (
        <div className="w-full max-w-3xl">
          <RagExcelCard status={data.excel} onSubmitted={submitted} />
        </div>
      )}

      {data.section === "rag-excel" && <OwnershipListsCard initial={data.reports} status={data.excel} />}

      {data.section !== "prices" && data.section !== "macro" && data.section !== "rag-pdf" &&
        data.section !== "rag-excel" && (
        <div className="w-full max-w-3xl">
          {data.section === "xbrl" && <UploadCard onSubmitted={submitted} />}
          {data.section === "screening-data" && (
            <div className="flex flex-col gap-4">
              <FundamentalsCard status={data.fundamentals} onSubmitted={submitted} />
              <ListingPricesCard status={data.listingPrices} onSubmitted={submitted} />
            </div>
          )}
          {data.section === "rag-news" && (
            <RagNewsCard
              exchanges={data.exchanges}
              companiesByExchange={data.companiesByExchange}
              maxArticles={data.rag.newsMaxArticles}
              today={data.today}
              onSubmitted={submitted}
            />
          )}
        </div>
      )}

      {(data.section === "rag-pdf" || data.section === "rag-excel" || data.section === "rag-news") && (
        <RagDocumentsCard
          source={data.section === "rag-pdf" ? "PDF" : data.section === "rag-excel" ? "XLSX" : "NEWS"}
          initial={data.documents}
          status={data.rag}
        />
      )}

      {data.section === "rag-pdf" && (
        <RagMacroDocumentsCard initial={data.macroDocuments} initialCountry={data.macroCountry} status={data.rag} />
      )}

      <JobsTable initial={jobs} defaultType={info.jobType} watch={watch} onJobFinished={jobFinished} />
    </div>
  )
}
