import type { NextRequest } from "next/server"

import { forward } from "@/lib/api"

const PARAMS = ["exchange", "tickers", "holder", "transaction", "status", "group", "from", "to", "sort", "limit", "offset"]

/**
 * GET /api/ownership/one-percent[?tickers&holder&transaction&status&group&from&to&sort&limit&offset] -> backend
 * GET /api/v1/ownership/one-percent (one page of the investors of KSEI's monthly lists of the shareholders of more than
 * 1%, latest month first).
 */
export async function GET(request: NextRequest) {
  const query = new URLSearchParams()
  for (const name of PARAMS) {
    const value = request.nextUrl.searchParams.get(name)?.trim()
    if (value) query.set(name, value)
  }
  return forward(`/api/v1/ownership/one-percent${query.size > 0 ? `?${query}` : ""}`)
}
