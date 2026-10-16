import type { NextRequest } from "next/server"

import { forward } from "@/lib/api"

/** GET /api/macro/outlook?country=&index=&range= -> backend GET /api/v1/macro/outlook (the Macro page). */
export async function GET(request: NextRequest) {
  const query = new URLSearchParams()
  for (const name of ["country", "index", "range"]) {
    const value = request.nextUrl.searchParams.get(name)?.trim()
    if (value) query.set(name, value)
  }
  return forward(`/api/v1/macro/outlook${query.size > 0 ? `?${query}` : ""}`)
}
