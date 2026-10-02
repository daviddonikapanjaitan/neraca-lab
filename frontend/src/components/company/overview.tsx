"use client"

import { useMemo, useState } from "react"
import {
  BanknoteIcon,
  CoinsIcon,
  LandmarkIcon,
  PercentIcon,
  ScaleIcon,
  TrendingDownIcon,
  TrendingUpIcon,
  WalletIcon,
} from "lucide-react"
import { Bar, BarChart, CartesianGrid, Line, LineChart, XAxis, YAxis } from "recharts"

import { EmptyState } from "@/components/empty-state"
import { StatTile } from "@/components/stat-tile"
import { ALL_PERIOD_TYPES, PeriodTypeFilter } from "@/components/company/toolbar"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import {
  ChartContainer,
  ChartLegend,
  ChartLegendContent,
  ChartTooltip,
  ChartTooltipContent,
  type ChartConfig,
} from "@/components/ui/chart"
import {
  EMPTY,
  formatCompact,
  formatDate,
  formatMultiple,
  formatNumber,
  formatPercent,
} from "@/lib/format"
import type { CompanyDetail, Period } from "@/lib/types"

const financialsConfig = {
  // the template palette is greyscale: pick shades that contrast with the card in each theme
  revenue: { label: "Revenue", theme: { light: "oklch(0.371 0 0)", dark: "oklch(0.87 0 0)" } },
  grossProfit: { label: "Gross profit", theme: { light: "oklch(0.708 0 0)", dark: "oklch(0.556 0 0)" } },
  netIncomeToParent: { label: "Net income (parent)", color: "oklch(69.6% 0.17 162.48)" },
} satisfies ChartConfig

const valuationConfig = {
  peRatio: { label: "P/E", theme: { light: "oklch(0.371 0 0)", dark: "oklch(0.87 0 0)" } },
  pbRatio: { label: "P/B", color: "oklch(69.6% 0.17 162.48)" },
  evOp: { label: "EV / OP", color: "oklch(76.9% 0.188 70.08)" },
} satisfies ChartConfig

/** Most recent period that has the given statement. */
function latestWith(periods: Period[], key: "incomeStatement" | "balanceSheet" | "cashFlowStatement") {
  return periods.find((p) => p[key] !== null) ?? null
}

