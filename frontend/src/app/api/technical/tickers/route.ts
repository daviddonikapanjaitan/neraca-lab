import type { NextRequest } from "next/server"

import { forward } from "@/lib/api"

/** GET /api/technical/tickers?exchange=&query=&limit= -> backend GET /api/v1/technical/tickers (ticker search). */
export async function GET(request: NextRequest) {
  const query = new URLSearchParams()
  for (const name of ["exchange", "query", "limit"]) {
    const value = request.nextUrl.searchParams.get(name)?.trim()
    if (value) query.set(name, value)
  }
  return forward(`/api/v1/technical/tickers${query.size > 0 ? `?${query}` : ""}`)
}
