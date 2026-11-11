import { forwardRequest } from "@/lib/api"

/** POST /api/portfolio/positions {exchange, ticker, averagePrice, shares, dateIn} -> backend POST /api/v1/portfolio/positions. */
export async function POST(request: Request) {
  return forwardRequest(request, "/api/v1/portfolio/positions")
}
