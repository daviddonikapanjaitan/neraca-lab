import type { Metadata } from "next"

import { IngestionPage } from "@/components/ingestion/ingestion-page"
import type { IngestionSectionData } from "@/components/ingestion/ingestion-page-client"
import { getCompanies, getExchanges, getPriceQueue, getPriceStatus } from "@/lib/api"
import { ingestionSection } from "@/lib/ingestion-sections"
import type { CompanySummary, PriceStatus } from "@/lib/types"

export const metadata: Metadata = {
  title: ingestionSection("prices").heading,
}

/**
 * Exchanges with their stored companies (the ticker dropdown), how far their prices and index reach (the cards on the
 * right) and the configured price provider.
 */
async function section(): Promise<IngestionSectionData> {
  const [exchanges, priceQueue] = await Promise.all([getExchanges(), getPriceQueue()])
  const [lists, statuses] = await Promise.all([
    Promise.all(exchanges.map((e) => getCompanies(e.code))),
    Promise.all(exchanges.map((e) => getPriceStatus(e.code))),
  ])
  const companiesByExchange: Record<string, CompanySummary[]> = {}
  const statusByExchange: Record<string, PriceStatus> = {}
  exchanges.forEach((e, i) => {
    companiesByExchange[e.code] = lists[i].companies
    statusByExchange[e.code] = statuses[i]
  })
  return { section: "prices", exchanges, companiesByExchange, statusByExchange, provider: priceQueue.provider }
}

export default function Page() {
  return <IngestionPage name="prices" section={section} />
}
