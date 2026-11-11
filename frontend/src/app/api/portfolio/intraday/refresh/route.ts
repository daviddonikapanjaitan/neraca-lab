import { forwardRequest } from "@/lib/api"

/** POST /api/portfolio/intraday/refresh -> backend POST /api/v1/portfolio/intraday/refresh (the user's stocks, now). */
export async function POST(request: Request) {
  return forwardRequest(request, "/api/v1/portfolio/intraday/refresh")
}
