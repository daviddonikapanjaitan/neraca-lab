import type { NextRequest } from "next/server"

import { forward } from "@/lib/api"

/**
 * GET /api/ownership/one-percent/highlights?date[&exchange] -> backend GET /api/v1/ownership/one-percent/highlights
 * (the list of a month with its largest buys and sells, the investors new above 1% and no longer above it, the names
 * that changed and the investors in the most companies).
 */
export async function GET(request: NextRequest) {
  const query = new URLSearchParams()
  for (const name of ["exchange", "date"]) {
    const value = request.nextUrl.searchParams.get(name)?.trim()
    if (value) query.set(name, value)
  }
  return forward(`/api/v1/ownership/one-percent/highlights?${query}`)
}
