import { forwardRequest } from "@/lib/api"

/** GET / POST /api/admin/users -> backend /api/v1/admin/users (ADMIN permission, checked by the backend). */
export async function GET(request: Request) {
  return forwardRequest(request, "/api/v1/admin/users")
}

export async function POST(request: Request) {
  return forwardRequest(request, "/api/v1/admin/users")
}
