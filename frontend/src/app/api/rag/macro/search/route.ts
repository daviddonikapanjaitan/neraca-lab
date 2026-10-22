import { forward } from "@/lib/api"

const PARAMS = ["q", "country", "limit"]

/** GET /api/rag/macro/search?q=[&country&limit] -> backend GET /api/v1/rag/macro/search (closest chunks of a country). */
export async function GET(request: Request) {
  const input = new URL(request.url).searchParams
  const query = new URLSearchParams()
  for (const name of PARAMS) {
    const value = input.get(name)?.trim()
    if (value) query.set(name, value)
  }
  return forward(`/api/v1/rag/macro/search?${query}`)
}
