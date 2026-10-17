"use client"

import { useEffect, useId, useRef, useState } from "react"
import { useQuery } from "@tanstack/react-query"
import { LoaderIcon, SearchIcon } from "lucide-react"
import { z } from "zod"

import { Input } from "@/components/ui/input"
import { fetchValidated, tickerSchema, type TickerInfo } from "@/lib/technical"
import { cn } from "@/lib/utils"

/** Ticker search with autocomplete (code or company name), keyboard navigable (arrows, Enter, Escape). */
export function TickerSearch({
  exchange,
  value,
  onSelect,
  itemLabel = "Ticker",
}: {
  exchange: string
  value: string | null
  onSelect: (ticker: TickerInfo) => void
  /** what the market's entries are called: Ticker (an exchange), Currency, Commodity */
  itemLabel?: string
}) {
  const market = itemLabel !== "Ticker"
  const prompt = market ? `Search ${itemLabel.toLowerCase()}` : "Search ticker, company, IHSG or LQ45"
  const [text, setText] = useState("")
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(0)
  const [query, setQuery] = useState("")
  const listId = useId()
  const rootRef = useRef<HTMLDivElement>(null)

  // debounce the search
  useEffect(() => {
    const id = setTimeout(() => setQuery(text.trim()), 150)
    return () => clearTimeout(id)
  }, [text])

  const results = useQuery({
    queryKey: ["technical", "tickers", exchange, query],
    queryFn: ({ signal }) =>
      fetchValidated(
        `/api/technical/tickers?${new URLSearchParams({ exchange, query, limit: "20" })}`,
        z.array(tickerSchema),
        signal
      ),
    enabled: open,
    staleTime: 60_000,
  })
  const items = results.data ?? []

  useEffect(() => {
    const close = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener("mousedown", close)
    return () => document.removeEventListener("mousedown", close)
  }, [])

  const choose = (item: TickerInfo) => {
    onSelect(item)
    setText("")
    setOpen(false)
  }

  return (
    <div ref={rootRef} className="relative w-full sm:w-[320px]">
      <SearchIcon className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground" />
      <Input
        role="combobox"
        aria-expanded={open}
        aria-controls={listId}
        aria-autocomplete="list"
        aria-label={prompt}
        placeholder={value ? `${value} · search another ${market ? itemLabel.toLowerCase() : "ticker"}` : prompt}
        className="pl-8"
        value={text}
        onFocus={() => setOpen(true)}
        onChange={(e) => {
          setText(e.target.value)
          setOpen(true)
          setActive(0)
        }}
        onKeyDown={(e) => {
          if (e.key === "ArrowDown") {
            e.preventDefault()
            setOpen(true)
            setActive((i) => Math.min(i + 1, Math.max(0, items.length - 1)))
          } else if (e.key === "ArrowUp") {
            e.preventDefault()
            setActive((i) => Math.max(i - 1, 0))
          } else if (e.key === "Enter" && items[active]) {
            e.preventDefault()
            choose(items[active])
          } else if (e.key === "Escape") {
            setOpen(false)
          }
        }}
      />
      {results.isFetching && (
        <LoaderIcon className="absolute top-1/2 right-2.5 size-4 -translate-y-1/2 animate-spin text-muted-foreground" />
      )}
      {open && (
        <ul
          id={listId}
          role="listbox"
          className="absolute z-50 mt-1 max-h-80 w-full overflow-y-auto rounded-lg border bg-popover p-1 text-popover-foreground shadow-md"
        >
          {results.isError && <li className="px-2 py-1.5 text-sm text-destructive">{results.error.message}</li>}
          {!results.isError && items.length === 0 && !results.isFetching && (
            <li className="px-2 py-1.5 text-sm text-muted-foreground">
              {market ? `No ${itemLabel.toLowerCase()} found` : "No company or index found"}
            </li>
          )}
          {items.map((item, i) => (
            <li
              key={`${item.type}-${item.ticker}`}
              role="option"
              aria-selected={i === active}
              className={cn(
                "flex cursor-pointer items-center justify-between gap-3 rounded-md px-2 py-1.5 text-sm",
                i === active && "bg-accent text-accent-foreground"
              )}
              onMouseEnter={() => setActive(i)}
              onMouseDown={(e) => {
                e.preventDefault()
                choose(item)
              }}
            >
              <span className="min-w-0">
                <span className="font-mono font-semibold">{item.ticker}</span>{" "}
                <span className="truncate text-muted-foreground">{item.companyName}</span>
              </span>
              <span className="shrink-0 text-xs text-muted-foreground">
                {item.type === "INDEX"
                  ? item.tradingDays === 0
                    ? "index · no bars"
                    : "index"
                  : item.type === "FOREX" || item.type === "COMMODITY"
                    ? item.tradingDays === 0
                      ? "no prices"
                      : item.frequency === "MONTHLY"
                        ? "monthly"
                        : (item.unit ?? "")
                  : item.tradingDays === 0
                    ? "no prices"
                    : item.limitedHistory
                      ? `${item.tradingDays} days`
                      : ""}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
