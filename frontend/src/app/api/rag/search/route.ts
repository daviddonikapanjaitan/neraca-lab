import { forward } from "@/lib/api"

const PARAMS = ["q", "exchange", "ticker", "source", "limit"]

/** GET /api/rag/search?q=[&exchange&ticker&source&limit] -> backend GET /api/v1/rag/search (closest chunks). */
export async function GET(request: Request) {
  const input = new URL(request.url).searchParams
  const query = new URLSearchParams()
  for (const name of PARAMS) {
    const value = input.get(name)?.trim()
    if (value) query.set(name, value)
  }
  return forward(`/api/v1/rag/search?${query}`)
}
