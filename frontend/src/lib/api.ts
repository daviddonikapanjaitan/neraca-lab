// Server-side client of the Neraca Lab backend. Only imported by Server Components and Route
// Handlers, so the backend URL and the session token never reach the browser and the backend needs
// no CORS configuration. Every call sends the session token of the httpOnly cookie as
// `Authorization: Bearer <token>`; the backend checks login and permissions on every API.
import { cache } from "react"
import { cookies } from "next/headers"
import { redirect } from "next/navigation"

import { SESSION_COOKIE } from "@/lib/session-cookie"
import type {
  CompanyDetail,
  CompanyListResponse,
  Exchange,
  IngestionJobList,
  PermissionInfo,
  PriceQueue,
  Role,
  User,
} from "@/lib/types"

const API_URL = (process.env.NERACA_API_URL ?? "http://localhost:8080").replace(/\/+$/, "")

/** A failed backend call: HTTP status (0 = backend unreachable) and the ProblemDetail text. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly title: string,
    readonly detail: string
  ) {
    super(`${title}: ${detail}`)
    this.name = "ApiError"
  }
}

function unreachable(): ApiError {
  return new ApiError(0, "Backend unreachable", `Cannot reach the Neraca Lab API at ${API_URL}. Is the backend running?`)
}

/** The session token of the request (httpOnly cookie), if any. */
export async function sessionToken(): Promise<string | undefined> {
  return (await cookies()).get(SESSION_COOKIE)?.value
}

async function authHeaders(): Promise<Record<string, string>> {
  const token = await sessionToken()
  return token ? { Authorization: `Bearer ${token}` } : {}
}

async function request(path: string): Promise<Response> {
  try {
    return await fetch(`${API_URL}${path}`, {
      cache: "no-store",
      headers: { Accept: "application/json", ...(await authHeaders()) },
    })
  } catch {
    throw unreachable()
  }
}

async function problem(path: string, response: Response): Promise<ApiError> {
  // Spring answers errors with an RFC 9457 ProblemDetail body
  let title = response.statusText || "Request failed"
  let detail = `${path} returned HTTP ${response.status}`
  try {
    const body = (await response.json()) as { title?: string; detail?: string }
    title = body.title ?? title
    detail = body.detail ?? detail
  } catch {
    // not JSON: keep the defaults
  }
  return new ApiError(response.status, title, detail)
}

/** GET for Server Components. A 401 (no / expired session) sends the browser to the login page. */
async function get<T>(path: string): Promise<T> {
  const response = await request(path)
  if (response.status === 401) {
    redirect("/login?expired=1")
  }
  if (!response.ok) {
    throw await problem(path, response)
  }
  return (await response.json()) as T
}

/**
 * GET /api/v1/auth/me: the logged-in user, or null without a valid session (no cookie, expired,
 * deactivated). Cached per request, so the layout and the page share one call.
 */
export const getCurrentUser = cache(async (): Promise<User | null> => {
  if (!(await sessionToken())) return null
  const response = await request("/api/v1/auth/me")
  if (response.status === 401) return null
  if (!response.ok) throw await problem("/api/v1/auth/me", response)
  return (await response.json()) as User
})

/** The logged-in user; without a valid session the browser goes to the login page. */
export async function requireUser(): Promise<User> {
  const user = await getCurrentUser()
  if (!user) redirect("/login?expired=1")
  return user
}

/** GET /api/v1/exchanges */
export const getExchanges = cache(() => get<Exchange[]>("/api/v1/exchanges"))

/** GET /api/v1/companies?exchange= */
export const getCompanies = cache((exchange: string) =>
  get<CompanyListResponse>(`/api/v1/companies?exchange=${encodeURIComponent(exchange)}`)
)

/** GET /api/v1/companies/{exchange}/{ticker}; cached per request so metadata and page share one call. */
export const getCompanyDetail = cache((exchange: string, ticker: string) =>
  get<CompanyDetail>(
    `/api/v1/companies/${encodeURIComponent(exchange)}/${encodeURIComponent(ticker)}`
  )
)

