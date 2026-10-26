import type { Metadata } from "next"

import { AccessDenied } from "@/components/access-denied"
import { ApiErrorState } from "@/components/api-error-state"
import { TechnicalRunsTable } from "@/components/technical-screening/runs-table"
import { TechnicalSelectionForm } from "@/components/technical-screening/selection-form"
import {
  ApiError,
  getTechnicalScreeningOptions,
  getTechnicalScreenings,
  getTechnicalSelectableCompanies,
  requireUser,
} from "@/lib/api"
import { hasPermission, homePath } from "@/lib/permissions"
import {
  TECHNICAL_RUN_LIMIT,
  type TechnicalScreeningOptions,
  type TechnicalScreeningRun,
  type TechnicalSelectableCompanies,
} from "@/lib/technical-screening"

export const metadata: Metadata = {
  title: "Technicals (Selected)",
}

async function load(): Promise<
  | {
      data: { options: TechnicalScreeningOptions; companies: TechnicalSelectableCompanies; runs: TechnicalScreeningRun[] }
      error?: never
    }
  | { data?: never; error: ApiError }
> {
  try {
    const options = await getTechnicalScreeningOptions()
    const exchange = options.exchanges[0]?.code ?? "IDX"
    const [companies, runs] = await Promise.all([
      getTechnicalSelectableCompanies(exchange),
      getTechnicalScreenings(TECHNICAL_RUN_LIMIT, "SELECTION"),
    ])
    return { data: { options, companies, runs } }
  } catch (error) {
    if (error instanceof ApiError) return { error }
    throw error
  }
}

/**
 * Screening > Technicals (Selected): the AI technical screening of stocks chosen from the companies table, read from
 * their stored daily prices (SCREENING).
 */
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
        <h1 className="text-xl font-semibold tracking-tight">AI technical screening of selected stocks</h1>
        <p className="text-sm text-muted-foreground">
          Choose stocks from the companies table and screen their charts only - price movements, moving averages and
          indicators of the daily prices stored in the database - with the same steps as Technicals (All), and get where
          to buy, where to sell and where to stop the loss. Each run is a background job; its report is saved and can be
          opened again.
        </p>
      </div>
      <TechnicalSelectionForm options={data.options} companies={data.companies} />
      <TechnicalRunsTable initial={data.runs} scope="SELECTION" />
    </div>
  )
}
