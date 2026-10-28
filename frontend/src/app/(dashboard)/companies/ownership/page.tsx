import type { Metadata } from "next"

import { AccessDenied } from "@/components/access-denied"
import { ApiErrorState } from "@/components/api-error-state"
import { OwnershipPageClient } from "@/components/ownership/ownership-page-client"
import { ApiError, getOwnershipMovements, getOwnershipOptions, requireUser } from "@/lib/api"
import { DEFAULT_OWNERSHIP_PAGE_SIZE, filtersOf, ownershipQuery } from "@/lib/ownership"
import { hasPermission, homePath } from "@/lib/permissions"

export const metadata: Metadata = {
  title: "Ownership (>=5%)",
}

function first(value: string | string[] | undefined): string | undefined {
  return (Array.isArray(value) ? value[0] : value)?.trim() || undefined
}

/**
 * Companies > Ownership (>=5%): the shareholders of more than 5% of every IDX company day by day, from KSEI's daily
 * lists uploaded on Ingestion > Excel Documents (RAG). The filters come from the URL
 * (?tickers=ADRO&holder=lo kheng hong&transaction=BUY&status=A&from=2026-10-19&to=2026-10-27&aliases=true
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
  const filters = filtersOf({
    tickers: first(params.tickers),
    holder: first(params.holder),
    transaction: first(params.transaction),
    status: first(params.status),
    from: first(params.from),
    to: first(params.to),
    aliases: first(params.aliases),
    sort: first(params.sort),
  })

  let options
  let page
  try {
    ;[options, page] = await Promise.all([
      getOwnershipOptions(),
      getOwnershipMovements(ownershipQuery(filters, DEFAULT_OWNERSHIP_PAGE_SIZE, 0).toString()),
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
      <OwnershipPageClient key={ownershipQuery(filters).toString()} options={options} filters={filters} initial={page} />
    </div>
  )
}
