import type { Metadata } from "next"
import { notFound, redirect } from "next/navigation"

import { AccessDenied } from "@/components/access-denied"
import { ApiErrorState } from "@/components/api-error-state"
import { ReportView } from "@/components/screening/report-view"
import { ApiError, getScreening, requireUser } from "@/lib/api"
import { isJobId } from "@/lib/ingestion"
import { hasPermission, homePath } from "@/lib/permissions"
import { reportHref, runTitle } from "@/lib/screening"
import type { ScreeningReport, ScreeningScope } from "@/lib/types"

// The report page of a screening, shared by /screening/<id> (a market-cap tier) and /screening/selected/<id>
// (selected stocks). A run opened under the other page's URL is redirected to its own.

export type ReportParams = Promise<{ id: string }>

async function load(params: ReportParams): Promise<
  { report: ScreeningReport; error?: never } | { report?: never; error: ApiError | null }
> {
  const { id } = await params
  if (!isJobId(id)) return { error: null }
  try {
    return { report: await getScreening(id) }
  } catch (error) {
    if (error instanceof ApiError) return { error }
    throw error
  }
}

export async function reportMetadata(params: ReportParams): Promise<Metadata> {
  const { report } = await load(params)
  return { title: report ? `Screening · ${runTitle(report.run)}` : "Screening" }
}

export async function ScreeningReportPage({ params, scope }: { params: ReportParams; scope: ScreeningScope }) {
  const user = await requireUser()
  if (!hasPermission(user, "SCREENING")) {
    return <AccessDenied permission="SCREENING" homeHref={homePath(user)} />
  }

  const { report, error } = await load(params)

  if (!report && (error === null || error.status === 404)) notFound()

  if (!report) {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-4 p-4 pt-0">
        <ApiErrorState
          error={error!}
          backHref={scope === "SELECTION" ? "/screening/selected" : "/screening"}
          backLabel="Back to screenings"
        />
      </div>
    )
  }

  if ((report.run.tickers !== null) !== (scope === "SELECTION")) redirect(reportHref(report.run))

  return (
    <div className="flex flex-1 flex-col gap-4 p-4 pt-0">
      <ReportView initial={report} />
    </div>
  )
}
