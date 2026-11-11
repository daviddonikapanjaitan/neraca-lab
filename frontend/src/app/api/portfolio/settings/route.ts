import { forwardRequest } from "@/lib/api"

/** PUT /api/portfolio/settings {cash, totalInvestment} -> backend PUT /api/v1/portfolio/settings. */
export async function PUT(request: Request) {
  return forwardRequest(request, "/api/v1/portfolio/settings")
}
