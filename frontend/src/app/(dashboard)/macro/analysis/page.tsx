import type { Metadata } from "next"

import { AccessDenied } from "@/components/access-denied"
import { ApiErrorState } from "@/components/api-error-state"
import { MacroAnalysesTable } from "@/components/macro/macro-analyses-table"
import { MacroAnalysisForm } from "@/components/macro/macro-analysis-form"
import { ApiError, getMacroAnalyses, getMacroAnalysisOptions, requireUser } from "@/lib/api"
import { DEFAULT_TABLE_PAGE_SIZE } from "@/lib/ingestion"
import type { MacroAnalysisOptions, MacroAnalysisPage } from "@/lib/macro-analysis"
import { hasPermission, homePath } from "@/lib/permissions"

export const metadata: Metadata = {
  title: "AI Macro Analysis",
}

async function load(): Promise<
  | { data: { options: MacroAnalysisOptions; analyses: MacroAnalysisPage }; error?: never }
  | { data?: never; error: ApiError }
> {
  try {
    const [options, analyses] = await Promise.all([getMacroAnalysisOptions(), getMacroAnalyses(DEFAULT_TABLE_PAGE_SIZE, 0)])
    return { data: { options, analyses } }
  } catch (error) {
    if (error instanceof ApiError) return { error }
    throw error
  }
}

/** Macro > AI Analysis: an AI macro analysis of a country's stock market (COMPANIES, as the Macro page). */
export default async function Page() {
  const user = await requireUser()
  if (!hasPermission(user, "COMPANIES")) {
    return <AccessDenied permission="COMPANIES" homeHref={homePath(user)} />
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
        <h1 className="text-xl font-semibold tracking-tight">AI macro analysis</h1>
        <p className="text-sm text-muted-foreground">
          Is the country investable now? Four macro agents - Paul Tudor Jones, Stanley Druckenmiller, George Soros and
          Ray Dalio - judge its stock market from the stored macro data, its indexes, commodities and currencies, and the
          Macro page&apos;s rules, and score it from 0 to 100. Each analysis is a background job; its report and cost are
          saved and can be opened again or downloaded as PDF.
        </p>
      </div>
      <MacroAnalysisForm options={data.options} />
      <MacroAnalysesTable initial={data.analyses} />
    </div>
  )
}
