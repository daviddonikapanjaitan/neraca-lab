"use client"

import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import type { CompanySummary, Exchange } from "@/lib/types"

/** Exchange and ticker dropdowns over the stored companies (the RAG documents of a company link to it). */
export function CompanyPicker({
  exchanges,
  companiesByExchange,
  exchange,
  ticker,
  onExchange,
  onTicker,
  disabled,
}: {
  exchanges: Exchange[]
  /** companies stored per exchange code, ordered by ticker */
  companiesByExchange: Record<string, CompanySummary[]>
  exchange: string | null
  ticker: string | null
  onExchange: (code: string) => void
  onTicker: (ticker: string | null) => void
  disabled?: boolean
}) {
  const companies = exchange ? companiesByExchange[exchange] ?? [] : []
  const exchangeItems = exchanges.map((e) => ({ value: e.code, label: `${e.code} · ${e.name}` }))
  const tickerItems = companies.map((c) => ({ value: c.ticker, label: `${c.ticker} · ${c.companyName}` }))

  return (
    <div className="grid gap-2 sm:grid-cols-2">
      <div className="flex flex-col gap-1.5">
        <span className="text-xs font-medium text-muted-foreground">Exchange</span>
        <Select
          items={exchangeItems}
          value={exchange}
          onValueChange={(code) => {
            if (code && code !== exchange) onExchange(code)
          }}
        >
          <SelectTrigger aria-label="Exchange" className="w-full" disabled={disabled}>
            <SelectValue placeholder="Choose an exchange" />
          </SelectTrigger>
          <SelectContent>
            {exchangeItems.map((e) => (
              <SelectItem key={e.value} value={e.value}>
                {e.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
      <div className="flex flex-col gap-1.5">
        <span className="text-xs font-medium text-muted-foreground">Company</span>
        <Select items={tickerItems} value={ticker} onValueChange={(value) => onTicker(value)}>
          <SelectTrigger aria-label="Company" className="w-full" disabled={disabled || companies.length === 0}>
            <SelectValue placeholder={companies.length === 0 ? "No companies stored" : "Choose a company"} />
          </SelectTrigger>
          <SelectContent>
            {tickerItems.map((t) => (
              <SelectItem key={t.value} value={t.value}>
                {t.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
    </div>
  )
}

/** The stored ticker a file name names, e.g. "FinancialStatement-2025-Tahunan-HRTA.pdf" -> "HRTA". */
export function tickerInFileName(fileName: string, companies: CompanySummary[]): string | null {
  const tokens = fileName
    .replace(/\.[^.]+$/, "")
    .toUpperCase()
    .split(/[^A-Z0-9]+/)
    .filter(Boolean)
  const tickers = new Set(companies.map((c) => c.ticker))
  // the last matching token: IDX file names end with the ticker
  for (let i = tokens.length - 1; i >= 0; i--) {
    if (tickers.has(tokens[i])) return tokens[i]
  }
  return null
}
