"use client" // Error boundaries must be Client Components

import { useEffect } from "react"
import { TriangleAlertIcon } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"

/** Unexpected errors (backend errors are rendered by the pages themselves). */
export default function DashboardError({
  error,
  retry,
}: {
  error: Error & { digest?: string }
  retry: () => void
}) {
  useEffect(() => {
    console.error(error)
  }, [error])

  return (
    <div className="flex flex-1 flex-col items-center justify-center p-4 pt-0">
      <Card className="mx-auto w-full max-w-xl">
        <CardContent className="flex flex-col items-center gap-4 py-10 text-center">
          <div className="flex size-12 items-center justify-center rounded-2xl bg-destructive/10 text-destructive">
            <TriangleAlertIcon className="size-6" />
          </div>
          <div className="space-y-1.5">
            <h2 className="text-base font-semibold">Something went wrong</h2>
            <p className="text-sm text-muted-foreground">
              The page could not be displayed.{error.digest && ` Reference: ${error.digest}`}
            </p>
          </div>
          <Button size="sm" onClick={() => retry()}>
            Try again
          </Button>
        </CardContent>
      </Card>
    </div>
  )
}
