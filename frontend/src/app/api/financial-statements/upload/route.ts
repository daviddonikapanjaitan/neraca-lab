import { forward } from "@/lib/api"
import { MAX_UPLOAD_BYTES } from "@/lib/ingestion"

function problem(status: number, title: string, detail: string) {
  return Response.json({ title, detail, status }, { status })
}

/**
 * POST /api/financial-statements/upload (multipart, field "file") -> backend
 * POST /api/v1/financial-statements/upload. The backend checks the workbook, stores it and
 * queues the AI ingestion; it answers 202 with the job (200 when the same file is already queued).
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
  if (!file.name.toLowerCase().endsWith(".xlsx")) {
    return problem(
      422,
      "Invalid financial statement workbook",
      "Only .xlsx files are accepted (IDX XBRL FinancialStatement-<period>-<TICKER>.xlsx)"
    )
  }
  if (file.size > MAX_UPLOAD_BYTES) {
    return problem(413, "File too large", "The file exceeds the upload limit of 20 MB")
  }
  const body = new FormData()
  body.append("file", file, file.name)
  return forward("/api/v1/financial-statements/upload", { method: "POST", body })
}
