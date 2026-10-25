import { forward } from "@/lib/api"
import { isJobId } from "@/lib/ingestion"

/** GET /api/technical-screenings/{id} -> backend GET /api/v1/technical-screenings/{id} (polled while the run is active). */
export async function GET(_request: Request, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params
  if (!isJobId(id)) {
    return Response.json(
      { title: "Invalid technical screening id", detail: `'${id}' is not a technical screening id`, status: 400 },
      { status: 400 }
    )
  }
  return forward(`/api/v1/technical-screenings/${encodeURIComponent(id)}`)
}
