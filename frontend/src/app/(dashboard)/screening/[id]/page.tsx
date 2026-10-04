import type { Metadata } from "next"
import { notFound } from "next/navigation"

import { AccessDenied } from "@/components/access-denied"
import { ApiErrorState } from "@/components/api-error-state"
import { ReportView } from "@/components/screening/report-view"
import { ApiError, getScreening, requireUser } from "@/lib/api"
import { isJobId } from "@/lib/ingestion"
import { hasPermission, homePath } from "@/lib/permissions"
import { runTitle } from "@/lib/screening"
import type { ScreeningReport } from "@/lib/types"

type Params = Promise<{ id: string }>

async function load(params: Params): Promise<
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

export async function generateMetadata({ params }: { params: Params }): Promise<Metadata> {
  const { report } = await load(params)
  return { title: report ? `Screening · ${runTitle(report.run)}` : "Screening" }
}

export default async function Page({ params }: { params: Params }) {
  const user = await requireUser()
  if (!hasPermission(user, "SCREENING")) {
    return <AccessDenied permission="SCREENING" homeHref={homePath(user)} />
  }

  const { report, error } = await load(params)

  if (!report && (error === null || error.status === 404)) notFound()

  if (!report) {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-4 p-4 pt-0">
        <ApiErrorState error={error!} backHref="/screening" backLabel="Back to screenings" />
      </div>
    )
  }

  return (
    <div className="flex flex-1 flex-col gap-4 p-4 pt-0">
      <ReportView initial={report} />
    </div>
  )
}
