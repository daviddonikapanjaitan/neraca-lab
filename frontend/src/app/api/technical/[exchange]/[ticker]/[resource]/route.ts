import type { NextRequest } from "next/server"

import { forward } from "@/lib/api"

const RESOURCES = new Set(["ohlcv", "indicators", "levels", "snapshot", "events"])
const PARAMS = ["interval", "range", "from", "to", "set", "adjustment"]

/**
 * GET /api/technical/{exchange}/{ticker}/{ohlcv|indicators|levels|snapshot|events}?... -> backend
 * GET /api/v1/technical/{exchange}/{ticker}/{resource} with the chart parameters.
 */
export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ exchange: string; ticker: string; resource: string }> }
) {
  const { exchange, ticker, resource } = await params
  if (!RESOURCES.has(resource)) {
    return Response.json(
      { title: "Not found", status: 404, detail: `Unknown technical resource '${resource}'` },
      { status: 404, headers: { "Content-Type": "application/problem+json" } }
    )
  }
  const query = new URLSearchParams()
  for (const name of PARAMS) {
    const value = request.nextUrl.searchParams.get(name)?.trim()
    if (value) query.set(name, value)
  }
  const suffix = query.size > 0 ? `?${query}` : ""
  return forward(
    `/api/v1/technical/${encodeURIComponent(exchange)}/${encodeURIComponent(ticker)}/${resource}${suffix}`
  )
}
