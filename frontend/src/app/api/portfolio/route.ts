import { forward } from "@/lib/api"

/** GET /api/portfolio -> backend GET /api/v1/portfolio (Portfolio > Position of the signed-in user). */
export async function GET() {
  return forward("/api/v1/portfolio")
}
