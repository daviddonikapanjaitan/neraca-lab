"use client"

import { useCallback, useState } from "react"
import { useRouter } from "next/navigation"

import { FundamentalsCard } from "@/components/ingestion/fundamentals-card"
import { JobsTable } from "@/components/ingestion/jobs-table"
import { PriceCard } from "@/components/ingestion/price-card"
import { RagDocumentsCard } from "@/components/ingestion/rag-documents-card"
import { RagNewsCard } from "@/components/ingestion/rag-news-card"
import { RagPdfCard } from "@/components/ingestion/rag-pdf-card"
import { UploadCard } from "@/components/ingestion/upload-card"
import { ingestionSection } from "@/lib/ingestion-sections"
import type {
  CompanySummary,
  Exchange,
  FundamentalsStatus,
  IngestionJob,
  IngestionJobList,
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
      provider: string
    }
  | { section: "screening-data"; fundamentals: FundamentalsStatus }
  | {
      section: "rag-pdf"
      exchanges: Exchange[]
      companiesByExchange: Record<string, CompanySummary[]>
      rag: RagStatus
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
 * One Ingestion page (IDX XBRL, Price Ingestion, Screening Data IDX, PDF Documents (RAG) or News (RAG)): its card,
 * the stored documents on the RAG pages, then the jobs table, which opens on the page's own job type (tabs for
 * the other types and all jobs, a status filter, page by page).
 */
export function IngestionPageClient({ jobs, ...data }: IngestionSectionData & { jobs: IngestionJobList }) {
  const router = useRouter()
  const info = ingestionSection(data.section)
  /** the job submitted last; the table reloads at once and watches it */
  const [watch, setWatch] = useState<{ id: string; seq: number } | null>(null)

  const submitted = useCallback((job: IngestionJob) => {
    setWatch((previous) => ({ id: job.id, seq: (previous?.seq ?? 0) + 1 }))
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

      <div className="w-full max-w-3xl">
        {data.section === "xbrl" && <UploadCard onSubmitted={submitted} />}
        {data.section === "prices" && (
          <PriceCard
            exchanges={data.exchanges}
            companiesByExchange={data.companiesByExchange}
            provider={data.provider}
            onSubmitted={submitted}
          />
        )}
        {data.section === "screening-data" && <FundamentalsCard status={data.fundamentals} onSubmitted={submitted} />}
        {data.section === "rag-pdf" && (
          <RagPdfCard
            exchanges={data.exchanges}
            companiesByExchange={data.companiesByExchange}
            onSubmitted={submitted}
          />
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

      {(data.section === "rag-pdf" || data.section === "rag-news") && (
        <RagDocumentsCard
          source={data.section === "rag-pdf" ? "PDF" : "NEWS"}
          initial={data.documents}
          status={data.rag}
        />
      )}

      <JobsTable initial={jobs} defaultType={info.jobType} watch={watch} onJobFinished={jobFinished} />
    </div>
  )
}
