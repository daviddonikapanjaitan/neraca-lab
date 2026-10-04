import type { NextRequest } from "next/server"

import { forward, forwardRequest } from "@/lib/api"

/** GET /api/screenings?limit= -> backend GET /api/v1/screenings (polled by the screening page). */
export async function GET(request: NextRequest) {
  const limit = request.nextUrl.searchParams.get("limit")
  return forward(`/api/v1/screenings${limit ? `?limit=${encodeURIComponent(limit)}` : ""}`)
}

/** POST /api/screenings with JSON { exchange, marketCapTier, topN, agents } -> backend POST /api/v1/screenings (202). */
export async function POST(request: Request) {
  return forwardRequest(request, "/api/v1/screenings")
}
