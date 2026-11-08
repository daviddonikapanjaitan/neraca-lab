import { forward } from "@/lib/api"

/** GET /api/macro/data/series -> backend GET /api/v1/macro/data/series (Macro > Data: the series). */
export async function GET() {
  return forward("/api/v1/macro/data/series")
}
