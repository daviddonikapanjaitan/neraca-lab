import type { NextRequest } from "next/server"

import { forwardRequest } from "@/lib/api"
import { performanceQuery } from "@/lib/portfolio-performance"

/** POST /api/portfolio/performance/snapshots?range= -> backend POST /api/v1/portfolio/performance/snapshots ("Save today"). */
export async function POST(request: NextRequest) {
  return forwardRequest(
    request,
    `/api/v1/portfolio/performance/snapshots${performanceQuery(request.nextUrl.searchParams.get("range"))}`
  )
}
