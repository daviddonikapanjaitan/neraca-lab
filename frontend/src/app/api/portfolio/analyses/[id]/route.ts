import { forward } from "@/lib/api"
import { isJobId } from "@/lib/ingestion"

type Params = { params: Promise<{ id: string }> }

/** GET /api/portfolio/analyses/{id} -> backend GET /api/v1/portfolio/analyses/{id} (the report, also while it runs). */
export async function GET(_request: Request, { params }: Params) {
  const { id } = await params
  if (!isJobId(id)) {
    return Response.json(
      { title: "Analysis not found", status: 404, detail: "No such portfolio analysis of yours" },
      { status: 404, headers: { "Content-Type": "application/problem+json" } }
    )
  }
  return forward(`/api/v1/portfolio/analyses/${encodeURIComponent(id)}`)
}
