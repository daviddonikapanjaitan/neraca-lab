"use client"

import { AMOUNT_SCALES, type AmountScale } from "@/lib/format"
import { PERIOD_TYPE_LABEL } from "@/lib/statements"
import { cn } from "@/lib/utils"

export const ALL_PERIOD_TYPES = "ALL"

/** Segmented control in the style of the shadcn-fintech performance-period buttons. */
function Segmented<T extends string>({
  label,
  value,
  options,
  onChange,
}: {
  label: string
  value: T
  options: { value: T; label: string; title?: string }[]
  onChange: (value: T) => void
}) {
  return (
    <div role="radiogroup" aria-label={label} className="flex flex-wrap items-center rounded-lg border border-border p-0.5">
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          role="radio"
          aria-checked={value === o.value}
          title={o.title}
          onClick={() => onChange(o.value)}
          className={cn(
            "rounded-md px-2.5 py-1 text-xs font-medium transition-colors",
            value === o.value
              ? "bg-primary text-primary-foreground"
              : "text-muted-foreground hover:text-foreground"
          )}
        >
          {o.label}
        </button>
      ))}
    </div>
  )
}

/** "All" plus every period type present, e.g. All | FY | Q1 | H1 | 9M. */
export function PeriodTypeFilter({
  value,
  types,
  onChange,
}: {
  value: string
  types: string[]
  onChange: (value: string) => void
}) {
  return (
    <Segmented
      label="Period type"
      value={value}
      onChange={onChange}
      options={[
        { value: ALL_PERIOD_TYPES, label: "All" },
        ...types.map((t) => ({ value: t, label: t, title: PERIOD_TYPE_LABEL[t] })),
      ]}
    />
  )
}

export function ScaleToggle({
  value,
  currency,
  onChange,
}: {
  value: AmountScale
  currency: string | null
  onChange: (value: AmountScale) => void
}) {
  return (
    <Segmented
      label="Amount scale"
      value={value}
      onChange={onChange}
      options={AMOUNT_SCALES.map((s) => ({
        value: s.value,
        label: s.label,
        title: s.value === "full" ? `Full ${currency ?? ""} amounts` : `${currency ?? ""} ${s.label.toLowerCase()}`,
      }))}
    />
  )
}
