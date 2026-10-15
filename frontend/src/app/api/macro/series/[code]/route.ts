import type { NextRequest } from "next/server"

import { forward } from "@/lib/api"

/** GET /api/macro/series/{code}?from= -> backend GET /api/v1/macro/series/{code} (the series and its observations). */
export async function GET(request: NextRequest, { params }: { params: Promise<{ code: string }> }) {
  const { code } = await params
  const from = request.nextUrl.searchParams.get("from")?.trim()
  const suffix = from ? `?${new URLSearchParams({ from })}` : ""
  return forward(`/api/v1/macro/series/${encodeURIComponent(code)}${suffix}`)
}
