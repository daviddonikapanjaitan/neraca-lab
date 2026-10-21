import { forward } from "@/lib/api"
import { isJobId } from "@/lib/ingestion"

/** GET /api/macro/analyses/{id} -> backend GET /api/v1/macro/analyses/{id} (the report; polled while it runs). */
export async function GET(_request: Request, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params
  if (!isJobId(id)) {
    return Response.json(
      { title: "Invalid analysis id", detail: `'${id}' is not a macro analysis id`, status: 400 },
      { status: 400 }
    )
  }
  return forward(`/api/v1/macro/analyses/${encodeURIComponent(id)}`)
}
