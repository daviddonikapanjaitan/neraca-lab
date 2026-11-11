import { forward } from "@/lib/api"

/** GET /api/companies/quant-scores?exchange= -> backend GET /api/v1/companies/quant-scores?exchange= (the company list). */
export async function GET(request: Request) {
  const exchange = new URL(request.url).searchParams.get("exchange")?.trim() || "IDX"
  return forward(`/api/v1/companies/quant-scores?exchange=${encodeURIComponent(exchange)}`)
}
