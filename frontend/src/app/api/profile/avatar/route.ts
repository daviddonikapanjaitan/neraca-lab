import { forwardFile, forwardRequest } from "@/lib/api"

/** GET /api/profile/avatar: the own profile picture. */
export async function GET() {
  return forwardFile("/api/v1/profile/avatar")
}

/** PUT (multipart "file") / DELETE /api/profile/avatar -> backend /api/v1/profile/avatar. */
export async function PUT(request: Request) {
  return forwardRequest(request, "/api/v1/profile/avatar")
}

export async function DELETE(request: Request) {
  return forwardRequest(request, "/api/v1/profile/avatar")
}
