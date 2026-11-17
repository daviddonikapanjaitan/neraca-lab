import { forward } from "@/lib/api"

/** GET /api/portfolio/analyses/options -> backend GET /api/v1/portfolio/analyses/options. */
export async function GET() {
  return forward("/api/v1/portfolio/analyses/options")
}
