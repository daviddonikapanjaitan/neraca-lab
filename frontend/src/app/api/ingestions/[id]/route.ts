import { forward } from "@/lib/api"
import { isJobId } from "@/lib/ingestion"

/** GET /api/ingestions/{id} -> backend GET /api/v1/ingestions/{id} (one job with its result). */
export async function GET(_request: Request, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params
  if (!isJobId(id)) {
    return Response.json(
      { title: "Invalid job id", detail: `'${id}' is not a job id`, status: 400 },
      { status: 400 }
    )
  }
  return forward(`/api/v1/ingestions/${encodeURIComponent(id)}`)
}
