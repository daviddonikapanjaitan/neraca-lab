import type { NextRequest } from "next/server"

import { forward } from "@/lib/api"

/** GET /api/technical/market?exchange=&range= -> backend GET /api/v1/technical/market (index regime, breadth, macro). */
export async function GET(request: NextRequest) {
  const query = new URLSearchParams()
  for (const name of ["exchange", "range"]) {
    const value = request.nextUrl.searchParams.get(name)?.trim()
    if (value) query.set(name, value)
  }
  return forward(`/api/v1/technical/market${query.size > 0 ? `?${query}` : ""}`)
}
