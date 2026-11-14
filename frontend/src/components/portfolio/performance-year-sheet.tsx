"use client"

import { useState } from "react"
import { CircleAlertIcon, LoaderIcon, Trash2Icon } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle } from "@/components/ui/sheet"
import { jsonBody, RequestError, requestJson } from "@/lib/client-api"
import {
  performanceQuery,
  type Performance,
  type PerformanceYear,
  type PerformanceYearRequest,
} from "@/lib/portfolio-performance"

/** A typed amount: digits, an optional sign and decimal point; commas are thousands separators ("4,852,232"). */
function parseAmount(text: string, signed = false): number | null | "invalid" {
  const t = text.trim().replace(/,/g, "")
  if (t === "") return null
  if (!(signed ? /^-?\d+(\.\d+)?$/ : /^\d+(\.\d+)?$/).test(t)) return "invalid"
  const n = Number(t)
  return Number.isFinite(n) ? n : "invalid"
}

/** A typed percentage ("21.92", "-2.59" or "21.92%") as a fraction (0.2192). */
function parsePercent(text: string): number | null | "invalid" {
  const t = text.trim().replace(/%$/, "").trim()
  if (t === "") return null
  if (!/^-?\d+(\.\d+)?$/.test(t)) return "invalid"
  const n = Number(t) / 100
  return Number.isFinite(n) && n >= -1 ? Number(n.toFixed(6)) : "invalid"
}

function amountText(v: number | null | undefined): string {
  return v === null || v === undefined ? "" : String(v)
}

function percentText(v: number | null | undefined): string {
  return v === null || v === undefined ? "" : String(Number((v * 100).toFixed(4)))
}

type Field = {
  key: keyof PerformanceYearRequest
  label: string
  hint: string
  kind: "amount" | "signed" | "percent"
}

const FIELDS: Field[] = [
  { key: "startCapital", label: "Start capital (Rp)", hint: "\"Total Invest <year before>\": the market value + cash at the end of the year before. Empty: taken from the year before.", kind: "amount" },
  { key: "newInvestment", label: "New investment (Rp)", hint: "\"Total Invest <year>\": money put in during the year (negative: taken out). Empty = 0.", kind: "signed" },
  { key: "cash", label: "Cash at the year end (Rp)", hint: "Empty: the year's last daily snapshot.", kind: "amount" },
  { key: "marketValue", label: "Market value at the year end (Rp)", hint: "The stocks' value. A daily snapshot of the year takes precedence.", kind: "amount" },
  { key: "averageValue", label: "Average value at the year end (Rp)", hint: "The stocks' cost at their average prices.", kind: "amount" },
  { key: "dividend", label: "Dividends received (Rp)", hint: "\"Deviden\": Deviden % = dividend / (market value + cash).", kind: "amount" },
  { key: "ihsgYtd", label: "IHSG YTD as recorded (%)", hint: "The sheet's figure, e.g. 21.92 for +21.92%. The page computes IHSG from the stored closes.", kind: "percent" },
  { key: "lq45Ytd", label: "LQ45 YTD as recorded (%)", hint: "The sheet's figure, e.g. -14.73.", kind: "percent" },
]

/**
 * Adds or edits a year of the sheet tracking_performance: the start capital, the new investment, the year-end cash,
 * market value and average value, the dividends and the IHSG / LQ45 YTD the sheet recorded. A typed row's figures are
 * the year end; the year's daily snapshots take precedence over them once saved.
 */
export function YearSheet({
  open,
  year,
  row,
  range,
  onClose,
  onSaved,
}: {
  open: boolean
  year: number
  /** the year as the page shows it (null: a new year) */
  row: PerformanceYear | null
  range: string
  onClose: () => void
  onSaved: (performance: Performance) => void
}) {
  const [pending, setPending] = useState(false)
  return (
    <Sheet open={open} onOpenChange={(next) => !next && !pending && onClose()}>
      <SheetContent className="w-full overflow-y-auto sm:max-w-md">
        {open && (
          <YearForm
            key={year}
            year={year}
            row={row}
            range={range}
            pending={pending}
            setPending={setPending}
            onClose={onClose}
            onSaved={onSaved}
          />
        )}
      </SheetContent>
    </Sheet>
  )
}

/** The form's fields: the row as it was typed (a year without a row starts empty). */
function initialText(row: PerformanceYear | null): Record<string, string> {
  const t = row?.row ?? null
  return {
    startCapital: amountText(t?.startCapital),
    newInvestment: amountText(t?.newInvestment),
    cash: amountText(t?.cash),
    marketValue: amountText(t?.marketValue),
    averageValue: amountText(t?.averageValue),
    dividend: amountText(t?.dividend),
    ihsgYtd: percentText(t?.ihsgYtd),
    lq45Ytd: percentText(t?.lq45Ytd),
    note: t?.note ?? "",
  }
}

