import { forwardRequest } from "@/lib/api"

/** GET /api/admin/permissions -> backend /api/v1/admin/permissions. */
export async function GET(request: Request) {
  return forwardRequest(request, "/api/v1/admin/permissions")
}
