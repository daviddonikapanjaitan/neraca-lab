"use client"

import { Checkbox } from "@/components/ui/checkbox"
import { INDICATOR_GROUPS, INDICATORS, NOT_FOR_INDEX, NOT_FOR_MONTHLY, NOT_FOR_OTHER_INDEX, type IndicatorId } from "@/lib/technical"

const LABELS = new Map(INDICATORS.map((i) => [i.id, i.label]))

/** Indicator checkboxes in groups; used in the settings column (desktop) and the settings sheet (phone). */
export function IndicatorPicker({
  selected,
  onToggle,
  levelsDisabled,
  index = false,
  comparable = false,
  monthly = false,
}: {
  selected: IndicatorId[]
  onToggle: (id: IndicatorId, on: boolean) => void
  /** levels are tradable prices: not drawn on the total-return basis */
  levelsDisabled: boolean
  /** the chart shows the index (IHSG), a currency pair or a commodity: no comparison with IHSG, no events, no auto-rejection */
  index?: boolean
  /** an index compared with IHSG (LQ45): relative strength and the rebased line stay available */
  comparable?: boolean
  /** a monthly series (coal): no levels or volume profile either */
  monthly?: boolean
}) {
  const unavailable = monthly ? NOT_FOR_MONTHLY : index ? (comparable ? NOT_FOR_OTHER_INDEX : NOT_FOR_INDEX) : []
  return (
    <div className="space-y-4">
      {INDICATOR_GROUPS.map((group) => (
        <fieldset key={group.title} className="space-y-1.5">
          <legend className="mb-1 text-xs font-medium uppercase tracking-wide text-muted-foreground">{group.title}</legend>
          {group.ids.map((id) => {
            const disabled = (levelsDisabled && group.title === "Levels") || unavailable.includes(id)
            const inputId = `indicator-${id}`
            return (
              <label key={id} htmlFor={inputId} className="flex cursor-pointer items-center gap-2 text-sm has-disabled:cursor-not-allowed has-disabled:opacity-50">
                <Checkbox
                  id={inputId}
                  checked={selected.includes(id)}
                  disabled={disabled}
                  onCheckedChange={(checked) => onToggle(id, checked === true)}
                />
                {LABELS.get(id)}
              </label>
            )
          })}
          {levelsDisabled && group.title === "Levels" && (
            <p className="text-xs text-muted-foreground">Levels are tradable prices: shown on split-adjusted prices only.</p>
          )}
          {monthly && group.title === "Levels" && (
            <p className="text-xs text-muted-foreground">A monthly series has no levels: they need daily bars.</p>
          )}
          {index && group.title === "Markers & comparison" && (
            <p className="text-xs text-muted-foreground">
              {comparable
                ? "An index has no events or auto-rejection; it is compared with IHSG."
                : "An index, a currency pair or a commodity has no events or auto-rejection and is not compared with IHSG."}
            </p>
          )}
        </fieldset>
      ))}
    </div>
  )
}
