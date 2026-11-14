import type { NextRequest } from "next/server"

import { forwardRequest } from "@/lib/api"
import { performanceQuery } from "@/lib/portfolio-performance"

type Params = { params: Promise<{ year: string }> }

function path(year: string, range: string | null): string | null {
  return /^\d{4}$/.test(year) ? `/api/v1/portfolio/performance/years/${year}${performanceQuery(range)}` : null
}

function badYear() {
  return Response.json(
    { title: "Bad Request", status: 400, detail: "year: four digits, e.g. 2025" },
    { status: 400, headers: { "Content-Type": "application/problem+json" } }
  )
}

/** PUT /api/portfolio/performance/years/{year}?range= -> backend PUT /api/v1/portfolio/performance/years/{year}. */
export async function PUT(request: NextRequest, { params }: Params) {
  const target = path((await params).year, request.nextUrl.searchParams.get("range"))
  return target ? forwardRequest(request, target) : badYear()
}

/** DELETE /api/portfolio/performance/years/{year}?range= -> backend DELETE /api/v1/portfolio/performance/years/{year}. */
export async function DELETE(request: NextRequest, { params }: Params) {
  const target = path((await params).year, request.nextUrl.searchParams.get("range"))
  return target ? forwardRequest(request, target) : badYear()
}
