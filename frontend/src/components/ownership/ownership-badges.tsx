import { ArrowDownRightIcon, ArrowUpRightIcon, LogInIcon, LogOutIcon } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { EMPTY, formatNumber } from "@/lib/format"
import { type InvestorStatus, MOVEMENT_LABEL, type OwnershipMovement, pointsLabel, STATUS_LABEL } from "@/lib/ownership"
import { cn } from "@/lib/utils"

/** Buy / Sell / No change / Crossed 5% / Below 5%, coloured as on Companies > Registration. */
export function MovementBadge({ movement }: { movement: OwnershipMovement }) {
  switch (movement) {
    case "BUY":
      return <Badge className="bg-emerald-500/15 text-emerald-700 dark:text-emerald-300">{MOVEMENT_LABEL.BUY}</Badge>
    case "SELL":
      return <Badge className="bg-rose-500/15 text-rose-700 dark:text-rose-300">{MOVEMENT_LABEL.SELL}</Badge>
    case "ENTERED":
      return (
        <Badge className="bg-sky-500/15 text-sky-700 dark:text-sky-300">
          <LogInIcon />
          {MOVEMENT_LABEL.ENTERED}
        </Badge>
      )
    case "EXITED":
      return (
        <Badge className="bg-amber-500/15 text-amber-700 dark:text-amber-300">
          <LogOutIcon />
          {MOVEMENT_LABEL.EXITED}
        </Badge>
      )
    default:
      return <Badge variant="outline">{MOVEMENT_LABEL.UNCHANGED}</Badge>
  }
}

/** Local / Foreign */
export function StatusBadge({ status }: { status: InvestorStatus | "LOCAL" | "FOREIGN" | null }) {
  if (!status) return null
  const foreign = status === "A" || status === "FOREIGN"
  return (
    <Badge variant="outline" className={cn(foreign && "border-violet-500/40 text-violet-700 dark:text-violet-300")}>
      {foreign ? STATUS_LABEL.A : STATUS_LABEL.L}
    </Badge>
  )
}

/** "+2,500,000" / "-1,000,000" / "0", coloured: bought green, sold red. */
export function SharesChange({ value }: { value: number | null }) {
  if (value === null) return <span className="text-muted-foreground">{EMPTY}</span>
  if (value === 0) return <span className="text-muted-foreground">0</span>
  const bought = value > 0
  return (
    <span
      className={cn(
        "inline-flex items-center gap-0.5 font-medium",
        bought ? "text-emerald-600 dark:text-emerald-400" : "text-rose-600 dark:text-rose-400"
      )}
    >
      {bought ? <ArrowUpRightIcon className="size-3.5" /> : <ArrowDownRightIcon className="size-3.5" />}
      {bought ? "+" : ""}
      {formatNumber(value, 0)}
    </span>
  )
}

/** "+0.25 pp", coloured. */
export function PointsChange({ value }: { value: number | null }) {
  if (value === null || value === 0) return <span className="text-muted-foreground">{pointsLabel(value)}</span>
  return (
    <span className={value > 0 ? "text-emerald-600 dark:text-emerald-400" : "text-rose-600 dark:text-rose-400"}>
      {pointsLabel(value)}
    </span>
  )
}
