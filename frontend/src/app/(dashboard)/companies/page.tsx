import type { Metadata } from "next"

import { ApiErrorState } from "@/components/api-error-state"
import { CompaniesPageClient } from "@/components/companies/companies-page-client"
import { ApiError, getCompanies, getExchanges } from "@/lib/api"
import type { CompanyListResponse, Exchange } from "@/lib/types"

export const metadata: Metadata = {
  title: "Companies",
}

const DEFAULT_EXCHANGE = "IDX"

async function load(exchange: string): Promise<
  | { exchanges: Exchange[]; list: CompanyListResponse; error?: never }
  | { exchanges?: never; list?: never; error: ApiError }
> {
  try {
    const [exchanges, list] = await Promise.all([getExchanges(), getCompanies(exchange)])
    return { exchanges, list }
  } catch (error) {
    if (error instanceof ApiError) return { error }
    throw error
  }
}

export default async function Page({
  searchParams,
}: {
  searchParams: Promise<{ [key: string]: string | string[] | undefined }>
}) {
  const params = await searchParams
  const raw = Array.isArray(params.exchange) ? params.exchange[0] : params.exchange
  const exchange = raw?.trim().toUpperCase() || DEFAULT_EXCHANGE

  const { exchanges, list, error } = await load(exchange)

  if (error) {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-4 p-4 pt-0">
        <ApiErrorState
          error={error}
          backHref={exchange === DEFAULT_EXCHANGE ? undefined : "/companies"}
          backLabel={`Show ${DEFAULT_EXCHANGE} companies`}
        />
      </div>
    )
  }

  return (
    <div className="flex flex-1 flex-col gap-4 p-4 pt-0">
      <CompaniesPageClient exchanges={exchanges} list={list} />
    </div>
  )
}
