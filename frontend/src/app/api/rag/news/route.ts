import { forward } from "@/lib/api"

const DATE = /^\d{4}-\d{2}-\d{2}$/

/**
 * POST /api/rag/news with JSON { exchange, ticker, from, to } (ISO dates) -> backend
 * POST /api/v1/rag/news?exchange=&ticker=&from=&to= (202 new job, 200 job already active for the company).
 */
export async function POST(request: Request) {
  let input: { exchange?: unknown; ticker?: unknown; from?: unknown; to?: unknown }
  try {
    input = await request.json()
  } catch {
    return Response.json({ title: "Invalid request", detail: "Expected a JSON body", status: 400 }, { status: 400 })
  }
  const exchange = typeof input.exchange === "string" ? input.exchange.trim() : ""
  const ticker = typeof input.ticker === "string" ? input.ticker.trim() : ""
  const from = typeof input.from === "string" ? input.from.trim() : ""
  const to = typeof input.to === "string" ? input.to.trim() : ""
  if (!exchange || !ticker) {
    return Response.json(
      { title: "Invalid request", detail: "Choose an exchange and a ticker", status: 400 },
      { status: 400 }
    )
  }
  if (!DATE.test(from) || !DATE.test(to)) {
    return Response.json(
      { title: "Invalid request", detail: "Choose a start and an end date", status: 400 },
      { status: 400 }
    )
  }
  const query = new URLSearchParams({ exchange, ticker, from, to })
  return forward(`/api/v1/rag/news?${query}`, { method: "POST" })
}
