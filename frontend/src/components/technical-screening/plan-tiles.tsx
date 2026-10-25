import { buyLabel, formatIdr, formatSignedPct, type PlanLevel, type TradePlan } from "@/lib/technical-screening"
import { cn } from "@/lib/utils"

/** Buy, sell (target) and stop loss of a trade plan, as the preset summaries of Companies › Technicals show them. */
export function PlanTiles({ plan, compact = false }: { plan: TradePlan | null; compact?: boolean }) {
  if (!plan || !plan.buy) {
    return (
      <p className="rounded-lg border border-dashed px-3 py-2 text-xs text-muted-foreground">
        No trade plan (too little history for prices).
      </p>
    )
  }
  return (
    <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
      <PriceTile label={buyLabel(plan.buy)} level={plan.buy} tone="buy" compact={compact} />
      <PriceTile label="Sell (target)" level={plan.sell} tone="sell" compact={compact} />
      <PriceTile label="Stop loss" level={plan.stopLoss} tone="stop" compact={compact} />
    </div>
  )
}

function PriceTile({
  label,
  level,
  tone,
  compact,
}: {
  label: string
  level: PlanLevel | null
  tone: "buy" | "sell" | "stop"
  compact: boolean
}) {
  return (
    <div className="flex min-w-0 flex-col gap-0.5 rounded-lg border bg-muted/30 px-3 py-2" title={level?.basis ?? undefined}>
      <span className="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">{label}</span>
      <span
        className={cn(
          "font-mono text-base font-semibold tabular-nums",
          tone === "buy" && "text-emerald-700 dark:text-emerald-400",
          tone === "stop" && "text-rose-700 dark:text-rose-400"
        )}
      >
        {level ? formatIdr(level.price) : "—"}
      </span>
      {level && (
        <>
          <span className="font-mono text-xs text-muted-foreground tabular-nums">
            {level.distancePct === null ? "" : `${formatSignedPct(level.distancePct)} vs close`}
          </span>
          {!compact && level.basis && <span className="line-clamp-3 text-xs text-muted-foreground">{level.basis}</span>}
        </>
      )}
    </div>
  )
}
