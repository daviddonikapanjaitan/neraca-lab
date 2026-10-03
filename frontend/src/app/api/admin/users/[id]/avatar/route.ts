import { forwardFile } from "@/lib/api"
import { invalidId, isNumericId } from "@/lib/route-ids"

/** GET /api/admin/users/{id}/avatar -> the user's profile picture (ADMIN permission). */
export async function GET(_request: Request, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params
  if (!isNumericId(id)) return invalidId(id)
  return forwardFile(`/api/v1/admin/users/${id}/avatar`)
}
