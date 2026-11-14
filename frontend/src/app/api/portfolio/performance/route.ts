import type { NextRequest } from "next/server"

import { forward } from "@/lib/api"
import { performanceQuery } from "@/lib/portfolio-performance"

/** GET /api/portfolio/performance?range= -> backend GET /api/v1/portfolio/performance (Portfolio > Performance). */
export async function GET(request: NextRequest) {
  return forward(`/api/v1/portfolio/performance${performanceQuery(request.nextUrl.searchParams.get("range"))}`)
}
