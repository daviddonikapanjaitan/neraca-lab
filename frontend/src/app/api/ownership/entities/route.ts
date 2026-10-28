import type { NextRequest } from "next/server"

import { forward } from "@/lib/api"

/** GET /api/ownership/entities?q[&limit] -> backend GET /api/v1/ownership/entities (an investor's companies). */
export async function GET(request: NextRequest) {
  const query = new URLSearchParams()
  for (const name of ["exchange", "q", "limit"]) {
    const value = request.nextUrl.searchParams.get(name)?.trim()
    if (value) query.set(name, value)
  }
  return forward(`/api/v1/ownership/entities?${query}`)
}
