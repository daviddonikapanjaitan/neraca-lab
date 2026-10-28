import type { Metadata } from "next"

import { AccessDenied } from "@/components/access-denied"
import { ApiErrorState } from "@/components/api-error-state"
import { OnePercentPageClient } from "@/components/ownership-one-percent/one-percent-page-client"
import { ApiError, getOnePercentMovements, getOnePercentOptions, requireUser } from "@/lib/api"
import { DEFAULT_OWNERSHIP_PAGE_SIZE } from "@/lib/ownership"
import { onePercentFiltersOf, onePercentQuery } from "@/lib/ownership-one-percent"
import { hasPermission, homePath } from "@/lib/permissions"

export const metadata: Metadata = {
  title: "Ownership (>=1%)",
}

function first(value: string | string[] | undefined): string | undefined {
  return (Array.isArray(value) ? value[0] : value)?.trim() || undefined
}

/**
 * Companies > Ownership (>=1%): the shareholders of more than 1% of every IDX company month by month, from KSEI's
 * monthly lists uploaded on Ingestion > Excel Documents (RAG). The filters come from the URL
 * (?tickers=ADRO&holder=lo kheng hong&transaction=BUY&status=A&group=FUND&from=2026-02-01&to=2026-09-30
 * &sort=CHANGE_DESC).
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
  const filters = onePercentFiltersOf({
    tickers: first(params.tickers),
    holder: first(params.holder),
    transaction: first(params.transaction),
    status: first(params.status),
    group: first(params.group),
    from: first(params.from),
    to: first(params.to),
    sort: first(params.sort),
  })

  let options
  let page
  try {
    ;[options, page] = await Promise.all([
      getOnePercentOptions(),
      getOnePercentMovements(onePercentQuery(filters, DEFAULT_OWNERSHIP_PAGE_SIZE, 0).toString()),
    ])
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

  return (
    <div className="flex flex-1 flex-col gap-4 p-4 pt-0">
      {/* new filters: a fresh page (first page, its own state) */}
      <OnePercentPageClient key={onePercentQuery(filters).toString()} options={options} filters={filters} initial={page} />
    </div>
  )
}
