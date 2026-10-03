// Server-side client of the Neraca Lab backend. Only imported by Server Components and Route
// Handlers, so the backend URL never reaches the browser and the backend needs no CORS configuration.
import { cache } from "react"

import type { CompanyDetail, CompanyListResponse, Exchange, IngestionJobList, PriceQueue } from "@/lib/types"

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

async function get<T>(path: string): Promise<T> {
  let response: Response
  try {
    response = await fetch(`${API_URL}${path}`, {
      cache: "no-store",
      headers: { Accept: "application/json" },
    })
  } catch {
    throw unreachable()
  }
  if (!response.ok) {
    // Spring answers errors with an RFC 9457 ProblemDetail body
    let title = response.statusText || "Request failed"
    let detail = `${path} returned HTTP ${response.status}`
    try {
      const problem = (await response.json()) as { title?: string; detail?: string }
      title = problem.title ?? title
      detail = problem.detail ?? detail
    } catch {
      // not JSON: keep the defaults
    }
    throw new ApiError(response.status, title, detail)
  }
  return (await response.json()) as T
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

/**
 * Route Handlers: sends a request to the backend and returns its answer as is (status, body,
 * Content-Type), so the browser sees the backend's JSON or ProblemDetail. An unreachable backend
 * becomes a 503 ProblemDetail.
 */
export async function forward(path: string, init?: RequestInit): Promise<Response> {
  let response: Response
  try {
    response = await fetch(`${API_URL}${path}`, {
      ...init,
      cache: "no-store",
      headers: { Accept: "application/json", ...init?.headers },
    })
  } catch {
    const error = unreachable()
    return Response.json({ title: error.title, detail: error.detail, status: 503 }, { status: 503 })
  }
  return new Response(await response.arrayBuffer(), {
    status: response.status,
    headers: { "Content-Type": response.headers.get("Content-Type") ?? "application/json" },
  })
}

/** Response headers of a file download passed on to the browser. */
const FILE_HEADERS = ["Content-Type", "Content-Length", "Content-Disposition", "ETag", "X-Checksum-SHA256"]

/**
 * Route Handlers: streams a file download from the backend to the browser with its headers
 * (name, type, size, checksum). Errors are passed on as ProblemDetail JSON; an unreachable backend
 * becomes a 503 ProblemDetail.
 */
export async function forwardFile(path: string): Promise<Response> {
  let response: Response
  try {
    response = await fetch(`${API_URL}${path}`, { cache: "no-store", headers: { Accept: "*/*" } })
  } catch {
    const error = unreachable()
    return Response.json({ title: error.title, detail: error.detail, status: 503 }, { status: 503 })
  }
  if (!response.ok) {
    return new Response(await response.arrayBuffer(), {
      status: response.status,
      headers: { "Content-Type": response.headers.get("Content-Type") ?? "application/json" },
    })
  }
  const headers = new Headers({ "Cache-Control": "no-store" })
  for (const name of FILE_HEADERS) {
    const value = response.headers.get(name)
    if (value) headers.set(name, value)
  }
  return new Response(response.body, { status: response.status, headers })
}
