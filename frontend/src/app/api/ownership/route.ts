import type { NextRequest } from "next/server"

import { forward } from "@/lib/api"

const PARAMS = ["exchange", "tickers", "holder", "transaction", "status", "from", "to", "aliases", "sort", "limit", "offset"]

/**
 * GET /api/ownership[?tickers&holder&transaction&status&from&to&aliases&sort&limit&offset] -> backend GET /api/v1/ownership
 * (one page of the investors of KSEI's daily lists of the shareholders of more than 5%, latest day first).
 */
export async function GET(request: NextRequest) {
  const query = new URLSearchParams()
  for (const name of PARAMS) {
    const value = request.nextUrl.searchParams.get(name)?.trim()
    if (value) query.set(name, value)
  }
  return forward(`/api/v1/ownership${query.size > 0 ? `?${query}` : ""}`)
}
