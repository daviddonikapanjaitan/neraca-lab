import { forward } from "@/lib/api"

/** GET /api/registrations/companies -> backend GET /api/v1/registrations/companies (companies with reports, options). */
export async function GET() {
  return forward("/api/v1/registrations/companies")
}
