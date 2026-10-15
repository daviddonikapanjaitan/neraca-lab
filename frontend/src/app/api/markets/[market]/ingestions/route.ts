import { forward } from "@/lib/api"

const MARKETS = new Set(["FOREX", "COMMODITY"])

function problem(status: number, title: string, detail: string) {
  return Response.json({ title, detail, status }, { status })
}

/**
 * POST /api/markets/{market}/ingestions with JSON { code, full } -> backend
 * POST /api/v1/markets/{market}/ingestions?code=&full= (202 new job, 200 job already active): the bars of one Forex pair
 * or commodity, in the price queue.
 */
export async function POST(request: Request, { params }: { params: Promise<{ market: string }> }) {
  const { market } = await params
  const code = market.toUpperCase()
  if (!MARKETS.has(code)) {
    return problem(404, "Not found", `Unknown market '${market}'`)
  }
  let input: { code?: unknown; full?: unknown }
  try {
    input = await request.json()
  } catch {
    return problem(400, "Invalid request", "Expected a JSON body")
  }
  const instrument = typeof input.code === "string" ? input.code.trim() : ""
  if (!instrument) {
    return problem(400, "Invalid request", code === "FOREX" ? "Choose a currency" : "Choose a commodity")
  }
  const query = new URLSearchParams({ code: instrument, full: input.full === true ? "true" : "false" })
  return forward(`/api/v1/markets/${code}/ingestions?${query}`, { method: "POST" })
}
