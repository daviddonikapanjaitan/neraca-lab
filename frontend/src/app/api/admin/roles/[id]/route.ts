import { forwardRequest } from "@/lib/api"
import { invalidId, isNumericId } from "@/lib/route-ids"

type Context = { params: Promise<{ id: string }> }

/** GET / PUT / DELETE /api/admin/roles/{id} -> backend /api/v1/admin/roles/{id}. */
async function handle(request: Request, { params }: Context) {
  const { id } = await params
  if (!isNumericId(id)) return invalidId(id)
  return forwardRequest(request, `/api/v1/admin/roles/${id}`)
}

export { handle as GET, handle as PUT, handle as DELETE }
