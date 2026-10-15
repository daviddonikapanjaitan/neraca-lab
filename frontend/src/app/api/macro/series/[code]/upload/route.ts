import { forward } from "@/lib/api"

/** Largest CSV the backend accepts (MacroService.MAX_UPLOAD_BYTES). */
const MAX_CSV_BYTES = 2_000_000

function problem(status: number, title: string, detail: string) {
  return Response.json({ title, detail, status }, { status })
}

/**
 * POST /api/macro/series/{code}/upload (multipart: file) -> backend POST /api/v1/macro/series/{code}/upload: a CSV of
 * obs_date,value lines, checked like a fetch (range, dates) and stored.
 */
export async function POST(request: Request, { params }: { params: Promise<{ code: string }> }) {
  const { code } = await params
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
  if (!/\.(csv|txt)$/i.test(file.name)) {
    return problem(400, "Invalid request", "Only .csv files are accepted")
  }
  if (file.size > MAX_CSV_BYTES) {
    return problem(413, "File too large", "The CSV exceeds the limit of 2 MB")
  }
  const body = new FormData()
  body.append("file", file, file.name)
  return forward(`/api/v1/macro/series/${encodeURIComponent(code)}/upload`, { method: "POST", body })
}
