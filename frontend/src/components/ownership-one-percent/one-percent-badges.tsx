import { ArrowRightLeftIcon, LogInIcon, LogOutIcon, PencilLineIcon } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import {
  GROUP_COLOR,
  GROUP_LABEL,
  type MatchKind,
  MOVEMENT_LABEL_1,
  type OnePercentMovement,
  type TypeGroup,
} from "@/lib/ownership-one-percent"
import { cn } from "@/lib/utils"

/** Buy / Sell / No change / New above 1% / No longer above 1%; "First list" when no list before it is stored. */
export function OnePercentMovementBadge({ movement }: { movement: OnePercentMovement | null }) {
  switch (movement) {
    case "BUY":
      return <Badge className="bg-emerald-500/15 text-emerald-700 dark:text-emerald-300">{MOVEMENT_LABEL_1.BUY}</Badge>
    case "SELL":
      return <Badge className="bg-rose-500/15 text-rose-700 dark:text-rose-300">{MOVEMENT_LABEL_1.SELL}</Badge>
    case "ENTERED":
      return (
        <Badge className="bg-sky-500/15 text-sky-700 dark:text-sky-300">
          <LogInIcon />
          {MOVEMENT_LABEL_1.ENTERED}
        </Badge>
      )
    case "EXITED":
      return (
        <Badge className="bg-amber-500/15 text-amber-700 dark:text-amber-300">
          <LogOutIcon />
          {MOVEMENT_LABEL_1.EXITED}
        </Badge>
      )
    case "UNCHANGED":
      return <Badge variant="outline">{MOVEMENT_LABEL_1.UNCHANGED}</Badge>
    default:
      return (
        <Badge variant="outline" className="text-muted-foreground" title="No list before this one is stored">
          First list
        </Badge>
      )
  }
}

/**
 * RENAMED: the same investor under a name spelled otherwise; MOVED: the same block of shares under another name (a
 * renaming or a transfer). Nothing for the same name.
 */
export function MatchBadge({ kind, previousName }: { kind: MatchKind | null; previousName: string | null }) {
  if (kind === "RENAMED") {
    return (
      <Badge variant="outline" title={`Listed as ${previousName ?? "another name"} the month before`}>
        <PencilLineIcon />
        Renamed
      </Badge>
    )
  }
  if (kind === "MOVED") {
    return (
      <Badge
        variant="outline"
        className="border-violet-500/40 text-violet-700 dark:text-violet-300"
        title={`The same shares were listed under ${previousName ?? "another name"} the month before: a new name or a transfer`}
      >
        <ArrowRightLeftIcon />
        Block moved
      </Badge>
    )
  }
  return null
}

/** The investor type (Individual, Mutual Funds, ...) with its group's colour. */
export function TypeBadge({ type, group }: { type: string | null; group: TypeGroup | null }) {
  if (!type && !group) return null
  return (
    <Badge variant="outline" className="gap-1" title={group ? GROUP_LABEL[group] : undefined}>
      <span className={cn("size-2 rounded-full", GROUP_COLOR[group ?? "UNKNOWN"])} />
      {type ?? GROUP_LABEL[group ?? "UNKNOWN"]}
    </Badge>
  )
}
