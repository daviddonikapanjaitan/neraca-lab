import type { NextRequest } from "next/server"

import { forward } from "@/lib/api"

/**
 * GET /api/ownership/one-percent/holders?ticker[&from&to] -> backend GET /api/v1/ownership/one-percent/holders (one
 * company: its investors in every stored month, and its digest).
 */
export async function GET(request: NextRequest) {
  const query = new URLSearchParams()
  for (const name of ["exchange", "ticker", "from", "to"]) {
    const value = request.nextUrl.searchParams.get(name)?.trim()
    if (value) query.set(name, value)
  }
  return forward(`/api/v1/ownership/one-percent/holders?${query}`)
}
