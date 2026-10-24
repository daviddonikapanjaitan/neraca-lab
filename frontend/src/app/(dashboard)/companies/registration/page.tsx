import type { Metadata } from "next"

import { AccessDenied } from "@/components/access-denied"
import { ApiErrorState } from "@/components/api-error-state"
import { RegistrationPageClient } from "@/components/registration/registration-page-client"
import { ApiError, getRegistrationOptions, getRegistrations, requireUser } from "@/lib/api"
import { hasPermission, homePath } from "@/lib/permissions"
import { DEFAULT_REGISTRATION_PAGE_SIZE, filtersOf, registrationQuery } from "@/lib/registration"

export const metadata: Metadata = {
  title: "Registration",
}

function first(value: string | string[] | undefined): string | undefined {
  return (Array.isArray(value) ? value[0] : value)?.trim() || undefined
}

/** This month in Jakarta, "2026-10" (the reports are monthly, IDX time). */
function currentMonth(): string {
  const parts = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Jakarta", year: "numeric", month: "2-digit" })
    .formatToParts(new Date())
  const part = (type: string) => parts.find((p) => p.type === type)?.value ?? ""
  return `${part("year")}-${part("month")}`
}

/**
 * Companies > Registration: the shares of the directors and commissioners month by month, from the monthly
 * registration reports of securities holders uploaded on Ingestion > PDF Documents (RAG). The filters come from the
 * URL (?tickers=HRTA,BBRI&transaction=BUY&role=DIRECTOR&from=2026-05&to=2026-08).
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
    transaction: first(params.transaction),
    role: first(params.role),
    from: first(params.from),
    to: first(params.to),
  })

  let options
  let page
  try {
    ;[options, page] = await Promise.all([
      getRegistrationOptions(),
      getRegistrations(registrationQuery(filters, DEFAULT_REGISTRATION_PAGE_SIZE, 0).toString()),
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
      <RegistrationPageClient
        key={registrationQuery(filters).toString()}
        options={options}
        filters={filters}
        initial={page}
        currentMonth={currentMonth()}
      />
    </div>
  )
}
