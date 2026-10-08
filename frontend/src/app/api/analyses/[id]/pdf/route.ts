import { forwardFile } from "@/lib/api"
import { isJobId } from "@/lib/ingestion"

/** GET /api/analyses/{id}/pdf -> backend GET /api/v1/analyses/{id}/pdf (the report as PDF download). */
export async function GET(_request: Request, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params
  if (!isJobId(id)) {
    return Response.json(
      { title: "Invalid analysis id", detail: `'${id}' is not an analysis id`, status: 400 },
      { status: 400 }
    )
  }
  return forwardFile(`/api/v1/analyses/${encodeURIComponent(id)}/pdf`)
}
