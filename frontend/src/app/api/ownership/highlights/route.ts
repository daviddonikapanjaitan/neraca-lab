import type { NextRequest } from "next/server"

import { forward } from "@/lib/api"

/**
 * GET /api/ownership/highlights?date[&exchange] -> backend GET /api/v1/ownership/highlights (the list of a day with its
 * largest buys and sells and the investors that crossed 5% or fell below it).
 */
export async function GET(request: NextRequest) {
  const query = new URLSearchParams()
  for (const name of ["exchange", "date"]) {
    const value = request.nextUrl.searchParams.get(name)?.trim()
    if (value) query.set(name, value)
  }
  return forward(`/api/v1/ownership/highlights?${query}`)
}
