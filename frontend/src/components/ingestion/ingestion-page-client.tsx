"use client"

import { useCallback, useState } from "react"
import { useRouter } from "next/navigation"

import { FundamentalsCard } from "@/components/ingestion/fundamentals-card"
import { JobsTable } from "@/components/ingestion/jobs-table"
import { PriceCard } from "@/components/ingestion/price-card"
import { UploadCard } from "@/components/ingestion/upload-card"
import { ingestionSection } from "@/lib/ingestion-sections"
import type { CompanySummary, Exchange, FundamentalsStatus, IngestionJob, IngestionJobList } from "@/lib/types"

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

/**
 * One Ingestion page (IDX XBRL, Price Ingestion or Screening Data IDX): its card, then the jobs table, which every
 * page shows with all ingestion jobs (filterable by type and status).
 */
export function IngestionPageClient({ jobs, ...data }: IngestionSectionData & { jobs: IngestionJobList }) {
  const router = useRouter()
  const info = ingestionSection(data.section)
  /** the job submitted last; the table reloads at once and watches it */
  const [watch, setWatch] = useState<{ id: string; seq: number } | null>(null)

  const submitted = useCallback((job: IngestionJob) => {
    setWatch((previous) => ({ id: job.id, seq: (previous?.seq ?? 0) + 1 }))
  }, [])

  // a finished job can add a company or prices: re-render the server data (ticker list, latest price date)
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
      </div>

      <JobsTable initial={jobs} watch={watch} onJobFinished={jobFinished} />
    </div>
  )
}
