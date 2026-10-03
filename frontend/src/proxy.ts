import { NextResponse } from "next/server"
import type { NextRequest } from "next/server"

import { SESSION_COOKIE } from "@/lib/session-cookie"

/**
 * Optimistic check for pages: without a session cookie every page except /login redirects to the
 * login page (remembering where to go back). The real check is done by the backend on every API
 * call (the dashboard layout also verifies the session and the pages their permission).
 *
 * API routes (/api/...) are excluded: they pass the session to the backend, which answers 401
 * without one, and a proxy would buffer request bodies (uploads up to 20 MB) with a 10 MB limit.
 */
export function proxy(request: NextRequest) {
  const { pathname, search } = request.nextUrl
  if (pathname === "/login" || request.cookies.has(SESSION_COOKIE)) {
    return NextResponse.next()
  }
  const login = new URL("/login", request.url)
  if (pathname !== "/") {
    login.searchParams.set("next", pathname + search)
  }
  return NextResponse.redirect(login)
}

export const config = {
  // every page; not API routes, Next.js assets or static files
  matcher: ["/((?!api/|_next/static|_next/image|favicon\\.ico|icon\\.svg|.*\\.(?:svg|png|jpg|jpeg|gif|webp|ico)$).*)"],
}
