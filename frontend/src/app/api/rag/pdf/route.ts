import { forward } from "@/lib/api"
import { MAX_UPLOAD_BYTES } from "@/lib/ingestion"

function problem(status: number, title: string, detail: string) {
  return Response.json({ title, detail, status }, { status })
}

/**
 * POST /api/rag/pdf (multipart: file, exchange, ticker) -> backend POST /api/v1/rag/pdf. The backend checks that
 * the PDF has text, stores it and queues its RAG ingestion for the company; it answers 202 with the job (200 when
 * the same file is already queued for that company).
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
  const ticker = form.get("ticker")
  if (!(file instanceof File)) {
    return problem(400, "Invalid upload", "No file selected")
  }
  if (typeof exchange !== "string" || !exchange.trim() || typeof ticker !== "string" || !ticker.trim()) {
    return problem(400, "Invalid upload", "Choose the company of the document")
  }
  if (!file.name.toLowerCase().endsWith(".pdf")) {
    return problem(400, "Invalid request", "Only .pdf files are accepted")
  }
  if (file.size > MAX_UPLOAD_BYTES) {
    return problem(413, "File too large", "The file exceeds the upload limit of 20 MB")
  }
  const body = new FormData()
  body.append("file", file, file.name)
  body.append("exchange", exchange.trim())
  body.append("ticker", ticker.trim())
  return forward("/api/v1/rag/pdf", { method: "POST", body })
}
