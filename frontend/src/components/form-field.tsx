import { Label } from "@/components/ui/label"
import { cn } from "@/lib/utils"

/** A labelled form control with an optional hint and the field error from the backend validation. */
export function FormField({
  id,
  label,
  hint,
  error,
  required,
  className,
  children,
}: {
  id: string
  label: string
  hint?: React.ReactNode
  error?: string
  required?: boolean
  className?: string
  children: React.ReactNode
}) {
  return (
    <div className={cn("flex flex-col gap-1.5", className)}>
      <Label htmlFor={id}>
        {label}
        {required && <span className="text-destructive" aria-hidden>*</span>}
      </Label>
      {children}
      {error ? (
        <p id={`${id}-error`} role="alert" className="text-xs text-destructive">
          {error}
        </p>
      ) : (
        hint && <p className="text-xs text-muted-foreground">{hint}</p>
      )}
    </div>
  )
}

