import { forwardFile } from "@/lib/api"
import { isJobId } from "@/lib/ingestion"

/** GET /api/screenings/{id}/pdf -> backend GET /api/v1/screenings/{id}/pdf (the report as PDF download). */
export async function GET(_request: Request, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params
  if (!isJobId(id)) {
    return Response.json(
      { title: "Invalid screening id", detail: `'${id}' is not a screening id`, status: 400 },
      { status: 400 }
    )
  }
  return forwardFile(`/api/v1/screenings/${encodeURIComponent(id)}/pdf`)
}
