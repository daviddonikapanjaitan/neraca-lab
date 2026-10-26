import type { Metadata } from "next"

import { AccessDenied } from "@/components/access-denied"
import { ApiErrorState } from "@/components/api-error-state"
import { TechnicalRunsTable } from "@/components/technical-screening/runs-table"
import { TechnicalTierForm } from "@/components/technical-screening/tier-form"
import { ApiError, getTechnicalScreeningOptions, getTechnicalScreenings, requireUser } from "@/lib/api"
import { hasPermission, homePath } from "@/lib/permissions"
import {
  TECHNICAL_RUN_LIMIT,
  type TechnicalScreeningOptions,
  type TechnicalScreeningRun,
} from "@/lib/technical-screening"

export const metadata: Metadata = {
  title: "Technicals (All)",
}

async function load(): Promise<
  | { data: { options: TechnicalScreeningOptions; runs: TechnicalScreeningRun[] }; error?: never }
  | { data?: never; error: ApiError }
> {
  try {
    const [options, runs] = await Promise.all([
      getTechnicalScreeningOptions(),
      getTechnicalScreenings(TECHNICAL_RUN_LIMIT, "TIER"),
    ])
    return { data: { options, runs } }
  } catch (error) {
    if (error instanceof ApiError) return { error }
    throw error
  }
}

/** Screening > Technicals (All): the AI technical screening of a market-cap tier of the exchange (SCREENING). */
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
        <h1 className="text-xl font-semibold tracking-tight">AI technical screening</h1>
        <p className="text-sm text-muted-foreground">
          Screen a market-cap tier of the stock exchange through its charts only - price movements, moving averages and
          indicators - in the style of Keith Gill, John Murphy, Paul Tudor Jones and Stanley Druckenmiller, with where to
          buy, where to sell and where to stop the loss. Each run is a background job; its report is saved and can be
          opened again.
        </p>
      </div>
      <TechnicalTierForm options={data.options} />
      <TechnicalRunsTable initial={data.runs} scope="TIER" />
    </div>
  )
}
