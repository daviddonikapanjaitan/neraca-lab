// Showing a USD reporter's amounts in IDR (Companies > Fundamentals, the USD | IDR toggle). The rates come with the
// company detail (`conversion`): per period the USD/IDR closing rate of the period end and the average of the daily
// closes over the period (Forex pair USDIDR, Ingestion > Price Ingestion). As IAS 21 translates statements:
// balance-sheet amounts at the closing rate, income-statement, cash-flow and segment amounts (and EPS) at the average.
import type { CurrencyConversion, Period } from "@/lib/types"

/** closing: the rate at the period end (balance sheet); average: the mean over the period (flows) */
export type RateBasis = "closing" | "average"

/** How a table converts its amounts: the period's rate (null: no rate stored -> the amount is not shown). */
export interface Conversion {
  /** the currency shown (IDR) */
  currency: string
  /** e.g. "USD/IDR" */
  pair: string
  basis: RateBasis
  rate: (period: Period) => number | null
  /** e.g. "average of 256 closes" / "close of 31 Dec 2025" */
  describe: (period: Period) => string | null
}

export function conversionFor(conversion: CurrencyConversion, basis: RateBasis): Conversion {
  const byPeriod = new Map(conversion.rates.map((r) => [r.periodId, r]))
  return {
    currency: conversion.to,
    pair: `${conversion.from}/${conversion.to}`,
    basis,
    rate: (period) => {
      const r = byPeriod.get(period.periodId)
      const value = basis === "closing" ? r?.closingRate : r?.averageRate
      return value === null || value === undefined ? null : value
    },
    describe: (period) => {
      const r = byPeriod.get(period.periodId)
      if (!r) return null
      if (basis === "closing") return r.closingRateDate ? `close of ${r.closingRateDate}` : null
      return r.averageRate !== null ? `average of ${r.averageDays} closes` : null
    },
  }
}

/**
 * The rate of each valuation date (the close on or before it): a valuation row's amounts all at that one rate, so P/E,
 * P/B, EV/EBITDA and the yields are the same in either currency.
 */
export interface DateConversion {
  currency: string
  pair: string
  rate: (date: string) => { rate: number; rateDate: string } | null
}

export function dateConversionFor(conversion: CurrencyConversion): DateConversion {
  const byDate = new Map((conversion.valuationRates ?? []).map((r) => [r.date, r]))
  return {
    currency: conversion.to,
    pair: `${conversion.from}/${conversion.to}`,
    rate: (date) => {
      const r = byDate.get(date)
      return r ? { rate: r.rate, rateDate: r.rateDate } : null
    },
  }
}

/** The amount in the shown currency; null when the period has no rate (never an unconverted amount). */
export function converted(value: number | null | undefined, period: Period, conversion: Conversion | null): number | null {
  if (value === null || value === undefined) return null
  if (!conversion) return value
  const rate = conversion.rate(period)
  return rate === null ? null : value * rate
}
