import { ArrowDown, ArrowUp, ArrowUpDown } from "lucide-react"

export type SortDir = "asc" | "desc"

/** Column sort indicator (shadcn-fintech holdings table style). */
export function SortIcon({ active, dir }: { active: boolean; dir: SortDir }) {
  if (!active) return <ArrowUpDown className="ml-1 inline size-3 text-muted-foreground/50" />
  return dir === "asc" ? <ArrowUp className="ml-1 inline size-3" /> : <ArrowDown className="ml-1 inline size-3" />
}
