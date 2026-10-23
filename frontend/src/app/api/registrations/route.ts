import type { NextRequest } from "next/server"

import { forward } from "@/lib/api"

const PARAMS = ["exchange", "tickers", "transaction", "role", "from", "to", "limit", "offset"]

/**
 * GET /api/registrations[?exchange&tickers&transaction&role&from&to&limit&offset] -> backend GET /api/v1/registrations
 * (one page of the directors' and commissioners' shares, latest month first).
 */
export async function GET(request: NextRequest) {
  const query = new URLSearchParams()
  for (const name of PARAMS) {
    const value = request.nextUrl.searchParams.get(name)?.trim()
    if (value) query.set(name, value)
  }
  return forward(`/api/v1/registrations${query.size > 0 ? `?${query}` : ""}`)
}