/** GET /api/v1/ingestions?limit= (most recent jobs, without results) */
export const getIngestions = cache((limit: number) =>
  get<IngestionJobList>(`/api/v1/ingestions?limit=${limit}`)
)

/** GET /api/v1/prices/ingestions (the configured price provider and the queue length) */
export const getPriceQueue = cache(() => get<PriceQueue>("/api/v1/prices/ingestions"))

/** GET /api/v1/admin/users */
export const getUsers = cache(() => get<User[]>("/api/v1/admin/users"))

/** GET /api/v1/admin/roles */
export const getRoles = cache(() => get<Role[]>("/api/v1/admin/roles"))

/** GET /api/v1/admin/permissions */
export const getPermissions = cache(() => get<PermissionInfo[]>("/api/v1/admin/permissions"))

// ---------------------------------------------------------------- Route Handlers

function unreachableResponse(): Response {
  const error = unreachable()
  return Response.json({ title: error.title, detail: error.detail, status: 503 }, { status: 503 })
}

/** Statuses whose response must not have a body. */
function bodyless(status: number): boolean {
  return status === 204 || status === 205 || status === 304
}

/**
 * Route Handlers: sends a request to the backend with the session token and returns its answer as
 * is (status, body, Content-Type), so the browser sees the backend's JSON or ProblemDetail. An
 * unreachable backend becomes a 503 ProblemDetail.
 */
export async function forward(path: string, init?: RequestInit): Promise<Response> {
  let response: Response
  try {
    response = await fetch(`${API_URL}${path}`, {
      ...init,
      cache: "no-store",
      headers: { Accept: "application/json", ...(await authHeaders()), ...init?.headers },
    })
  } catch {
    return unreachableResponse()
  }
  if (bodyless(response.status)) {
    return new Response(null, { status: response.status })
  }
  return new Response(await response.arrayBuffer(), {
    status: response.status,
    headers: { "Content-Type": response.headers.get("Content-Type") ?? "application/json" },
  })
}

/** Route Handlers: forwards the browser's request (method, body, Content-Type) to a backend path. */
export async function forwardRequest(request: Request, path: string): Promise<Response> {
  const hasBody = request.method !== "GET" && request.method !== "HEAD"
  const contentType = request.headers.get("Content-Type")
  return forward(path, {
    method: request.method,
    body: hasBody ? await request.arrayBuffer() : undefined,
    headers: hasBody && contentType ? { "Content-Type": contentType } : undefined,
  })
}

/** Response headers of a file download passed on to the browser. */
const FILE_HEADERS = [
  "Content-Type",
  "Content-Length",
  "Content-Disposition",
  "ETag",
  "X-Checksum-SHA256",
  "X-Content-Type-Options",
]

/**
 * Route Handlers: streams a file download (uploaded workbook, avatar) from the backend to the
 * browser with its headers (name, type, size, checksum). Errors are passed on as ProblemDetail JSON;
 * an unreachable backend becomes a 503 ProblemDetail.
 */
export async function forwardFile(path: string): Promise<Response> {
  let response: Response
  try {
    response = await fetch(`${API_URL}${path}`, {
      cache: "no-store",
      headers: { Accept: "*/*", ...(await authHeaders()) },
    })
  } catch {
    return unreachableResponse()
  }
  if (!response.ok) {
    return new Response(await response.arrayBuffer(), {
      status: response.status,
      headers: { "Content-Type": response.headers.get("Content-Type") ?? "application/json" },
    })
  }
  const headers = new Headers({ "Cache-Control": "private, no-store" })
  for (const name of FILE_HEADERS) {
    const value = response.headers.get(name)
    if (value) headers.set(name, value)
  }
  return new Response(response.body, { status: response.status, headers })
}

/** Backend login (no session yet). */
export async function backendLogin(body: string): Promise<Response> {
  try {
    return await fetch(`${API_URL}/api/v1/auth/login`, {
      method: "POST",
      cache: "no-store",
      headers: { Accept: "application/json", "Content-Type": "application/json" },
      body,
    })
  } catch {
    return unreachableResponse()
  }
}
