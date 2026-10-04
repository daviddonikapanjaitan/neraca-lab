import { forward } from "@/lib/api"

/**
 * POST /api/fundamentals/ingestions with JSON { exchange, full } -> backend
 * POST /api/v1/fundamentals/ingestions?exchange=&full= (202 new run, 200 run already active).
 */
export async function POST(request: Request) {
  let input: { exchange?: unknown; full?: unknown }
  try {
    input = await request.json()
  } catch {
    return Response.json(
      { title: "Invalid request", detail: "Expected a JSON body", status: 400 },
      { status: 400 }
    )
  }
  const exchange = typeof input.exchange === "string" && input.exchange.trim() ? input.exchange.trim() : "IDX"
  const query = new URLSearchParams({ exchange, full: input.full === true ? "true" : "false" })
  return forward(`/api/v1/fundamentals/ingestions?${query}`, { method: "POST" })
}