function YearForm({
  year,
  row,
  range,
  pending,
  setPending,
  onClose,
  onSaved,
}: {
  year: number
  row: PerformanceYear | null
  range: string
  pending: boolean
  setPending: (pending: boolean) => void
  onClose: () => void
  onSaved: (performance: Performance) => void
}) {
  const [text, setText] = useState<Record<string, string>>(() => initialText(row))
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [error, setError] = useState<string | null>(null)
  const derivedEnd = row !== null && row.endFrom !== null && row.endFrom !== "TYPED"

  async function send(init: RequestInit, failure: string) {
    setPending(true)
    try {
      const { body } = await requestJson<Performance>(
        `/api/portfolio/performance/years/${year}${performanceQuery(range)}`,
        init
      )
      onSaved(body)
      onClose()
    } catch (err) {
      if (err instanceof RequestError) {
        setErrors(err.errors)
        setError(err.message)
      } else {
        setError(failure)
      }
    } finally {
      setPending(false)
    }
  }

  async function save(e: React.FormEvent) {
    e.preventDefault()
    if (pending) return
    const next: Record<string, string> = {}
    const body: PerformanceYearRequest = {
      startCapital: null,
      newInvestment: null,
      dividend: null,
      cash: null,
      marketValue: null,
      averageValue: null,
      ihsgYtd: null,
      lq45Ytd: null,
      note: text.note.trim() || null,
    }
    for (const f of FIELDS) {
      const v = f.kind === "percent" ? parsePercent(text[f.key]) : parseAmount(text[f.key], f.kind === "signed")
      if (v === "invalid") {
        next[f.key] = f.kind === "percent" ? "Enter a percentage, e.g. 21.92 or -2.59" : f.kind === "signed"
          ? "Enter an amount, e.g. 15000000 or -5000000" : "Enter an amount of 0 or more, e.g. 4852232"
      } else {
        ;(body as Record<string, number | string | null>)[f.key] = v
      }
    }
    setErrors(next)
    setError(null)
    if (Object.keys(next).length > 0) return
    await send(jsonBody("PUT", body), "The year could not be saved.")
  }

  async function remove() {
    if (pending) return
    setErrors({})
    setError(null)
    await send({ method: "DELETE" }, "The year could not be deleted.")
  }

  return (
    <form onSubmit={save} className="flex h-full flex-col" noValidate>
      <SheetHeader>
        <SheetTitle>{row?.typed ? `Edit ${year}` : `Add ${year}`}</SheetTitle>
        <SheetDescription>
          The row of the sheet tracking_performance. Amounts in rupiah, with or without commas (4,852,232); empty
          fields are left out.
        </SheetDescription>
      </SheetHeader>
      <div className="flex flex-col gap-4 px-4">
        {derivedEnd && (
          <p className="flex items-start gap-2 rounded-lg border px-3 py-2 text-xs text-muted-foreground">
            <CircleAlertIcon className="mt-px size-3.5 shrink-0" />
            {row?.endFrom === "LIVE"
              ? "This year's figures to date come from the portfolio now; typed year-end figures are used only while the year has neither."
              : "This year's year-end figures come from its last daily snapshot, which takes precedence over typed ones."}
          </p>
        )}
        {FIELDS.map((f) => (
          <div key={f.key} className="flex flex-col gap-1.5">
            <Label htmlFor={`year-${f.key}`}>{f.label}</Label>
            <Input
              id={`year-${f.key}`}
              inputMode="decimal"
              autoComplete="off"
              value={text[f.key]}
              onChange={(e) => setText((t) => ({ ...t, [f.key]: e.target.value }))}
              aria-invalid={!!errors[f.key]}
              aria-describedby={`year-${f.key}-hint`}
            />
            <p id={`year-${f.key}-hint`} className="text-xs text-muted-foreground">{f.hint}</p>
            {errors[f.key] && <p className="text-xs text-rose-700 dark:text-rose-400">{errors[f.key]}</p>}
          </div>
        ))}
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="year-note">Note</Label>
          <Input
            id="year-note"
            autoComplete="off"
            maxLength={500}
            value={text.note}
            onChange={(e) => setText((t) => ({ ...t, note: e.target.value }))}
          />
        </div>
        {error && (
          <p role="alert" className="text-sm text-rose-700 dark:text-rose-400">
            {error}
          </p>
        )}
      </div>
      <SheetFooter className="flex-row justify-between">
        {row?.typed ? (
          <Button type="button" variant="ghost" onClick={remove} disabled={pending} className="text-rose-700 dark:text-rose-400">
            <Trash2Icon /> Delete row
          </Button>
        ) : (
          <span />
        )}
        <div className="flex gap-2">
          <Button type="button" variant="outline" onClick={onClose} disabled={pending}>
            Cancel
          </Button>
          <Button type="submit" disabled={pending}>
            {pending && <LoaderIcon className="animate-spin" />}
            Save
          </Button>
        </div>
      </SheetFooter>
    </form>
  )
}
