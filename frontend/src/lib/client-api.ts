// Client-side calls of the Next.js route handlers (which forward to the backend with the session).

/** A failed call: HTTP status and the ProblemDetail text (field errors of a 400 in `errors`). */
export class RequestError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly errors: Record<string, string> = {}
  ) {
    super(message)
    this.name = "RequestError"
  }
}

/** The session ended (logged out elsewhere, expired, user deactivated): back to the login page. */
export function redirectToLogin() {
  const next = window.location.pathname + window.location.search
  // a full page load on purpose: the session ended, so no client state of the old session survives
  // eslint-disable-next-line @next/next/no-location-assign-relative-destination
  window.location.assign(`/login?expired=1&next=${encodeURIComponent(next)}`)
}

/** Reads a ProblemDetail error body; a 401 sends the browser to the login page. */
export async function problemOf(response: Response): Promise<RequestError> {
  if (response.status === 401) redirectToLogin()
  let detail = `Request failed with HTTP ${response.status}`
  let errors: Record<string, string> = {}
  try {
    const problem = (await response.json()) as { title?: string; detail?: string; errors?: Record<string, string> }
    detail = problem.detail ?? problem.title ?? detail
    errors = problem.errors ?? {}
  } catch {
    // not JSON: keep the default
  }
  return new RequestError(response.status, detail, errors)
}

/**
 * Calls a route handler. Resolves with the JSON body (null for 204) and the HTTP status; rejects
 * with a RequestError carrying the ProblemDetail text.
 */
export async function requestJson<T>(url: string, init?: RequestInit): Promise<{ status: number; body: T }> {
  let response: Response
  try {
    response = await fetch(url, { cache: "no-store", ...init })
  } catch {
    throw new RequestError(0, "Cannot reach the Neraca Lab server. Check your connection and try again.")
  }
  if (!response.ok) throw await problemOf(response)
  const body = response.status === 204 ? null : await response.json()
  return { status: response.status, body: body as T }
}

/** JSON request body helper. */
export function jsonBody(method: string, body: unknown): RequestInit {
  return { method, headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) }
}
