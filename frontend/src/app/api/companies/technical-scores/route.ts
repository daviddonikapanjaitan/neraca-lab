import { forward } from "@/lib/api"

/**
 * GET /api/companies/technical-scores?exchange= -> backend GET /api/v1/companies/technical-scores?exchange= (the
 * company list's Technical (Score) column).
 */
export async function GET(request: Request) {
  const exchange = new URL(request.url).searchParams.get("exchange")?.trim() || "IDX"
  return forward(`/api/v1/companies/technical-scores?exchange=${encodeURIComponent(exchange)}`)
}
