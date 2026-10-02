"use client"

import { useMemo, useState } from "react"

import { CompanyHeader } from "@/components/company/company-header"
import { FilingsTable } from "@/components/company/filings-table"
import { MarketPanel } from "@/components/company/market-panel"
import { MetricsTable } from "@/components/company/metrics-table"
import { Overview } from "@/components/company/overview"
import { SegmentsPanel } from "@/components/company/segments-panel"
import { StatementTable } from "@/components/company/statement-table"
import { ALL_PERIOD_TYPES, PeriodTypeFilter, ScaleToggle } from "@/components/company/toolbar"
import { ValuationsTable } from "@/components/company/valuations-table"
import { EmptyState } from "@/components/empty-state"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import type { AmountScale } from "@/lib/format"
import {
  BALANCE_SHEET,
  CASH_FLOW_STATEMENT,
  INCOME_STATEMENT,
  PERIOD_TYPES,
  type LineItem,
} from "@/lib/statements"
import { DETAIL_TABS, isDetailTab, type DetailTab } from "@/lib/tabs"
import type { CompanyDetail, Period } from "@/lib/types"

const STATEMENT_NOTES: Record<"income" | "balance" | "cashflow", string> = {
  income: "Expenses are positive; H1 and 9M periods are year-to-date.",
  balance: "Position at the end of each period.",
  cashflow: "Signed as cash moves: inflows positive, outflows negative (in parentheses).",
}

export function CompanyDetailView({ detail, initialTab }: { detail: CompanyDetail; initialTab: DetailTab }) {
  const [tab, setTab] = useState<DetailTab>(initialTab)
  const [periodType, setPeriodType] = useState<string>(ALL_PERIOD_TYPES)
  const [scale, setScale] = useState<AmountScale>("billions")
  const currency = detail.company.currency

  // period types present, in a fixed order (FY, Q1, H1, 9M, ...)
  const periodTypes = useMemo(() => {
    const present = new Set(detail.periods.map((p) => p.periodType))
    return [
      ...PERIOD_TYPES.filter((t) => present.has(t)),
      ...Array.from(present).filter((t) => !(PERIOD_TYPES as readonly string[]).includes(t)),
    ]
  }, [detail.periods])

  const byType = useMemo(
    () => detail.periods.filter((p) => periodType === ALL_PERIOD_TYPES || p.periodType === periodType),
    [detail.periods, periodType]
  )

  function changeTab(value: unknown) {
    if (!isDetailTab(value)) return
    setTab(value)
    // keep the tab in the URL (shareable) without a server round trip
    const url = new URL(window.location.href)
    if (value === "overview") url.searchParams.delete("tab")
    else url.searchParams.set("tab", value)
    window.history.replaceState(null, "", url.pathname + url.search + url.hash)
  }

  const toolbar = (withScale: boolean) => (
    <div className="flex flex-wrap items-center gap-2">
      <PeriodTypeFilter value={periodType} types={periodTypes} onChange={setPeriodType} />
      {withScale && <ScaleToggle value={scale} currency={currency} onChange={setScale} />}
    </div>
  )

  function statementCard<T>(
    key: "income" | "balance" | "cashflow",
    title: string,
    items: LineItem<T>[],
    pick: (p: Period) => T | null
  ) {
    const periods = byType.filter((p) => pick(p) !== null)
    return (
      <Card>
        <CardHeader>
          <div className="flex flex-col gap-3 xl:flex-row xl:items-center xl:justify-between">
            <div className="space-y-1">
              <CardTitle>{title}</CardTitle>
              <CardDescription>
                {currency ?? "Company currency"}
                {scale !== "full" && ` ${scale}`} · {STATEMENT_NOTES[key]}
              </CardDescription>
            </div>
            {toolbar(true)}
          </div>
        </CardHeader>
        <CardContent className="px-0">
          {periods.length === 0 ? (
            <EmptyState
              variant="filter"
              title={`No ${title.toLowerCase()}`}
              description="No period of the selected type has this statement."
            />
          ) : (
            <StatementTable items={items} periods={periods} pick={pick} scale={scale} />
          )}
        </CardContent>
      </Card>
    )
  }

  return (
    <div className="flex flex-col gap-4">
      <CompanyHeader detail={detail} />

      <Tabs value={tab} onValueChange={changeTab}>
        <div className="-mx-1 overflow-x-auto px-1 pb-1">
          <TabsList>
            {DETAIL_TABS.map((t) => (
              <TabsTrigger key={t.value} value={t.value} className="px-2.5">
                {t.label}
              </TabsTrigger>
            ))}
          </TabsList>
        </div>

        <TabsContent value="overview" className="pt-2">
          <Overview detail={detail} periodTypes={periodTypes} />
        </TabsContent>

        <TabsContent value="income" className="pt-2">
          {statementCard("income", "Income statement", INCOME_STATEMENT, (p) => p.incomeStatement)}
        </TabsContent>

        <TabsContent value="balance" className="pt-2">
          {statementCard("balance", "Balance sheet", BALANCE_SHEET, (p) => p.balanceSheet)}
        </TabsContent>

        <TabsContent value="cashflow" className="pt-2">
          {statementCard("cashflow", "Cash flow statement", CASH_FLOW_STATEMENT, (p) => p.cashFlowStatement)}
        </TabsContent>

        <TabsContent value="segments" className="pt-2">
          <div className="flex flex-col gap-3">
            <div className="flex justify-end">
              <ScaleToggle value={scale} currency={currency} onChange={setScale} />
            </div>
            <SegmentsPanel detail={detail} scale={scale} />
          </div>
        </TabsContent>

        <TabsContent value="metrics" className="pt-2">
          <Card>
            <CardHeader>
              <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
                <div className="space-y-1">
                  <CardTitle>Fundamental metrics</CardTitle>
                  <CardDescription>
                    Calculated per period. Ratios in %, multiples in x; annualized values scale partial years to 12 months.
                  </CardDescription>
                </div>
                {toolbar(false)}
              </div>
            </CardHeader>
            <CardContent className="px-0">
              <MetricsTable periods={byType} />
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="valuation" className="pt-2">
          <Card>
            <CardHeader>
              <CardTitle>Valuation history</CardTitle>
              <CardDescription>
                Last close against trailing-twelve-month figures of the latest period ended on or before each date.
              </CardDescription>
            </CardHeader>
            <CardContent className="px-0">
              <ValuationsTable valuations={detail.valuations} currency={currency} />
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="market" className="pt-2">
          <MarketPanel detail={detail} />
        </TabsContent>

        <TabsContent value="filings" className="pt-2">
          <Card>
            <CardHeader>
              <CardTitle>Reporting periods</CardTitle>
              <CardDescription>
                Every period stored for {detail.company.ticker}, where it came from and which data it has.
              </CardDescription>
            </CardHeader>
            <CardContent className="px-0">
              <FilingsTable periods={detail.periods} />
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
    </div>
  )
}
