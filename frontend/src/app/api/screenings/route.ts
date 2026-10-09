import type { NextRequest } from "next/server"

import { forward, forwardRequest } from "@/lib/api"

/** GET /api/screenings?limit=&scope= -> backend GET /api/v1/screenings (polled by the screening pages). */
export async function GET(request: NextRequest) {
  const query = new URLSearchParams()
  for (const name of ["limit", "scope"]) {
    const value = request.nextUrl.searchParams.get(name)
    if (value) query.set(name, value)
  }
  const search = query.toString()
  return forward(`/api/v1/screenings${search ? `?${search}` : ""}`)
}

/**
 * POST /api/screenings with JSON { exchange, marketCapTier, topN, agents } or { exchange, tickers, topN, agents }
 * -> backend POST /api/v1/screenings (202).
 */
export async function POST(request: Request) {
  return forwardRequest(request, "/api/v1/screenings")
}
