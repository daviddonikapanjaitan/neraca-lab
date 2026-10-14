"use client"

import { Checkbox } from "@/components/ui/checkbox"
import { INDICATOR_GROUPS, INDICATORS, type IndicatorId } from "@/lib/technical"

const LABELS = new Map(INDICATORS.map((i) => [i.id, i.label]))

/** Indicator checkboxes in groups; used in the settings column (desktop) and the settings sheet (phone). */
export function IndicatorPicker({
  selected,
  onToggle,
  levelsDisabled,
}: {
  selected: IndicatorId[]
  onToggle: (id: IndicatorId, on: boolean) => void
  /** levels are tradable prices: not drawn on the total-return basis */
  levelsDisabled: boolean
}) {
  return (
    <div className="space-y-4">
      {INDICATOR_GROUPS.map((group) => (
        <fieldset key={group.title} className="space-y-1.5">
          <legend className="mb-1 text-xs font-medium uppercase tracking-wide text-muted-foreground">{group.title}</legend>
          {group.ids.map((id) => {
            const disabled = levelsDisabled && group.title === "Levels"
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
        </fieldset>
      ))}
    </div>
  )
}
