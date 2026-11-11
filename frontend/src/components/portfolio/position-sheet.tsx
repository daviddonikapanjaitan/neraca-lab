"use client"

import { useState } from "react"
import { CircleAlertIcon, LoaderIcon } from "lucide-react"

import { TickerSearch } from "@/components/technical/ticker-search"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet"
import { jsonBody, RequestError, requestJson } from "@/lib/client-api"
import { formatNumber } from "@/lib/format"
import type { Portfolio, PortfolioPosition } from "@/lib/portfolio"

/** Today in Jakarta, "2026-11-11": the latest date of a first buy. */
function today(): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Jakarta" }).format(new Date())
}

/** A typed amount: digits with an optional decimal point; commas are thousands separators ("4,852,232"). */
function parseAmount(text: string): number | null {
  const t = text.trim().replace(/,/g, "")
  if (!/^\d+(\.\d+)?$/.test(t)) return null
  const n = Number(t)
  return Number.isFinite(n) ? n : null
}

type FormProps = {
  pending: boolean
  setPending: (pending: boolean) => void
  onClose: () => void
  onSaved: (portfolio: Portfolio) => void
}

/**
 * Adds a stock to the portfolio or edits one: the average buy price, the number of shares and the date of the first
 * buy ("Tanggal Masuk"). The company comes from Companies (search by ticker or name); everything else of the row is
 * read from its Fundamentals.
 */
