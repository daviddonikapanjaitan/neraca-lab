import { forwardRequest } from "@/lib/api"

/** GET / PUT /api/profile -> backend /api/v1/profile (the logged-in user; address, phone, date of birth). */
export async function GET(request: Request) {
  return forwardRequest(request, "/api/v1/profile")
}

export async function PUT(request: Request) {
  return forwardRequest(request, "/api/v1/profile")
}
