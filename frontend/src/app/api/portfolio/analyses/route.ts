import type { NextRequest } from "next/server"

import { forward, forwardRequest } from "@/lib/api"

/** GET /api/portfolio/analyses?limit= -> backend GET /api/v1/portfolio/analyses (the user's runs). */
export async function GET(request: NextRequest) {
  const limit = request.nextUrl.searchParams.get("limit")?.trim()
  const query = limit && /^\d{1,3}$/.test(limit) ? `?${new URLSearchParams({ limit })}` : ""
  return forward(`/api/v1/portfolio/analyses${query}`)
}

/** POST /api/portfolio/analyses {mode, maxStocks, tickers, addedCapital} -> backend POST /api/v1/portfolio/analyses. */
export async function POST(request: Request) {
  return forwardRequest(request, "/api/v1/portfolio/analyses")
}
