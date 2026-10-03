import { cookies } from "next/headers"

import { forward } from "@/lib/api"
import { SESSION_COOKIE } from "@/lib/session-cookie"

/** POST /api/auth/logout: ends the backend session and removes the cookie (204, also without a session). */
export async function POST() {
  await forward("/api/v1/auth/logout", { method: "POST" })
  ;(await cookies()).delete(SESSION_COOKIE)
  return new Response(null, { status: 204 })
}
