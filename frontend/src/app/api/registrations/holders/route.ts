import type { NextRequest } from "next/server"

import { forward } from "@/lib/api"

/**
 * GET /api/registrations/holders?ticker[&exchange] -> backend GET /api/v1/registrations/holders (the company's
 * shareholders of 5% or more, latest month first).
 */
export async function GET(request: NextRequest) {
  const query = new URLSearchParams()
  for (const name of ["exchange", "ticker"]) {
    const value = request.nextUrl.searchParams.get(name)?.trim()
    if (value) query.set(name, value)
  }
  return forward(`/api/v1/registrations/holders?${query}`)
}
