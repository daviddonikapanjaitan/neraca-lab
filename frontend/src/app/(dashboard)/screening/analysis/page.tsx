import type { Metadata } from "next"

import { AccessDenied } from "@/components/access-denied"
import { AnalysesTable } from "@/components/analysis/analyses-table"
import { AnalysisForm } from "@/components/analysis/analysis-form"
import { ApiErrorState } from "@/components/api-error-state"
import { ApiError, getAnalyses, getAnalysisOptions, requireUser } from "@/lib/api"
import { DEFAULT_TABLE_PAGE_SIZE } from "@/lib/ingestion"
import { hasPermission, homePath } from "@/lib/permissions"
import type { AnalysisOptions, AnalysisPage } from "@/lib/types"

export const metadata: Metadata = {
  title: "Analysis",
}

async function load(): Promise<
  { data: { options: AnalysisOptions; analyses: AnalysisPage }; error?: never } | { data?: never; error: ApiError }
> {
  try {
    const [options, analyses] = await Promise.all([getAnalysisOptions(), getAnalyses(DEFAULT_TABLE_PAGE_SIZE, 0)])
    return { data: { options, analyses } }
  } catch (error) {
    if (error instanceof ApiError) return { error }
    throw error
  }
}

/** Screening > Analysis: an in-depth AI analysis of one stock from the companies table (SCREENING). */
export default async function Page() {
  const user = await requireUser()
  if (!hasPermission(user, "SCREENING")) {
    return <AccessDenied permission="SCREENING" homeHref={homePath(user)} />
  }

  const { data, error } = await load()

  if (error) {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-4 p-4 pt-0">
        <ApiErrorState error={error} backHref={homePath(user)} backLabel="Back" />
      </div>
    )
  }

  return (
    <div className="flex flex-1 flex-col gap-4 p-4 pt-0">
      <div className="flex flex-col gap-1">
        <h1 className="text-xl font-semibold tracking-tight">AI stock analysis</h1>
        <p className="text-sm text-muted-foreground">
          Analyse one stock in depth through the eyes of great investors, using what the database holds for it: its
          financial statements, prices and valuations, and its PDF documents and news. Each analysis is a background
          job; its report and cost are saved and can be opened again or downloaded as PDF.
        </p>
      </div>
      <AnalysisForm options={data.options} />
      <AnalysesTable initial={data.analyses} />
    </div>
  )
}
