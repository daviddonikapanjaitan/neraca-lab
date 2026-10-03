import { LoaderIcon } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { STATUS_META } from "@/lib/ingestion"
import type { IngestionJobStatus } from "@/lib/types"
import { cn } from "@/lib/utils"

export function JobStatusBadge({ status }: { status: IngestionJobStatus }) {
  const meta = STATUS_META[status]
  return (
    <Badge variant="outline" className={cn("border-transparent", meta.className)}>
      {status === "RUNNING" && <LoaderIcon className="animate-spin" />}
      {meta.label}
    </Badge>
  )
}
