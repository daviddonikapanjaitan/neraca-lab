import { forward } from "@/lib/api"
import { MAX_UPLOAD_BYTES } from "@/lib/ingestion"

function problem(status: number, title: string, detail: string) {
  return Response.json({ title, detail, status }, { status })
}

/** Form fields passed on to the backend besides the file. */
const FIELDS = ["country", "documentType", "publishedOn", "title", "issuer"]

/**
 * POST /api/rag/macro/pdf (multipart: file, country, documentType, publishedOn, optional title and issuer) -> backend
 * POST /api/v1/rag/macro/pdf. The backend checks the country (Indonesia only for now), the type, the publication date
 * and that the PDF has text, stores it and queues its RAG ingestion for the country; it answers 202 with the job (200
 * when the same file is already queued for that country).
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
  if (!file.name.toLowerCase().endsWith(".pdf")) {
    return problem(400, "Invalid request", "Only .pdf files are accepted")
  }
  if (file.size > MAX_UPLOAD_BYTES) {
    return problem(413, "File too large", "The file exceeds the upload limit of 50 MB")
  }
  const body = new FormData()
  body.append("file", file, file.name)
  for (const name of FIELDS) {
    const value = form.get(name)
    if (typeof value === "string" && value.trim()) body.append(name, value.trim())
  }
  return forward("/api/v1/rag/macro/pdf", { method: "POST", body })
}
