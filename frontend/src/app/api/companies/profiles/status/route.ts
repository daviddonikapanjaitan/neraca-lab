import { forward } from "@/lib/api"

/** GET /api/companies/profiles/status?exchange= -> backend GET /api/v1/companies/profiles/status?exchange= */
export async function GET(request: Request) {
  const exchange = new URL(request.url).searchParams.get("exchange")?.trim() || "IDX"
  return forward(`/api/v1/companies/profiles/status?exchange=${encodeURIComponent(exchange)}`)
}
