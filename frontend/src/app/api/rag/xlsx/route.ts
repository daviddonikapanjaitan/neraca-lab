import { forward } from "@/lib/api"
import { MAX_UPLOAD_BYTES } from "@/lib/ingestion"

function problem(status: number, title: string, detail: string) {
  return Response.json({ title, detail, status }, { status })
}

/**
 * POST /api/rag/xlsx (multipart: file, exchange, documentType) -> backend POST /api/v1/rag/xlsx. The backend reads the
 * workbook at once (422 when it is not the document type's), stores it and queues it; it answers 202 with the job
 * (200 when the same file is already queued).
 */
export async function POST(request: Request) {
  let form: FormData
  try {
    form = await request.formData()
  } catch {
    return problem(400, "Invalid upload", "Expected a multipart form with a file field")
  }
  const file = form.get("file")
  const exchange = form.get("exchange")
  const documentType = form.get("documentType")
  if (!(file instanceof File)) {
    return problem(400, "Invalid upload", "No file selected")
  }
  if (typeof exchange !== "string" || !exchange.trim()) {
    return problem(400, "Invalid upload", "Choose the exchange of the document")
  }
  if (typeof documentType !== "string" || !documentType.trim()) {
    return problem(400, "Invalid upload", "Choose the type of the document")
  }
  if (!file.name.toLowerCase().endsWith(".xlsx")) {
    return problem(400, "Invalid request", "Only .xlsx workbooks are accepted")
  }
  if (file.size > MAX_UPLOAD_BYTES) {
    return problem(413, "File too large", "The file exceeds the upload limit of 50 MB")
  }
  const body = new FormData()
  body.append("file", file, file.name)
  body.append("exchange", exchange.trim())
  body.append("documentType", documentType.trim())
  return forward("/api/v1/rag/xlsx", { method: "POST", body })
}
