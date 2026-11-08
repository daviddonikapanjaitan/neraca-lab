import type { Metadata } from "next"

import { AccessDenied } from "@/components/access-denied"
import { ApiErrorState } from "@/components/api-error-state"
import { EmptyState } from "@/components/empty-state"
import { MacroDataClient } from "@/components/macro/macro-data-client"
import { ApiError, getMacroDataChart, getMacroDataSeries, requireUser } from "@/lib/api"
import { isMacroRange } from "@/lib/macro"
import { hasPermission, homePath } from "@/lib/permissions"

export const metadata: Metadata = {
  title: "Macro Data",
}

function first(value: string | string[] | undefined): string | undefined {
  return (Array.isArray(value) ? value[0] : value)?.trim() || undefined
}

/**
 * Macro > Data: every series of Ingestion > Macro as a large chart, chosen from a dropdown, with its statistics,
 * changes and latest observations. The choice is in the URL (?series=US_10Y&range=5Y). Permission COMPANIES, as the
 * other Macro pages.
 */
export default async function Page({
  searchParams,
}: {
  searchParams: Promise<{ [key: string]: string | string[] | undefined }>
}) {
  const user = await requireUser()
  if (!hasPermission(user, "COMPANIES")) {
    return <AccessDenied permission="COMPANIES" homeHref={homePath(user)} />
  }

  const params = await searchParams
  const rangeParam = first(params.range)?.toUpperCase()
  const range = isMacroRange(rangeParam) ? rangeParam : "1Y"

  let list
  try {
    list = await getMacroDataSeries()
  } catch (error) {
    if (error instanceof ApiError) return errorState(error)
    throw error
  }
  if (list.series.length === 0) {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-4 p-4 pt-0">
        <EmptyState variant="chart" title="No macro series" description="The macro catalog is empty." />
      </div>
    )
  }

  // an unknown series in the URL: the first one with data instead of an error
  const wanted = first(params.series)?.toUpperCase()
  const series =
    list.series.find((s) => s.code === wanted) ?? list.series.find((s) => s.rows > 0) ?? list.series[0]

  let chart
  try {
    chart = await getMacroDataChart(series.code, range)
  } catch (error) {
    if (error instanceof ApiError) return errorState(error)
    throw error
  }

  return (
    <div className="flex flex-1 flex-col gap-4 p-4 pt-0">
      <MacroDataClient list={list} initial={chart} />
    </div>
  )
}

function errorState(error: ApiError) {
  return (
    <div className="flex flex-1 flex-col items-center justify-center gap-4 p-4 pt-0">
      <ApiErrorState error={error} />
    </div>
  )
}
