import type { Metadata } from "next"

import { AccessDenied } from "@/components/access-denied"
import { ApiErrorState } from "@/components/api-error-state"
import { RunsTable } from "@/components/screening/runs-table"
import { SelectionForm } from "@/components/screening/selection-form"
import { ApiError, getScreeningOptions, getScreenings, getSelectableCompanies, requireUser } from "@/lib/api"
import { hasPermission, homePath } from "@/lib/permissions"
import { RUN_LIMIT } from "@/lib/screening"
import type { ScreeningOptions, ScreeningRun, SelectableCompanies } from "@/lib/types"

export const metadata: Metadata = {
  title: "Selected Stocks",
}

async function load(): Promise<
  | { data: { options: ScreeningOptions; companies: SelectableCompanies; runs: ScreeningRun[] }; error?: never }
  | { data?: never; error: ApiError }
> {
  try {
    const options = await getScreeningOptions()
    const exchange = options.exchanges[0]?.code ?? "IDX"
    const [companies, runs] = await Promise.all([getSelectableCompanies(exchange), getScreenings(RUN_LIMIT, "SELECTION")])
    return { data: { options, companies, runs } }
  } catch (error) {
    if (error instanceof ApiError) return { error }
    throw error
  }
}

/** Screening > Selected Stocks: the screening of stocks chosen from the companies table (SCREENING). */
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
        <h1 className="text-xl font-semibold tracking-tight">AI screening of selected stocks</h1>
        <p className="text-sm text-muted-foreground">
          Choose stocks from the companies table and screen them through the eyes of great investors, with the same
          steps as Screening Stocks. Each run is a background job; its report is saved and can be opened again or
          downloaded as PDF.
        </p>
      </div>
      <SelectionForm options={data.options} companies={data.companies} />
      <RunsTable initial={data.runs} scope="SELECTION" />
    </div>
  )
}
