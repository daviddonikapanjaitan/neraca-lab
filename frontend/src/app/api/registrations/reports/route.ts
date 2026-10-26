import type { NextRequest } from "next/server"

import { forward } from "@/lib/api"

/**
 * GET /api/registrations/reports?ticker[&exchange] -> backend GET /api/v1/registrations/reports (the company's reports,
 * latest month first, with the shares by type of holder).
 */
export async function GET(request: NextRequest) {
  const query = new URLSearchParams()
  for (const name of ["exchange", "ticker"]) {
    const value = request.nextUrl.searchParams.get(name)?.trim()
    if (value) query.set(name, value)
  }
  return forward(`/api/v1/registrations/reports?${query}`)
}
