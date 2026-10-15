import { forward } from "@/lib/api"

/**
 * POST /api/prices/index-ingestions with JSON { exchange, full } -> backend
 * POST /api/v1/prices/index-ingestions?exchange=&full= (202 new job, 200 job already active): the exchange's index
 * (IHSG from Yahoo Finance ^JKSE) in the price queue.
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
  const exchange = typeof input.exchange === "string" ? input.exchange.trim() : ""
  if (!exchange) {
    return Response.json(
      { title: "Invalid request", detail: "Choose an exchange", status: 400 },
      { status: 400 }
    )
  }
  const query = new URLSearchParams({ exchange, full: input.full === true ? "true" : "false" })
  return forward(`/api/v1/prices/index-ingestions?${query}`, { method: "POST" })
}
