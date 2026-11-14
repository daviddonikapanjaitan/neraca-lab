import type { Metadata } from "next"

import { AccessDenied } from "@/components/access-denied"
import { ApiErrorState } from "@/components/api-error-state"
import { AnalysisForm } from "@/components/portfolio/analysis-form"
import { AnalysisRunsTable } from "@/components/portfolio/analysis-runs-table"
import { ApiError, getPortfolioAnalyses, getPortfolioAnalysisOptions, requireUser } from "@/lib/api"
import { hasPermission, homePath } from "@/lib/permissions"
import { ANALYSIS_RUN_LIMIT, type AnalysisOptions, type AnalysisRun } from "@/lib/portfolio-analysis"

export const metadata: Metadata = {
  title: "Portfolio Analysis",
}

async function load(): Promise<{ options: AnalysisOptions; runs: AnalysisRun[] } | ApiError> {
  try {
    const [options, runs] = await Promise.all([getPortfolioAnalysisOptions(), getPortfolioAnalyses(ANALYSIS_RUN_LIMIT)])
    return { options, runs }
  } catch (error) {
    if (error instanceof ApiError) return error
    throw error
  }
}

/**
 * Portfolio > Analysis (instruction/INSTRUCTION.md): AI recommendations for the signed-in user's portfolio - sell,
 * decrease, hold, increase or buy, and the cash to keep - from the best Fundamental (Score) first and the best Technical
 * (Score) after, the fundamental and technical agents (DeepSeek) and the synthesis (Claude Opus 5.5), within at most
 * 33% per stock and per sector. Reading needs COMPANIES; starting a run SCREENING too (the AI models).
 */
export default async function Page() {
  const user = await requireUser()
  if (!hasPermission(user, "COMPANIES")) {
    return <AccessDenied permission="COMPANIES" homeHref={homePath(user)} />
  }
  const data = await load()
  if (data instanceof ApiError) {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-4 p-4 pt-0">
        <ApiErrorState error={data} backHref={homePath(user)} backLabel="Back" />
      </div>
    )
  }
  return (
    <div className="flex min-w-0 flex-1 flex-col gap-4 p-4 pt-0">
      <div className="flex flex-col gap-1">
        <h1 className="text-xl font-semibold tracking-tight">AI portfolio analysis</h1>
        <p className="text-sm text-muted-foreground">
          Recommendations for your portfolio: which stocks to sell or decrease, which to keep or increase, which new
          stocks to buy with your capital, and how much cash to keep - at most{" "}
          {Math.round(data.options.maxPositionWeight * 100)}% of the capital in one stock or one sector.
        </p>
      </div>
      <AnalysisForm options={data.options} canStart={hasPermission(user, "SCREENING")} />
      <AnalysisRunsTable initial={data.runs} />
    </div>
  )
}
