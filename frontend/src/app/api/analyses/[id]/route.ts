import { forward } from "@/lib/api"
import { isJobId } from "@/lib/ingestion"

/** GET /api/analyses/{id} -> backend GET /api/v1/analyses/{id} (the report; polled while the analysis runs). */
export async function GET(_request: Request, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params
  if (!isJobId(id)) {
    return Response.json(
      { title: "Invalid analysis id", detail: `'${id}' is not an analysis id`, status: 400 },
      { status: 400 }
    )
  }
  return forward(`/api/v1/analyses/${encodeURIComponent(id)}`)
}
