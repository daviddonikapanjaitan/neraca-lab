import type { Metadata } from "next"

import { AccessDenied } from "@/components/access-denied"
import { ApiErrorState } from "@/components/api-error-state"
import { MacroPageClient } from "@/components/macro/macro-page-client"
import { ApiError, getMacroOutlook, requireUser } from "@/lib/api"
import { isMacroRange } from "@/lib/macro"
import { hasPermission, homePath } from "@/lib/permissions"

export const metadata: Metadata = {
  title: "Macro",
}

function first(value: string | string[] | undefined): string | undefined {
  return (Array.isArray(value) ? value[0] : value)?.trim() || undefined
}

/**
 * Macro: a country's stock index read through its macro data - the index as a line, the macro panel (a chart and a
 * summary per series, the liquidity and risk regimes), the Paul Tudor Jones and Druckenmiller summaries and the
 * conclusion (investability score, when to buy and when to sell). The choice is in the URL
 * (?country=ID&index=IHSG&range=1Y). Permission COMPANIES, as the macro panel of Companies > Technicals.
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
  const country = first(params.country)?.toUpperCase() ?? "ID"
  const index = first(params.index)?.toUpperCase()
  const rangeParam = first(params.range)?.toUpperCase()
  const range = isMacroRange(rangeParam) ? rangeParam : "1Y"

  let outlook
  try {
    outlook = await getMacroOutlook(country, index, range)
  } catch (error) {
    if (error instanceof ApiError) {
      // an unknown country or index in the URL: show the default page instead of an error
      if (error.status === 400 && (first(params.country) || first(params.index))) {
        try {
          outlook = await getMacroOutlook("ID", undefined, range)
        } catch (retry) {
          if (!(retry instanceof ApiError)) throw retry
          return errorState(retry)
        }
      } else {
        return errorState(error)
      }
    } else {
      throw error
    }
  }

  return (
    <div className="flex flex-1 flex-col gap-4 p-4 pt-0">
      <MacroPageClient initial={outlook} />
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
