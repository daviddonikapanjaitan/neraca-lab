// Path parameter checks of the route handlers.

/** A numeric database id (users, roles). */
export function isNumericId(id: string): boolean {
  return /^\d{1,18}$/.test(id)
}

export function invalidId(id: string): Response {
  return Response.json({ title: "Invalid id", detail: `'${id}' is not a valid id`, status: 400 }, { status: 400 })
}
