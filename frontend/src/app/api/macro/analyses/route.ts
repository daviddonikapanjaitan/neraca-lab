import type { NextRequest } from "next/server"

import { forward, forwardRequest } from "@/lib/api"

/** GET /api/macro/analyses?country=&index=&limit=&offset= -> backend GET /api/v1/macro/analyses (polled by the pages). */
export async function GET(request: NextRequest) {
  const query = new URLSearchParams()
  for (const name of ["country", "index", "limit", "offset"]) {
    const value = request.nextUrl.searchParams.get(name)?.trim()
    if (value) query.set(name, value)
  }
  const suffix = query.size > 0 ? `?${query}` : ""
  return forward(`/api/v1/macro/analyses${suffix}`)
}

/** POST /api/macro/analyses with JSON { country, index, agents? } -> backend POST /api/v1/macro/analyses (202 new, 200 active). */
export async function POST(request: Request) {
  return forwardRequest(request, "/api/v1/macro/analyses")
}
