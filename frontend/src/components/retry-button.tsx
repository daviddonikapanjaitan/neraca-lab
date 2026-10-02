"use client"

import { useTransition } from "react"
import { useRouter } from "next/navigation"
import { RefreshCwIcon } from "lucide-react"

import { Button } from "@/components/ui/button"
import { cn } from "@/lib/utils"

/** Re-renders the current route on the server (re-runs its backend calls). */
export function RetryButton() {
  const router = useRouter()
  const [pending, startTransition] = useTransition()

  return (
    <Button size="sm" disabled={pending} onClick={() => startTransition(() => router.refresh())}>
      <RefreshCwIcon className={cn(pending && "animate-spin")} />
      {pending ? "Retrying..." : "Try again"}
    </Button>
  )
}
