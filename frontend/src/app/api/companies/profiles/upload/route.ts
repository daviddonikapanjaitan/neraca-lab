import { forward } from "@/lib/api"
import { MAX_UPLOAD_BYTES } from "@/lib/ingestion"

function problem(status: number, title: string, detail: string) {
  return Response.json({ title, detail, status }, { status })
}

/**
 * POST /api/companies/profiles/upload (multipart: file, exchange) -> backend POST
 * /api/v1/companies/profiles/upload?exchange=: the IDX company profiles a browser session collected on idx.co.id
 * (the collector script of the card), stored at once; the result in the response.
 */
export async function POST(request: Request) {
  let form: FormData
  try {
    form = await request.formData()
  } catch {
    return problem(400, "Invalid upload", "Expected a multipart form with a file field")
  }
  const file = form.get("file")
  if (!(file instanceof File)) {
    return problem(400, "Invalid upload", "No file selected")
  }
  if (!/\.json$/i.test(file.name)) {
    return problem(400, "Invalid request", "Only .json files are accepted")
  }
  if (file.size > MAX_UPLOAD_BYTES) {
    return problem(413, "File too large", "The JSON exceeds the limit of 50 MB")
  }
  const rawExchange = form.get("exchange")
  const exchange = typeof rawExchange === "string" && rawExchange.trim() ? rawExchange.trim() : "IDX"
  const body = new FormData()
  body.append("file", file, file.name)
  return forward(`/api/v1/companies/profiles/upload?exchange=${encodeURIComponent(exchange)}`, {
    method: "POST",
    body,
  })
}
