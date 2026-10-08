import type { NextRequest } from "next/server"

import { forward } from "@/lib/api"

/** GET /api/ingestions?type=&status=&limit=&offset= -> backend GET /api/v1/ingestions (polled by the ingestion page). */
export async function GET(request: NextRequest) {
  const query = new URLSearchParams()
  for (const name of ["type", "status", "limit", "offset"]) {
    const value = request.nextUrl.searchParams.get(name)
    if (value) query.set(name, value)
  }
  const suffix = query.size > 0 ? `?${query}` : ""
  return forward(`/api/v1/ingestions${suffix}`)
}
