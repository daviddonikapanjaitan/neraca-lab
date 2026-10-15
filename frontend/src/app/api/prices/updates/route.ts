import { forward } from "@/lib/api"

/**
 * POST /api/prices/updates with JSON { exchange, index } -> backend
 * POST /api/v1/prices/updates?exchange=&index= (202): queue every active company of the exchange whose prices end
 * before the last completed trading day (only the missing days are fetched), and the exchange's index (IHSG).
 */
export async function POST(request: Request) {
  let input: { exchange?: unknown; index?: unknown }
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
  const query = new URLSearchParams({ exchange, index: input.index === false ? "false" : "true" })
  return forward(`/api/v1/prices/updates?${query}`, { method: "POST" })
}
