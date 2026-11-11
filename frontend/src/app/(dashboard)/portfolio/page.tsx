import type { Metadata } from "next"

import { AccessDenied } from "@/components/access-denied"
import { ApiErrorState } from "@/components/api-error-state"
import { PortfolioPositionClient } from "@/components/portfolio/portfolio-position-client"
import { ApiError, getPortfolio, requireUser } from "@/lib/api"
import { hasPermission, homePath } from "@/lib/permissions"

export const metadata: Metadata = {
  title: "Portfolio Position",
}

/**
 * Portfolio > Position: the signed-in user's stocks as the sheet Position of instruction/Position Portfolio.xlsx -
 * each with its price, gain or loss and the Fundamentals of Companies (BVPS, forward EPS, D/E, EV/EBITDA, PER, PBV,
 * dividend, last filing, EBITDA, shares, market cap, debt, cash, EV, OP, EV/OP), the metrics (cash, total investment,
 * profit) and the sector allocation. Permission COMPANIES; each user sees only their own portfolio.
 */
export default async function Page() {
  const user = await requireUser()
  if (!hasPermission(user, "COMPANIES")) {
    return <AccessDenied permission="COMPANIES" homeHref={homePath(user)} />
  }

  let portfolio
  try {
    portfolio = await getPortfolio()
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
    <div className="flex min-w-0 flex-1 flex-col gap-4 p-4 pt-0">
      <PortfolioPositionClient initial={portfolio} />
    </div>
  )
}
