"use client"

import { useState } from "react"
import { CircleAlertIcon, DownloadIcon, LoaderIcon } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"

/**
 * Downloads the workbook of an upload job (GET /api/ingestions/{id}/file) and saves it under the
 * name of that upload. Fetched first, so a failure shows a message instead of a broken download.
 * {@code compact}: icon button for the jobs table, with the error in its tooltip.
 */
export function DownloadFileButton({
  jobId,
  fileName,
  compact = false,
}: {
  jobId: string
  fileName: string
  compact?: boolean
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
        response = await fetch(`/api/ingestions/${encodeURIComponent(jobId)}/file`, { cache: "no-store" })
      } catch {
        throw new Error("Cannot reach the Neraca Lab server. Check your connection and try again.")
      }
      if (!response.ok) {
        let detail = `Download failed with HTTP ${response.status}`
        try {
          const problem = (await response.json()) as { title?: string; detail?: string }
          detail = problem.detail ?? problem.title ?? detail
        } catch {
          // not JSON: keep the default
        }
        throw new Error(detail)
      }
      const url = URL.createObjectURL(await response.blob())
      try {
        const link = document.createElement("a")
        link.href = url
        link.download = fileName
        document.body.appendChild(link)
        link.click()
        link.remove()
      } finally {
        // give the browser time to start the download before the blob is released
        setTimeout(() => URL.revokeObjectURL(url), 10_000)
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setPending(false)
    }
  }

  const Icon = pending ? LoaderIcon : error ? CircleAlertIcon : DownloadIcon
  const iconClass = pending ? "animate-spin" : error ? "text-destructive" : undefined

  if (compact) {
    const label = error ? `Download failed: ${error}` : `Download ${fileName}`
    return (
      <Tooltip>
        <TooltipTrigger
          render={
            <Button
              variant="ghost"
              size="icon-sm"
              aria-label={label}
              disabled={pending}
              onClick={(e) => {
                e.stopPropagation()
                download()
              }}
            />
          }
        >
          <Icon className={iconClass} />
        </TooltipTrigger>
        <TooltipContent>{label}</TooltipContent>
      </Tooltip>
    )
  }

  return (
    <div className="flex flex-col gap-1.5">
      <Button variant="outline" size="sm" className="self-start" disabled={pending} onClick={download}>
        <Icon className={iconClass} />
        {pending ? "Downloading..." : "Download file"}
      </Button>
      {error && (
        <p role="alert" className="text-xs text-destructive">
          {error}
        </p>
      )}
    </div>
  )
}
