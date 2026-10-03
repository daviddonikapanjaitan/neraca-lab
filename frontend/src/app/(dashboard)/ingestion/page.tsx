import type { Metadata } from "next"

import { AccessDenied } from "@/components/access-denied"
import { ApiErrorState } from "@/components/api-error-state"
import { IngestionPageClient } from "@/components/ingestion/ingestion-page-client"
import { ApiError, getCompanies, getExchanges, getIngestions, getPriceQueue, requireUser } from "@/lib/api"
import { JOB_LIMIT } from "@/lib/ingestion"
import { hasPermission, homePath } from "@/lib/permissions"
import type { CompanySummary, Exchange, IngestionJobList } from "@/lib/types"

export const metadata: Metadata = {
  title: "Ingestion",
}

interface Data {
  exchanges: Exchange[]
  companiesByExchange: Record<string, CompanySummary[]>
  provider: string
  jobs: IngestionJobList
}

async function load(): Promise<{ data: Data; error?: never } | { data?: never; error: ApiError }> {
  try {
    const [exchanges, jobs, priceQueue] = await Promise.all([
      getExchanges(),
      getIngestions(JOB_LIMIT),
      getPriceQueue(),
    ])
    const lists = await Promise.all(exchanges.map((e) => getCompanies(e.code)))
    const companiesByExchange: Record<string, CompanySummary[]> = {}
    exchanges.forEach((e, i) => {
      companiesByExchange[e.code] = lists[i].companies
    })
    return { data: { exchanges, companiesByExchange, provider: priceQueue.provider, jobs } }
  } catch (error) {
    if (error instanceof ApiError) return { error }
    throw error
  }
}

export default async function Page() {
  const user = await requireUser()
  if (!hasPermission(user, "INGESTION")) {
    return <AccessDenied permission="INGESTION" homeHref={homePath(user)} />
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
      <IngestionPageClient {...data} />
    </div>
  )
}
