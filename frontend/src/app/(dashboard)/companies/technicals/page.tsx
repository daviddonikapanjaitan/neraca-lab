import type { Metadata } from "next"

import { AccessDenied } from "@/components/access-denied"
import { ApiErrorState } from "@/components/api-error-state"
import { TechnicalsPageClient } from "@/components/technical/technicals-page-client"
import { ApiError, getExchanges, requireUser } from "@/lib/api"
import { hasPermission, homePath } from "@/lib/permissions"
import type { ChartSettings, IndicatorId, PresetId } from "@/lib/technical"

export const metadata: Metadata = {
  title: "Technicals",
}

function first(value: string | string[] | undefined): string | undefined {
  return (Array.isArray(value) ? value[0] : value)?.trim() || undefined
}

/**
 * Companies > Technicals: technical analysis charts of a listing. The chart settings come from the URL
 * (?exchange=IDX&ticker=HRTA&interval=D&range=1Y&adjustment=split&preset=murphy&ind=sma50,sma200), else from the
 * user's saved settings; they are validated on the client.
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
  let exchanges
  try {
    exchanges = await getExchanges()
  } catch (error) {
    if (error instanceof ApiError) {
      return (
        <div className="flex flex-1 flex-col items-center justify-center gap-4 p-4 pt-0">
          <ApiErrorState error={error} />
        </div>
      )
    }
    throw error
  }

  const ticker = first(params.ticker)?.toUpperCase()
  const ind = first(params.ind)
  const initial: Partial<ChartSettings> = {
    ...(first(params.exchange) ? { exchange: first(params.exchange)!.toUpperCase() } : {}),
    ...(ticker ? { ticker } : {}),
    ...(first(params.interval) ? { interval: first(params.interval)!.toUpperCase() as ChartSettings["interval"] } : {}),
    ...(first(params.range) ? { range: first(params.range)!.toUpperCase() as ChartSettings["range"] } : {}),
    ...(first(params.adjustment) ? { adjustment: first(params.adjustment) as ChartSettings["adjustment"] } : {}),
    ...(first(params.preset) ? { preset: first(params.preset) as PresetId } : {}),
    ...(ind !== undefined ? { indicators: ind.split(",").map((s) => s.trim()).filter(Boolean) as IndicatorId[] } : {}),
  }

  return (
    <div className="flex flex-1 flex-col gap-4 p-4 pt-0">
      <TechnicalsPageClient exchanges={exchanges} initial={initial} />
    </div>
  )
}
