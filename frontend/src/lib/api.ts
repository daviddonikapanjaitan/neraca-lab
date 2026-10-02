// Server-side client of the Neraca Lab backend. Only imported by Server Components, so the
// backend URL never reaches the browser and the backend needs no CORS configuration.
import { cache } from "react"

import type { CompanyDetail, CompanyListResponse, Exchange } from "@/lib/types"

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

async function get<T>(path: string): Promise<T> {
  let response: Response
  try {
    response = await fetch(`${API_URL}${path}`, {
      cache: "no-store",
      headers: { Accept: "application/json" },
    })
  } catch {
    throw new ApiError(0, "Backend unreachable", `Cannot reach the Neraca Lab API at ${API_URL}. Is the backend running?`)
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
