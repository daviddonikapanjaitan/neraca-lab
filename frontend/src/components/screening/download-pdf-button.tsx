"use client"

import { useState } from "react"
import { FileDownIcon, LoaderIcon } from "lucide-react"

import { Button } from "@/components/ui/button"
import { problemOf } from "@/lib/client-api"

/** Name from the Content-Disposition header (filename*=UTF-8''... or filename="..."). */
function fileNameOf(header: string | null, fallback: string): string {
  if (!header) return fallback
  const utf8 = /filename\*=UTF-8''([^;]+)/i.exec(header)
  if (utf8) {
    try {
      return decodeURIComponent(utf8[1])
    } catch {
      // fall through
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(header)
  return plain ? plain[1] : fallback
}

/**
 * Downloads the PDF of a finished screening or analysis (e.g. GET /api/screenings/{id}/pdf). Fetched first, so a
 * failure shows a message instead of a broken download.
 */
export function DownloadPdfButton({
  href,
  fileName,
  disabled = false,
}: {
  /** the route handler serving the PDF */
  href: string
  /** download name when the response has no Content-Disposition */
  fileName: string
  disabled?: boolean
}) {
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function download() {
    if (pending) return
    setPending(true)
    setError(null)
    try {
      let response: Response
      try {
        response = await fetch(href, { cache: "no-store" })
      } catch {
        throw new Error("Cannot reach the Neraca Lab server. Check your connection and try again.")
      }
      if (!response.ok) throw await problemOf(response)
      const name = fileNameOf(response.headers.get("Content-Disposition"), fileName)
      const url = URL.createObjectURL(await response.blob())
      try {
        const link = document.createElement("a")
        link.href = url
        link.download = name
        document.body.appendChild(link)
        link.click()
        link.remove()
      } finally {
        setTimeout(() => URL.revokeObjectURL(url), 10_000)
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setPending(false)
    }
  }

  return (
    <div className="flex flex-col items-end gap-1">
      <Button variant="outline" size="sm" disabled={disabled || pending} onClick={download}>
        {pending ? <LoaderIcon className="animate-spin" /> : <FileDownIcon />}
        {pending ? "Preparing PDF..." : "Download PDF"}
      </Button>
      {error && (
        <p role="alert" className="max-w-xs text-right text-xs text-destructive">
          {error}
        </p>
      )}
    </div>
  )
}
