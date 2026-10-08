import { forward } from "@/lib/api"

const PARAMS = ["exchange", "ticker", "tickerPrefix", "source", "limit", "offset"]

/**
 * GET /api/rag/documents[?exchange&ticker&tickerPrefix&source&limit&offset] -> backend GET /api/v1/rag/documents
 * (one page of the stored documents).
 */
export async function GET(request: Request) {
  const input = new URL(request.url).searchParams
  const query = new URLSearchParams()
  for (const name of PARAMS) {
    const value = input.get(name)?.trim()
    if (value) query.set(name, value)
  }
  const suffix = query.size > 0 ? `?${query}` : ""
  return forward(`/api/v1/rag/documents${suffix}`)
}
