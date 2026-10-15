import { forward } from "@/lib/api"

/**
 * POST /api/macro/ingestions with JSON { code, full } -> backend POST /api/v1/macro/ingestions?code=&full= (202 new
 * job, 200 job already active): one macro series from its source.
 */
export async function POST(request: Request) {
  let input: { code?: unknown; full?: unknown }
  try {
    input = await request.json()
  } catch {
    return Response.json({ title: "Invalid request", detail: "Expected a JSON body", status: 400 }, { status: 400 })
  }
  const code = typeof input.code === "string" ? input.code.trim() : ""
  if (!code) {
    return Response.json({ title: "Invalid request", detail: "Choose a series", status: 400 }, { status: 400 })
  }
  const query = new URLSearchParams({ code, full: input.full === true ? "true" : "false" })
  return forward(`/api/v1/macro/ingestions?${query}`, { method: "POST" })
}
