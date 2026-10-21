import type { Metadata } from "next"
import { notFound } from "next/navigation"

import { AccessDenied } from "@/components/access-denied"
import { ApiErrorState } from "@/components/api-error-state"
import { MacroAnalysisReport } from "@/components/macro/macro-analysis-report"
import { ApiError, getMacroAnalysis, requireUser } from "@/lib/api"
import { isJobId } from "@/lib/ingestion"
import { MACRO_ANALYSIS_HREF } from "@/lib/macro"
import { macroAnalysisTitle, type MacroAnalysisReport as Report } from "@/lib/macro-analysis"
import { hasPermission, homePath } from "@/lib/permissions"

type Params = Promise<{ id: string }>

async function load(params: Params): Promise<
  { report: Report; error?: never } | { report?: never; error: ApiError | null }
> {
  const { id } = await params
  if (!isJobId(id)) return { error: null }
  try {
    return { report: await getMacroAnalysis(id) }
  } catch (error) {
    if (error instanceof ApiError) return { error }
    throw error
  }
}

export async function generateMetadata({ params }: { params: Params }): Promise<Metadata> {
  const { report } = await load(params)
  return { title: report ? `Macro analysis · ${macroAnalysisTitle(report.run)}` : "Macro analysis" }
}

export default async function Page({ params }: { params: Params }) {
  const user = await requireUser()
  if (!hasPermission(user, "COMPANIES")) {
    return <AccessDenied permission="COMPANIES" homeHref={homePath(user)} />
  }

  const { report, error } = await load(params)

  if (!report && (error === null || error.status === 404)) notFound()

  if (!report) {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-4 p-4 pt-0">
        <ApiErrorState error={error!} backHref={MACRO_ANALYSIS_HREF} backLabel="Back to macro analyses" />
      </div>
    )
  }

  return (
    <div className="flex flex-1 flex-col gap-4 p-4 pt-0">
      <MacroAnalysisReport initial={report} />
    </div>
  )
}
