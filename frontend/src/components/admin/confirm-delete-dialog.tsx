"use client"

import { useState } from "react"
import { useRouter } from "next/navigation"
import { CircleAlertIcon, LoaderIcon, Trash2Icon } from "lucide-react"

import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { Button } from "@/components/ui/button"
import { requestJson } from "@/lib/client-api"

/** Confirms a delete, sends DELETE to `url` and reloads the page data; a refusal is shown in the dialog. */
export function ConfirmDeleteDialog({
  open,
  title,
  description,
  url,
  onClose,
}: {
  open: boolean
  title: string
  description: string
  url: string | null
  onClose: () => void
}) {
  const router = useRouter()
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function confirm() {
    if (!url || pending) return
    setPending(true)
    setError(null)
    try {
      await requestJson(url, { method: "DELETE" })
      router.refresh()
      onClose()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setPending(false)
    }
  }

  return (
    <AlertDialog
      open={open}
      onOpenChange={(next) => {
        if (!next && !pending) {
          setError(null)
          onClose()
        }
      }}
    >
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{title}</AlertDialogTitle>
          <AlertDialogDescription>{description}</AlertDialogDescription>
        </AlertDialogHeader>
        {error && (
          <p role="alert" className="flex items-start gap-2 text-xs text-destructive">
            <CircleAlertIcon className="mt-px size-3.5 shrink-0" />
            <span>{error}</span>
          </p>
        )}
        <AlertDialogFooter>
          <AlertDialogClose render={<Button variant="outline" disabled={pending} />}>Cancel</AlertDialogClose>
          <Button variant="destructive" onClick={confirm} disabled={pending}>
            {pending ? <LoaderIcon className="animate-spin" /> : <Trash2Icon />}
            {pending ? "Deleting..." : "Delete"}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
