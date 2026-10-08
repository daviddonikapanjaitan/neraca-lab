import type { NextRequest } from "next/server"

import { forward, forwardRequest } from "@/lib/api"

/** GET /api/analyses?ticker=&limit=&offset= -> backend GET /api/v1/analyses (polled by the analysis page). */
export async function GET(request: NextRequest) {
  const query = new URLSearchParams()
  for (const name of ["ticker", "limit", "offset"]) {
    const value = request.nextUrl.searchParams.get(name)?.trim()
    if (value) query.set(name, value)
  }
  const suffix = query.size > 0 ? `?${query}` : ""
  return forward(`/api/v1/analyses${suffix}`)
}

/** POST /api/analyses with JSON { ticker, exchange?, agents? } -> backend POST /api/v1/analyses (202 new, 200 active). */
export async function POST(request: Request) {
  return forwardRequest(request, "/api/v1/analyses")
}
