"use client"

import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import type { TechnicalAgentCode } from "@/lib/technical-screening"
import type { ScreeningOption } from "@/lib/types"
import { cn } from "@/lib/utils"

/** The technical agents of a run (multi-select, in the fixed order of the options). */
export function AgentPicker({
  agents,
  selected,
  onChange,
  disabled,
}: {
  agents: ScreeningOption[]
  selected: TechnicalAgentCode[]
  onChange: (agents: TechnicalAgentCode[]) => void
  disabled?: boolean
}) {
  const all = agents.map((a) => a.code as TechnicalAgentCode)

  function toggle(code: TechnicalAgentCode, checked: boolean) {
    onChange(checked ? all.filter((c) => c === code || selected.includes(c)) : selected.filter((c) => c !== code))
  }

  return (
    <fieldset className="flex flex-col gap-2" disabled={disabled}>
      <div className="flex items-center justify-between gap-2">
        <legend className="text-xs font-medium text-muted-foreground">
          Technical agents ({selected.length} of {agents.length} selected)
        </legend>
        <div className="flex gap-1">
          <Button type="button" variant="ghost" size="xs" onClick={() => onChange(all)}>
            Select all
          </Button>
          <Button type="button" variant="ghost" size="xs" onClick={() => onChange([])}>
            Clear
          </Button>
        </div>
      </div>
      <div className="grid gap-2 sm:grid-cols-2">
        {agents.map((agent) => {
          const code = agent.code as TechnicalAgentCode
          const checked = selected.includes(code)
          return (
            <label
              key={code}
              className={cn(
                "flex cursor-pointer items-start gap-2.5 rounded-lg border p-2.5 text-sm transition-colors",
                checked ? "border-primary/50 bg-primary/5" : "hover:bg-muted/50"
              )}
            >
              <Checkbox
                checked={checked}
                onCheckedChange={(value) => toggle(code, value)}
                className="mt-0.5"
                aria-label={agent.label}
              />
              <span className="min-w-0">
                <span className="block font-medium">{agent.label}</span>
                <span className="block text-xs text-muted-foreground">{agent.description}</span>
              </span>
            </label>
          )
        })}
      </div>
      {selected.length === 0 && <span className="text-xs text-destructive">Choose at least one agent.</span>}
    </fieldset>
  )
}
