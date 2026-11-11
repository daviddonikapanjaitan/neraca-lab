import type { NextRequest } from "next/server"

import { forward } from "@/lib/api"

/** GET /api/macro/data/series/{code}?range= -> backend GET /api/v1/macro/data/series/{code} (Macro > Data: one chart). */
export async function GET(request: NextRequest, { params }: { params: Promise<{ code: string }> }) {
  const { code } = await params
  const range = request.nextUrl.searchParams.get("range")?.trim() || "1Y"
  return forward(`/api/v1/macro/data/series/${encodeURIComponent(code)}?${new URLSearchParams({ range })}`)
}
