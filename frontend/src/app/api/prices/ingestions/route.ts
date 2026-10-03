import { forward } from "@/lib/api"

/**
 * POST /api/prices/ingestions with JSON { exchange, ticker, full } -> backend
 * POST /api/v1/prices/ingestions?exchange=&ticker=&full= (202 new job, 200 job already active).
 */
export async function POST(request: Request) {
  let input: { exchange?: unknown; ticker?: unknown; full?: unknown }
  try {
    input = await request.json()
  } catch {
    return Response.json(
      { title: "Invalid request", detail: "Expected a JSON body", status: 400 },
      { status: 400 }
    )
  }
  const exchange = typeof input.exchange === "string" ? input.exchange.trim() : ""
  const ticker = typeof input.ticker === "string" ? input.ticker.trim() : ""
  if (!exchange || !ticker) {
    return Response.json(
      { title: "Invalid request", detail: "Choose an exchange and a ticker", status: 400 },
      { status: 400 }
    )
  }
  const query = new URLSearchParams({ exchange, ticker, full: input.full === true ? "true" : "false" })
  return forward(`/api/v1/prices/ingestions?${query}`, { method: "POST" })
}
