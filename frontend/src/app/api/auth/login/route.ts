import { cookies } from "next/headers"

import { backendLogin } from "@/lib/api"
import { SESSION_COOKIE } from "@/lib/session-cookie"
import type { User } from "@/lib/types"

/** The request reached this server over HTTPS (directly or behind a proxy). */
function isHttps(request: Request): boolean {
  const forwarded = request.headers.get("x-forwarded-proto")
  return forwarded ? forwarded.split(",")[0].trim() === "https" : new URL(request.url).protocol === "https:"
}

/**
 * POST /api/auth/login {username, password} -> backend POST /api/v1/auth/login. On success the
 * session token is stored in an httpOnly cookie (never readable by JavaScript) and only the user is
 * returned; errors (401 wrong credentials, 400) are passed on.
 */
export async function POST(request: Request) {
  const response = await backendLogin(await request.text())
  if (!response.ok) {
    return new Response(await response.arrayBuffer(), {
      status: response.status,
      headers: { "Content-Type": response.headers.get("Content-Type") ?? "application/json" },
    })
  }
  const { token, expiresAt, user } = (await response.json()) as { token: string; expiresAt: string; user: User }
  ;(await cookies()).set(SESSION_COOKIE, token, {
    httpOnly: true,
    sameSite: "lax",
    secure: isHttps(request),
    path: "/",
    expires: new Date(expiresAt),
  })
  return Response.json({ user, expiresAt })
}
