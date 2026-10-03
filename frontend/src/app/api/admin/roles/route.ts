import { forwardRequest } from "@/lib/api"

/** GET / POST /api/admin/roles -> backend /api/v1/admin/roles (ADMIN permission, checked by the backend). */
export async function GET(request: Request) {
  return forwardRequest(request, "/api/v1/admin/roles")
}

export async function POST(request: Request) {
  return forwardRequest(request, "/api/v1/admin/roles")
}
