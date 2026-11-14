import type { Metadata } from "next"
import { notFound } from "next/navigation"

import { AccessDenied } from "@/components/access-denied"
import { ApiErrorState } from "@/components/api-error-state"
import { AnalysisReportView } from "@/components/portfolio/analysis-report"
import { ApiError, getPortfolioAnalysis, requireUser } from "@/lib/api"
import { isJobId } from "@/lib/ingestion"
import { hasPermission, homePath } from "@/lib/permissions"
import { analysisRunTitle, PORTFOLIO_ANALYSIS_HREF, type AnalysisReport } from "@/lib/portfolio-analysis"

type Params = Promise<{ id: string }>

async function load(params: Params): Promise<AnalysisReport | ApiError | null> {
  const { id } = await params
  if (!isJobId(id)) return null
  try {
    return await getPortfolioAnalysis(id)
  } catch (error) {
    if (error instanceof ApiError) return error
    throw error
  }
}

export async function generateMetadata({ params }: { params: Params }): Promise<Metadata> {
  const report = await load(params)
  return { title: report && !(report instanceof ApiError) ? `Portfolio analysis · ${analysisRunTitle(report.run)}` : "Portfolio analysis" }
}

/** A Portfolio > Analysis report (the signed-in user's own runs only). */
export default async function Page({ params }: { params: Params }) {
  const user = await requireUser()
  if (!hasPermission(user, "COMPANIES")) {
    return <AccessDenied permission="COMPANIES" homeHref={homePath(user)} />
  }
  const report = await load(params)
  if (report === null || (report instanceof ApiError && report.status === 404)) notFound()
  if (report instanceof ApiError) {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-4 p-4 pt-0">
        <ApiErrorState error={report} backHref={PORTFOLIO_ANALYSIS_HREF} backLabel="Back to portfolio analyses" />
      </div>
    )
  }
  return (
    <div className="flex min-w-0 flex-1 flex-col gap-4 p-4 pt-0">
      <AnalysisReportView initial={report} />
    </div>
  )
}
