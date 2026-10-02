import type { LucideIcon } from "lucide-react"

import { cn } from "@/lib/utils"

const TONES = {
  positive: { color: "text-emerald-500", bg: "bg-emerald-500/10" },
  negative: { color: "text-rose-500", bg: "bg-rose-500/10" },
  primary: { color: "text-primary", bg: "bg-primary/10" },
  muted: { color: "text-muted-foreground", bg: "bg-muted" },
} as const

/** Summary tile in the style of the shadcn-fintech transaction summary. */
export function StatTile({
  label,
  value,
  hint,
  icon: Icon,
  tone = "primary",
}: {
  label: string
  value: React.ReactNode
  hint?: React.ReactNode
  icon: LucideIcon
  tone?: keyof typeof TONES
}) {
  return (
    <div className="flex items-center gap-3 rounded-xl bg-card p-3 ring-1 ring-foreground/10">
      <div className={cn("flex size-9 shrink-0 items-center justify-center rounded-full", TONES[tone].bg)}>
        <Icon className={cn("size-4", TONES[tone].color)} />
      </div>
      <div className="min-w-0">
        <p className="text-xs text-muted-foreground">{label}</p>
        <p className="truncate text-base font-semibold tracking-tight tabular-nums">{value}</p>
        {hint && <p className="truncate text-[11px] text-muted-foreground">{hint}</p>}
      </div>
    </div>
  )
}
