import { forward } from "@/lib/api"

/** POST /api/macro/updates -> backend POST /api/v1/macro/updates (202): queue every macro series that is behind. */
export async function POST() {
  return forward("/api/v1/macro/updates", { method: "POST" })
}
