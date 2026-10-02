import Link from "next/link"
import { ServerCrashIcon, TriangleAlertIcon } from "lucide-react"

import { RetryButton } from "@/components/retry-button"
import { buttonVariants } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import type { ApiError } from "@/lib/api"

/**
 * A failed backend call, rendered by Server Components (so the message stays visible in
 * production, unlike errors thrown to an error boundary).
 */
export function ApiErrorState({
  error,
  backHref,
  backLabel = "Back to companies",
}: {
  error: ApiError
  backHref?: string
  backLabel?: string
}) {
  const unreachable = error.status === 0
  const Icon = unreachable ? ServerCrashIcon : TriangleAlertIcon

  return (
    <Card className="mx-auto w-full max-w-xl">
      <CardContent className="flex flex-col items-center gap-4 py-10 text-center">
        <div className="flex size-12 items-center justify-center rounded-2xl bg-destructive/10 text-destructive">
          <Icon className="size-6" />
        </div>
        <div className="space-y-1.5">
          <h2 className="text-base font-semibold">{error.title}</h2>
          <p className="text-sm text-muted-foreground">{error.detail}</p>
          {!unreachable && (
            <p className="text-xs text-muted-foreground/70 tabular-nums">HTTP {error.status}</p>
          )}
        </div>
        <div className="flex flex-wrap justify-center gap-2">
          <RetryButton />
          {backHref && (
            <Link href={backHref} className={buttonVariants({ variant: "outline", size: "sm" })}>
              {backLabel}
            </Link>
          )}
        </div>
      </CardContent>
    </Card>
  )
}
