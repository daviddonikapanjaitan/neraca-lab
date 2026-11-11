import { forwardRequest } from "@/lib/api"

type Params = { params: Promise<{ id: string }> }

function path(id: string): string | null {
  return /^\d{1,18}$/.test(id) ? `/api/v1/portfolio/positions/${id}` : null
}

function notFound() {
  return Response.json(
    { title: "Position not found", status: 404, detail: "No such position in your portfolio" },
    { status: 404, headers: { "Content-Type": "application/problem+json" } }
  )
}

/** PUT /api/portfolio/positions/{id} {averagePrice, shares, dateIn} -> backend PUT /api/v1/portfolio/positions/{id}. */
export async function PUT(request: Request, { params }: Params) {
  const target = path((await params).id)
  return target ? forwardRequest(request, target) : notFound()
}

/** DELETE /api/portfolio/positions/{id} -> backend DELETE /api/v1/portfolio/positions/{id}. */
export async function DELETE(request: Request, { params }: Params) {
  const target = path((await params).id)
  return target ? forwardRequest(request, target) : notFound()
}