export function PositionSheet({
  open,
  position,
  exchange = "IDX",
  onClose,
  onSaved,
}: {
  open: boolean
  /** null: a new position */
  position: PortfolioPosition | null
  exchange?: string
  onClose: () => void
  onSaved: (portfolio: Portfolio) => void
}) {
  const [pending, setPending] = useState(false)
  return (
    <Sheet open={open} onOpenChange={(next) => !next && !pending && onClose()}>
      <SheetContent className="w-full overflow-y-auto sm:max-w-md">
        {/* mounted per opening, so the form starts from the position */}
        {open && (
          <PositionForm
            key={position?.positionId ?? "new"}
            position={position}
            exchange={exchange}
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

function PositionForm({
  position,
  exchange,
  pending,
  setPending,
  onClose,
  onSaved,
}: FormProps & { position: PortfolioPosition | null; exchange: string }) {
  const [ticker, setTicker] = useState<string | null>(position?.ticker ?? null)
  const [companyName, setCompanyName] = useState<string | null>(position?.companyName ?? null)
  const [price, setPrice] = useState(position ? String(position.averagePrice) : "")
  const [shares, setShares] = useState(position ? String(position.shares) : "")
  const [dateIn, setDateIn] = useState(position?.dateIn ?? "")
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [error, setError] = useState<string | null>(null)

  const sharesValue = parseAmount(shares)

  async function save(e: React.FormEvent) {
    e.preventDefault()
    if (pending) return
    const next: Record<string, string> = {}
    const averagePrice = parseAmount(price)
    if (!position && !ticker) next.ticker = "Choose a company"
    if (averagePrice === null || averagePrice <= 0) next.averagePrice = "Enter a price above 0, e.g. 1537.92"
    if (sharesValue === null || sharesValue < 1 || !Number.isInteger(sharesValue)) {
      next.shares = "Enter a whole number of shares, e.g. 6600"
    }
    if (dateIn && dateIn > today()) next.dateIn = "The first buy cannot be in the future"
    setErrors(next)
    setError(null)
    if (Object.keys(next).length > 0) return

    setPending(true)
    try {
      const body = { averagePrice, shares: sharesValue, dateIn: dateIn || null }
      const { body: portfolio } = position
        ? await requestJson<Portfolio>(`/api/portfolio/positions/${position.positionId}`, jsonBody("PUT", body))
        : await requestJson<Portfolio>("/api/portfolio/positions", jsonBody("POST", { exchange, ticker, ...body }))
      onSaved(portfolio)
      onClose()
    } catch (err) {
      if (err instanceof RequestError) {
        setErrors(err.errors)
        setError(err.message)
      } else {
        setError("The position could not be saved.")
      }
    } finally {
      setPending(false)
    }
  }

  return (
    <form onSubmit={save} className="flex h-full flex-col" noValidate>
      <SheetHeader>
        <SheetTitle>{position ? `Edit ${position.ticker}` : "Add a position"}</SheetTitle>
        <SheetDescription>
          {position
            ? position.companyName
            : "The stock's price, fundamentals and dividends come from Companies > Fundamentals."}
        </SheetDescription>
      </SheetHeader>

      <div className="flex flex-col gap-4 px-4">
        {!position && (
          <div className="flex flex-col gap-1.5">
            <Label>Company</Label>
            <TickerSearch
              exchange={exchange}
              value={ticker}
              onSelect={(t) => {
                if (t.type !== "STOCK") {
                  setErrors((e) => ({ ...e, ticker: `${t.ticker} is an index, not a company` }))
                  return
                }
                setTicker(t.ticker)
                setCompanyName(t.companyName)
                setErrors((e) => Object.fromEntries(Object.entries(e).filter(([k]) => k !== "ticker")))
              }}
            />
            {ticker && (
              <p className="text-xs text-muted-foreground">
                <span className="font-mono font-medium text-foreground">{ticker}</span>
                {companyName && ` · ${companyName}`}
              </p>
            )}
            <FieldError message={errors.ticker} />
          </div>
        )}

        <div className="flex flex-col gap-1.5">
          <Label htmlFor="position-price">Average price (Rp per share)</Label>
          <Input
            id="position-price"
            inputMode="decimal"
            autoComplete="off"
            placeholder="1537.92"
            value={price}
            onChange={(e) => setPrice(e.target.value)}
            aria-invalid={!!errors.averagePrice}
          />
          <FieldError message={errors.averagePrice} />
        </div>

        <div className="flex flex-col gap-1.5">
          <Label htmlFor="position-shares">Shares</Label>
          <Input
            id="position-shares"
            inputMode="numeric"
            autoComplete="off"
            placeholder="6600"
            value={shares}
            onChange={(e) => setShares(e.target.value)}
            aria-invalid={!!errors.shares}
          />
          {sharesValue !== null && sharesValue > 0 && (
            <p className="text-xs text-muted-foreground">{formatNumber(sharesValue / 100, 2)} lots of 100 shares</p>
          )}
          <FieldError message={errors.shares} />
        </div>

        <div className="flex flex-col gap-1.5">
          <Label htmlFor="position-date">Date in (first buy)</Label>
          <Input
            id="position-date"
            type="date"
            max={today()}
            value={dateIn}
            onChange={(e) => setDateIn(e.target.value)}
            aria-invalid={!!errors.dateIn}
          />
          <FieldError message={errors.dateIn} />
        </div>

        <FormError message={error} />
      </div>

      <SheetFooter className="mt-auto">
        <Button type="submit" disabled={pending}>
          {pending && <LoaderIcon className="animate-spin" />}
          {position ? "Save changes" : "Add position"}
        </Button>
        <Button type="button" variant="outline" onClick={onClose} disabled={pending}>
          Cancel
        </Button>
      </SheetFooter>
    </form>
  )
}

/** Cash and total investment: the two figures of the portfolio that are typed rather than read. */
export function SettingsSheet({
  open,
  cash,
  totalInvestment,
  onClose,
  onSaved,
}: {
  open: boolean
  cash: number
  totalInvestment: number
  onClose: () => void
  onSaved: (portfolio: Portfolio) => void
}) {
  const [pending, setPending] = useState(false)
  return (
    <Sheet open={open} onOpenChange={(next) => !next && !pending && onClose()}>
      <SheetContent className="w-full overflow-y-auto sm:max-w-md">
        {open && (
          <SettingsForm
            cash={cash}
            totalInvestment={totalInvestment}
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

function SettingsForm({
  cash,
  totalInvestment,
  pending,
  setPending,
  onClose,
  onSaved,
}: FormProps & { cash: number; totalInvestment: number }) {
  const [cashText, setCashText] = useState(String(cash))
  const [investmentText, setInvestmentText] = useState(String(totalInvestment))
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [error, setError] = useState<string | null>(null)

  async function save(e: React.FormEvent) {
    e.preventDefault()
    if (pending) return
    const c = parseAmount(cashText)
    const t = parseAmount(investmentText)
    const next: Record<string, string> = {}
    if (c === null) next.cash = "Enter an amount of 0 or more, e.g. 4852232"
    if (t === null) next.totalInvestment = "Enter an amount of 0 or more, e.g. 150000000"
    setErrors(next)
    setError(null)
    if (Object.keys(next).length > 0) return
    setPending(true)
    try {
      const { body } = await requestJson<Portfolio>(
        "/api/portfolio/settings",
        jsonBody("PUT", { cash: c, totalInvestment: t })
      )
      onSaved(body)
      onClose()
    } catch (err) {
      if (err instanceof RequestError) {
        setErrors(err.errors)
        setError(err.message)
      } else {
        setError("The amounts could not be saved.")
      }
    } finally {
      setPending(false)
    }
  }

  return (
    <form onSubmit={save} className="flex h-full flex-col" noValidate>
      <SheetHeader>
        <SheetTitle>Cash and total investment</SheetTitle>
        <SheetDescription>
          Total profit = market total + cash − total investment. Amounts in rupiah, with or without commas
          (4,852,232).
        </SheetDescription>
      </SheetHeader>
      <div className="flex flex-col gap-4 px-4">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="portfolio-cash">Total cash (Rp)</Label>
          <Input
            id="portfolio-cash"
            inputMode="decimal"
            autoComplete="off"
            value={cashText}
            onChange={(e) => setCashText(e.target.value)}
            aria-invalid={!!errors.cash}
          />
          <FieldError message={errors.cash} />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="portfolio-investment">Total investment (Rp)</Label>
          <Input
            id="portfolio-investment"
            inputMode="decimal"
            autoComplete="off"
            value={investmentText}
            onChange={(e) => setInvestmentText(e.target.value)}
            aria-invalid={!!errors.totalInvestment}
          />
          <p className="text-xs text-muted-foreground">The capital put into the portfolio so far.</p>
          <FieldError message={errors.totalInvestment} />
        </div>
        <FormError message={error} />
      </div>
      <SheetFooter className="mt-auto">
        <Button type="submit" disabled={pending}>
          {pending && <LoaderIcon className="animate-spin" />}
          Save
        </Button>
        <Button type="button" variant="outline" onClick={onClose} disabled={pending}>
          Cancel
        </Button>
      </SheetFooter>
    </form>
  )
}

function FieldError({ message }: { message?: string }) {
  if (!message) return null
  return <p className="text-xs text-destructive">{message}</p>
}

function FormError({ message }: { message: string | null }) {
  if (!message) return null
  return (
    <p role="alert" className="flex items-start gap-2 text-xs text-destructive">
      <CircleAlertIcon className="mt-px size-3.5 shrink-0" />
      <span>{message}</span>
    </p>
  )
}
