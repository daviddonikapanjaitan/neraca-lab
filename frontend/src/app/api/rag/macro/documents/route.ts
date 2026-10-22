import { forward } from "@/lib/api"

const PARAMS = ["country", "documentType", "limit", "offset"]

/**
 * GET /api/rag/macro/documents[?country&documentType&limit&offset] -> backend GET /api/v1/rag/macro/documents (one
 * page of the stored macro documents, latest publication first).
 */
export async function GET(request: Request) {
  const input = new URL(request.url).searchParams
  const query = new URLSearchParams()
  for (const name of PARAMS) {
    const value = input.get(name)?.trim()
    if (value) query.set(name, value)
  }
  const suffix = query.size > 0 ? `?${query}` : ""
  return forward(`/api/v1/rag/macro/documents${suffix}`)
}
