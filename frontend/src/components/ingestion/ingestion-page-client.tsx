"use client"

import { useCallback, useState } from "react"
import { useRouter } from "next/navigation"

import { JobsTable } from "@/components/ingestion/jobs-table"
import { PriceCard } from "@/components/ingestion/price-card"
import { UploadCard } from "@/components/ingestion/upload-card"
import type { CompanySummary, Exchange, IngestionJob, IngestionJobList } from "@/lib/types"

export function IngestionPageClient({
  exchanges,
  companiesByExchange,
  provider,
  jobs,
}: {
  exchanges: Exchange[]
  companiesByExchange: Record<string, CompanySummary[]>
  provider: string
  jobs: IngestionJobList
}) {
  const router = useRouter()
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
        <h1 className="text-xl font-semibold tracking-tight">Ingestion</h1>
        <p className="text-sm text-muted-foreground">
          Load financial statements and daily prices into Neraca Lab. Every ingestion runs in the background;
          follow its progress in the table below.
        </p>
      </div>

      <div className="grid items-start gap-4 lg:grid-cols-2">
        <UploadCard onSubmitted={submitted} />
        <PriceCard
          exchanges={exchanges}
          companiesByExchange={companiesByExchange}
          provider={provider}
          onSubmitted={submitted}
        />
      </div>

      <JobsTable initial={jobs} watch={watch} onJobFinished={jobFinished} />
    </div>
  )
}
