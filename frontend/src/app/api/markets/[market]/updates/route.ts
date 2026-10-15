import { forward } from "@/lib/api"

const MARKETS = new Set(["FOREX", "COMMODITY"])

/**
 * POST /api/markets/{market}/updates -> backend POST /api/v1/markets/{market}/updates (202): queue every Forex pair or
 * commodity whose bars end before its last completed day (only the missing days are fetched).
 */
export async function POST(_request: Request, { params }: { params: Promise<{ market: string }> }) {
  const { market } = await params
  const code = market.toUpperCase()
  if (!MARKETS.has(code)) {
    return Response.json({ title: "Not found", detail: `Unknown market '${market}'`, status: 404 }, { status: 404 })
  }
  return forward(`/api/v1/markets/${code}/updates`, { method: "POST" })
}
