import type { Metadata } from "next"

import { AccessDenied } from "@/components/access-denied"
import { ApiErrorState } from "@/components/api-error-state"
import { RunsTable } from "@/components/screening/runs-table"
import { ScreeningForm } from "@/components/screening/screening-form"
import { ApiError, getScreeningOptions, getScreenings, requireUser } from "@/lib/api"
import { hasPermission, homePath } from "@/lib/permissions"
import { RUN_LIMIT } from "@/lib/screening"
import type { ScreeningOptions, ScreeningRun } from "@/lib/types"

export const metadata: Metadata = {
  title: "Screening",
}

async function load(): Promise<
  { data: { options: ScreeningOptions; runs: ScreeningRun[] }; error?: never } | { data?: never; error: ApiError }
> {
  try {
    const [options, runs] = await Promise.all([getScreeningOptions(), getScreenings(RUN_LIMIT)])
    return { data: { options, runs } }
  } catch (error) {
    if (error instanceof ApiError) return { error }
    throw error
  }
}

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
        <h1 className="text-xl font-semibold tracking-tight">AI stock screening</h1>
        <p className="text-sm text-muted-foreground">
          Screen the stock exchange through the eyes of great investors. Each run is a background job; its report is
          saved and can be opened again or downloaded as PDF.
        </p>
      </div>
      <ScreeningForm options={data.options} />
      <RunsTable initial={data.runs} />
    </div>
  )
}
