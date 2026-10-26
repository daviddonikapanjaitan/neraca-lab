import type { Metadata } from "next"
import { notFound, redirect } from "next/navigation"

import { AccessDenied } from "@/components/access-denied"
import { ApiErrorState } from "@/components/api-error-state"
import { TechnicalReportView } from "@/components/technical-screening/report-view"
import { ApiError, getTechnicalScreening, requireUser } from "@/lib/api"
import { isJobId } from "@/lib/ingestion"
import { hasPermission, homePath } from "@/lib/permissions"
import { TECHNICALS_ALL_HREF, TECHNICALS_SELECTED_HREF } from "@/lib/screening-sections"
import {
  technicalReportHref,
  technicalRunTitle,
  technicalScope,
  type TechnicalScreeningReport,
} from "@/lib/technical-screening"
import type { ScreeningScope } from "@/lib/types"

// The report page of a technical screening, shared by /screening/technicals/<id> (a market-cap tier) and
// /screening/technicals/selected/<id> (selected stocks). A run opened under the other page's URL is redirected to its own.

export type TechnicalReportParams = Promise<{ id: string }>

async function load(params: TechnicalReportParams): Promise<
  { report: TechnicalScreeningReport; error?: never } | { report?: never; error: ApiError | null }
> {
  const { id } = await params
  if (!isJobId(id)) return { error: null }
  try {
    return { report: await getTechnicalScreening(id) }
  } catch (error) {
    if (error instanceof ApiError) return { error }
    throw error
  }
}

export async function technicalReportMetadata(params: TechnicalReportParams): Promise<Metadata> {
  const { report } = await load(params)
  return { title: report ? `Technical screening · ${technicalRunTitle(report.run)}` : "Technical screening" }
}

export async function TechnicalReportPage({ params, scope }: { params: TechnicalReportParams; scope: ScreeningScope }) {
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
          backHref={scope === "SELECTION" ? TECHNICALS_SELECTED_HREF : TECHNICALS_ALL_HREF}
          backLabel="Back to technical screenings"
        />
      </div>
    )
  }

  if (technicalScope(report.run) !== scope) redirect(technicalReportHref(report.run))

  return (
    <div className="flex flex-1 flex-col gap-4 p-4 pt-0">
      <TechnicalReportView initial={report} />
    </div>
  )
}
