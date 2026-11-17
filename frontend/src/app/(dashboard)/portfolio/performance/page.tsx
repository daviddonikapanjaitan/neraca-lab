import type { Metadata } from "next"

import { AccessDenied } from "@/components/access-denied"
import { ApiErrorState } from "@/components/api-error-state"
import { PerformanceClient } from "@/components/portfolio/performance-client"
import { ApiError, getPortfolioPerformance, requireUser } from "@/lib/api"
import { hasPermission, homePath } from "@/lib/permissions"
import { PERFORMANCE_ALL, type Performance } from "@/lib/portfolio-performance"

export const metadata: Metadata = {
  title: "Portfolio Performance",
}

function first(value: string | string[] | undefined): string | undefined {
  return (Array.isArray(value) ? value[0] : value)?.trim() || undefined
}

/** The page's data, or the API's error. */
async function load(range: string): Promise<Performance | ApiError> {
  try {
    return await getPortfolioPerformance(range)
  } catch (error) {
    if (error instanceof ApiError) return error
    throw error
  }
}

/**
 * Portfolio > Performance: the sheet tracking_performance of instruction/Position Portfolio.xlsx - the portfolio
 * against IHSG and LQ45 from 2022 to the end of 2026 on one chart (as Macro > Data), the years side by side and in a
 * table, and the snapshots saved every IDX trading day at or after 17:00 WIB. The range is in the URL (?range=2025;
 * ALL by default). Permission COMPANIES; each user sees only their own portfolio.
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

  const wanted = first((await searchParams).range)?.toUpperCase() ?? PERFORMANCE_ALL
  let performance = await load(wanted)
  // an unknown range in the URL: the whole period instead of an error
  if (performance instanceof ApiError && performance.status === 400 && wanted !== PERFORMANCE_ALL) {
    performance = await load(PERFORMANCE_ALL)
  }
  if (performance instanceof ApiError) {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-4 p-4 pt-0">
        <ApiErrorState error={performance} />
      </div>
    )
  }

  return (
    <div className="flex min-w-0 flex-1 flex-col gap-4 p-4 pt-0">
      <PerformanceClient initial={performance} />
    </div>
  )
}