export function Overview({ detail, periodTypes }: { detail: CompanyDetail; periodTypes: string[] }) {
  const { company, periods, valuations, coverage } = detail
  const currency = company.currency ?? undefined

  const incomePeriod = latestWith(periods, "incomeStatement")
  const balancePeriod = latestWith(periods, "balanceSheet")
  const cashPeriod = latestWith(periods, "cashFlowStatement")
  const income = incomePeriod?.incomeStatement ?? null
  const balance = balancePeriod?.balanceSheet ?? null
  const cash = cashPeriod?.cashFlowStatement ?? null
  const valuation = valuations[0] ?? null

  const netMargin =
    income?.netIncome != null && income.revenue ? income.netIncome / income.revenue : null
  const freeCashFlow =
    cash?.operatingCashFlow != null && cash.capitalExpenditure != null
      ? cash.operatingCashFlow + cash.capitalExpenditure
      : null

  // default to full years when there are at least two, so the bars compare like with like
  const [chartType, setChartType] = useState(() =>
    periods.filter((p) => p.periodType === "FY" && p.incomeStatement).length >= 2 ? "FY" : ALL_PERIOD_TYPES
  )

  const financialData = useMemo(
    () =>
      periods
        .filter((p) => p.incomeStatement && (chartType === ALL_PERIOD_TYPES || p.periodType === chartType))
        .slice()
        .reverse()
        .map((p) => ({
          period: p.period,
          revenue: p.incomeStatement?.revenue ?? null,
          grossProfit: p.incomeStatement?.grossProfit ?? null,
          netIncomeToParent: p.incomeStatement?.netIncomeToParent ?? null,
        })),
    [periods, chartType]
  )

  const valuationData = useMemo(
    () =>
      valuations
        .slice()
        .reverse()
        .map((v) => ({
          date: v.valuationDate,
          peRatio: v.peRatio,
          pbRatio: v.pbRatio,
          evOp: v.evOp,
        })),
    [valuations]
  )

  const coverageRows: [string, string][] = [
    ["Reporting periods", `${coverage.periods}`],
    ["Period range", coverage.firstPeriodEnd ? `${formatDate(coverage.firstPeriodEnd)} – ${formatDate(coverage.latestPeriodEnd)}` : EMPTY],
    ["Income statements", `${coverage.incomeStatements}`],
    ["Balance sheets", `${coverage.balanceSheets}`],
    ["Cash flow statements", `${coverage.cashFlowStatements}`],
    ["Segments / segment figures", `${coverage.segments} / ${coverage.segmentFinancials}`],
    ["Price days", `${formatNumber(coverage.priceDays, 0)}`],
    ["Price range", coverage.firstPriceDate ? `${formatDate(coverage.firstPriceDate)} – ${formatDate(coverage.latestPriceDate)}` : EMPTY],
    ["Share snapshots", `${coverage.shareSnapshots}`],
    ["Market snapshots", `${formatNumber(coverage.marketSnapshots, 0)}`],
    ["Valuation snapshots", `${coverage.valuationSnapshots}`],
    ["Financial metrics", `${formatNumber(coverage.financialMetrics, 0)}`],
    ["Corporate actions", `${coverage.corporateActions}`],
  ]

  return (
    <div className="flex flex-col gap-4">
      {/* KPI tiles */}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <StatTile
          label="Revenue"
          value={formatCompact(income?.revenue, currency)}
          hint={incomePeriod ? `${incomePeriod.period} · ${formatDate(incomePeriod.periodEnd)}` : "No income statement"}
          icon={BanknoteIcon}
        />
        <StatTile
          label="Net income (parent)"
          value={formatCompact(income?.netIncomeToParent, currency)}
          hint={netMargin !== null ? `Net margin ${formatPercent(netMargin)}` : incomePeriod?.period}
          icon={income?.netIncomeToParent != null && income.netIncomeToParent < 0 ? TrendingDownIcon : TrendingUpIcon}
          tone={income?.netIncomeToParent != null && income.netIncomeToParent < 0 ? "negative" : "positive"}
        />
        <StatTile
          label="Total assets"
          value={formatCompact(balance?.totalAssets, currency)}
          hint={balancePeriod ? `${balancePeriod.period} · ${formatDate(balancePeriod.periodEnd)}` : "No balance sheet"}
          icon={LandmarkIcon}
          tone="muted"
        />
        <StatTile
          label="Equity (parent)"
          value={formatCompact(balance?.shareholdersEquity, currency)}
          hint={balance?.totalLiabilities != null ? `Liabilities ${formatCompact(balance.totalLiabilities, currency)}` : balancePeriod?.period}
          icon={WalletIcon}
          tone="muted"
        />
        <StatTile
          label="Free cash flow"
          value={formatCompact(freeCashFlow, currency)}
          hint={cashPeriod ? `${cashPeriod.period} · operating cash flow + capex` : "No cash flow statement"}
          icon={CoinsIcon}
          tone={freeCashFlow !== null && freeCashFlow < 0 ? "negative" : "positive"}
        />
        <StatTile
          label="P/E (TTM)"
          value={formatMultiple(valuation?.peRatio)}
          hint={valuation ? `${formatDate(valuation.valuationDate)} · based on ${valuation.period ?? EMPTY}` : "No valuation yet"}
          icon={PercentIcon}
        />
        <StatTile
          label="P/B"
          value={formatMultiple(valuation?.pbRatio)}
          hint={valuation ? `Book value ${formatCompact(valuation.bookValue, currency)}` : "Needs price data"}
          icon={ScaleIcon}
          tone="muted"
        />
        <StatTile
          label="EV / operating profit"
          value={formatMultiple(valuation?.evOp)}
          hint={valuation ? `EV ${formatCompact(valuation.enterpriseValue, currency)}` : "Needs price data"}
          icon={ScaleIcon}
          tone="muted"
        />
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-12">
        {/* Revenue and profit */}
        <Card className="lg:col-span-8">
          <CardHeader>
            <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
              <div className="space-y-1">
                <CardTitle>Revenue and profit</CardTitle>
                <CardDescription>
                  As reported per period ({currency ?? "company currency"}); H1 and 9M are year-to-date.
                </CardDescription>
              </div>
              <PeriodTypeFilter value={chartType} types={periodTypes} onChange={setChartType} />
            </div>
          </CardHeader>
          <CardContent>
            {financialData.length === 0 ? (
              <EmptyState variant="chart" title="No income statements" description="No income statement is stored for this period type." />
            ) : (
              <ChartContainer config={financialsConfig} className="aspect-auto h-[300px] w-full">
                <BarChart data={financialData} margin={{ left: 4, right: 4 }}>
                  <CartesianGrid vertical={false} strokeDasharray="3 3" />
                  <XAxis dataKey="period" tickLine={false} axisLine={false} tick={{ fontSize: 11 }} />
                  <YAxis
                    tickLine={false}
                    axisLine={false}
                    width={56}
                    tick={{ fontSize: 11 }}
                    tickFormatter={(v: number) => formatCompact(v)}
                  />
                  <ChartTooltip
                    content={<ChartTooltipContent formatter={(value, name) => (
                      <div className="flex w-full justify-between gap-4">
                        <span className="text-muted-foreground">
                          {financialsConfig[name as keyof typeof financialsConfig]?.label ?? name}
                        </span>
                        <span className="font-mono font-medium tabular-nums">{formatCompact(Number(value), currency)}</span>
                      </div>
                    )} />}
                  />
                  <ChartLegend content={<ChartLegendContent />} />
                  <Bar dataKey="revenue" fill="var(--color-revenue)" radius={[4, 4, 0, 0]} />
                  <Bar dataKey="grossProfit" fill="var(--color-grossProfit)" radius={[4, 4, 0, 0]} />
                  <Bar dataKey="netIncomeToParent" fill="var(--color-netIncomeToParent)" radius={[4, 4, 0, 0]} />
                </BarChart>
              </ChartContainer>
            )}
          </CardContent>
        </Card>

        {/* Data coverage */}
        <Card className="lg:col-span-4">
          <CardHeader>
            <CardTitle>Data in Neraca Lab</CardTitle>
            <CardDescription>What is stored for {company.ticker}</CardDescription>
          </CardHeader>
          <CardContent>
            <dl className="divide-y text-sm">
              {coverageRows.map(([label, value]) => (
                <div key={label} className="flex items-center justify-between gap-4 py-1.5">
                  <dt className="text-muted-foreground">{label}</dt>
                  <dd className="text-right font-medium tabular-nums">{value}</dd>
                </div>
              ))}
            </dl>
          </CardContent>
        </Card>
      </div>

      {/* Valuation history */}
      <Card>
        <CardHeader>
          <CardTitle>Valuation multiples</CardTitle>
          <CardDescription>
            Price against trailing-twelve-month fundamentals at each valuation date.
          </CardDescription>
        </CardHeader>
        <CardContent>
          {valuationData.length === 0 ? (
            <EmptyState
              variant="chart"
              title="No valuations yet"
              description="Valuations need daily prices and share counts; none are stored for this company yet."
            />
          ) : (
            <ChartContainer config={valuationConfig} className="aspect-auto h-[260px] w-full">
              <LineChart data={valuationData} margin={{ left: 4, right: 12 }}>
                <CartesianGrid vertical={false} strokeDasharray="3 3" />
                <XAxis
                  dataKey="date"
                  tickLine={false}
                  axisLine={false}
                  tick={{ fontSize: 11 }}
                  tickFormatter={(v: string) => formatDate(v)}
                />
                <YAxis
                  tickLine={false}
                  axisLine={false}
                  width={40}
                  tick={{ fontSize: 11 }}
                  tickFormatter={(v: number) => `${v}x`}
                />
                <ChartTooltip
                  content={<ChartTooltipContent
                    labelFormatter={(label) => formatDate(String(label))}
                    formatter={(value, name) => (
                      <div className="flex w-full justify-between gap-4">
                        <span className="text-muted-foreground">
                          {valuationConfig[name as keyof typeof valuationConfig]?.label ?? name}
                        </span>
                        <span className="font-mono font-medium tabular-nums">{formatMultiple(Number(value))}</span>
                      </div>
                    )}
                  />}
                />
                <ChartLegend content={<ChartLegendContent />} />
                <Line type="monotone" dataKey="peRatio" stroke="var(--color-peRatio)" strokeWidth={2} dot={{ r: 3 }} connectNulls />
                <Line type="monotone" dataKey="pbRatio" stroke="var(--color-pbRatio)" strokeWidth={2} dot={{ r: 3 }} connectNulls />
                <Line type="monotone" dataKey="evOp" stroke="var(--color-evOp)" strokeWidth={2} dot={{ r: 3 }} connectNulls />
              </LineChart>
            </ChartContainer>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
