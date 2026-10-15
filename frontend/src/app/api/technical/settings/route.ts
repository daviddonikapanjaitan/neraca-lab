import { forward, forwardRequest } from "@/lib/api"

/** GET /api/technical/settings -> backend GET /api/v1/technical/settings (the user's last chart settings; 204 none). */
export async function GET() {
  return forward("/api/v1/technical/settings")
}

/** PUT /api/technical/settings with a JSON object -> backend PUT /api/v1/technical/settings. */
export async function PUT(request: Request) {
  return forwardRequest(request, "/api/v1/technical/settings")
}
