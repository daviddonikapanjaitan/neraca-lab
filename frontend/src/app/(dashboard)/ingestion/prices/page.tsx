import type { Metadata } from "next"

import { IngestionPage } from "@/components/ingestion/ingestion-page"
import type { IngestionSectionData } from "@/components/ingestion/ingestion-page-client"
import { getCompanies, getExchanges, getPriceQueue } from "@/lib/api"
import { ingestionSection } from "@/lib/ingestion-sections"
import type { CompanySummary } from "@/lib/types"

export const metadata: Metadata = {
  title: ingestionSection("prices").heading,
}

/** Exchanges with their stored companies (the ticker dropdown) and the configured price provider. */
async function section(): Promise<IngestionSectionData> {
  const [exchanges, priceQueue] = await Promise.all([getExchanges(), getPriceQueue()])
  const lists = await Promise.all(exchanges.map((e) => getCompanies(e.code)))
  const companiesByExchange: Record<string, CompanySummary[]> = {}
  exchanges.forEach((e, i) => {
    companiesByExchange[e.code] = lists[i].companies
  })
  return { section: "prices", exchanges, companiesByExchange, provider: priceQueue.provider }
}

export default function Page() {
  return <IngestionPage section={section} />
}
