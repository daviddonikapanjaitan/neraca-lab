import type { NextRequest } from "next/server"

import { forward } from "@/lib/api"

/** GET /api/rag/xlsx/reports[?limit&offset] -> backend GET /api/v1/rag/xlsx/reports (the stored ownership lists). */
export async function GET(request: NextRequest) {
  const query = new URLSearchParams()
  for (const name of ["exchange", "documentType", "limit", "offset"]) {
    const value = request.nextUrl.searchParams.get(name)?.trim()
    if (value) query.set(name, value)
  }
  return forward(`/api/v1/rag/xlsx/reports${query.size > 0 ? `?${query}` : ""}`)
}
