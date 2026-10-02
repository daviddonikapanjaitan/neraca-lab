"use client"

import { EmptyState } from "@/components/empty-state"
import { Badge } from "@/components/ui/badge"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { EMPTY, formatCompact, formatDate, formatNumber, titleCase } from "@/lib/format"
import type { CompanyDetail } from "@/lib/types"

function Fact({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center justify-between gap-4 py-1.5">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="text-right font-medium tabular-nums">{value}</dd>
    </div>
  )
}

export function MarketPanel({ detail }: { detail: CompanyDetail }) {
  const { latestPrice, latestMarketSnapshot, shareSnapshots, corporateActions, coverage, company } = detail
  const currency = company.currency ?? undefined

  return (
    <div className="flex flex-col gap-4">
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>Latest price</CardTitle>
            <CardDescription>
              {latestPrice
                ? `${formatDate(latestPrice.tradingDate)} · ${formatNumber(coverage.priceDays, 0)} trading days since ${formatDate(coverage.firstPriceDate)}`
                : "No daily prices stored"}
            </CardDescription>
          </CardHeader>
          <CardContent>
            {latestPrice ? (
              <dl className="divide-y text-sm">
                <Fact label="Open" value={formatNumber(latestPrice.openPrice, 2)} />
                <Fact label="High" value={formatNumber(latestPrice.highPrice, 2)} />
                <Fact label="Low" value={formatNumber(latestPrice.lowPrice, 2)} />
                <Fact label="Close" value={formatNumber(latestPrice.closePrice, 2)} />
                <Fact label="Adjusted close" value={formatNumber(latestPrice.adjustedClose, 2)} />
                <Fact label="Volume" value={formatNumber(latestPrice.volume, 0)} />
              </dl>
            ) : (
              <EmptyState variant="chart" title="No prices" description="Daily prices are loaded separately from the filings." className="py-8" />
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Market value</CardTitle>
            <CardDescription>
              {latestMarketSnapshot ? formatDate(latestMarketSnapshot.snapshotDate) : "No market snapshot stored"}
            </CardDescription>
          </CardHeader>
          <CardContent>
            {latestMarketSnapshot ? (
              <dl className="divide-y text-sm">
                <Fact label="Share price" value={formatNumber(latestMarketSnapshot.sharePrice, 2)} />
                <Fact label="Shares outstanding" value={formatNumber(latestMarketSnapshot.sharesOutstanding, 0)} />
                <Fact label="Market cap" value={formatCompact(latestMarketSnapshot.marketCap, currency)} />
                <Fact label="Enterprise value" value={formatCompact(latestMarketSnapshot.enterpriseValue, currency)} />
              </dl>
            ) : (
              <EmptyState variant="chart" title="No market data" description="Market snapshots are derived from daily prices and share counts." className="py-8" />
            )}
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Share counts</CardTitle>
          <CardDescription>Shares at each date of the filings (most recent first)</CardDescription>
        </CardHeader>
        <CardContent className="px-0">
          {shareSnapshots.length === 0 ? (
            <EmptyState variant="generic" title="No share counts" description="No share snapshot is stored for this company." className="py-8" />
          ) : (
            <div className="overflow-x-auto">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead className="pl-4">Date</TableHead>
                    <TableHead className="text-right">Outstanding</TableHead>
                    <TableHead className="text-right">Weighted (basic)</TableHead>
                    <TableHead className="text-right">Weighted (diluted)</TableHead>
                    <TableHead className="text-right">Treasury</TableHead>
                    <TableHead className="pr-4 text-right">Public float</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {shareSnapshots.map((s) => (
                    <TableRow key={s.snapshotDate}>
                      <TableCell className="pl-4 tabular-nums">{formatDate(s.snapshotDate)}</TableCell>
                      <TableCell className="text-right tabular-nums">{formatNumber(s.sharesOutstanding, 0)}</TableCell>
                      <TableCell className="text-right tabular-nums">{formatNumber(s.basicShares, 0)}</TableCell>
                      <TableCell className="text-right tabular-nums">{formatNumber(s.dilutedShares, 0)}</TableCell>
                      <TableCell className="text-right tabular-nums">{formatNumber(s.treasuryShares, 0)}</TableCell>
                      <TableCell className="pr-4 text-right tabular-nums">{formatNumber(s.publicFloat, 0)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Corporate actions</CardTitle>
          <CardDescription>Splits, rights issues, dividends, buybacks and other actions</CardDescription>
        </CardHeader>
        <CardContent className="px-0">
          {corporateActions.length === 0 ? (
            <EmptyState variant="generic" title="No corporate actions" description="No corporate action is stored for this company." className="py-8" />
          ) : (
            <div className="overflow-x-auto">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead className="pl-4">Date</TableHead>
                    <TableHead>Type</TableHead>
                    <TableHead className="text-right">Ratio</TableHead>
                    <TableHead className="text-right">Shares issued</TableHead>
                    <TableHead className="text-right">Cash raised</TableHead>
                    <TableHead className="pr-4">Description</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {corporateActions.map((a, i) => (
                    <TableRow key={`${a.actionDate}-${a.actionType}-${i}`}>
                      <TableCell className="pl-4 tabular-nums">{formatDate(a.actionDate)}</TableCell>
                      <TableCell><Badge variant="secondary">{titleCase(a.actionType)}</Badge></TableCell>
                      <TableCell className="text-right tabular-nums">
                        {a.ratioFrom !== null && a.ratioTo !== null ? `${formatNumber(a.ratioFrom)} : ${formatNumber(a.ratioTo)}` : EMPTY}
                      </TableCell>
                      <TableCell className="text-right tabular-nums">{formatNumber(a.sharesIssued, 0)}</TableCell>
                      <TableCell className="text-right tabular-nums">{formatCompact(a.cashRaised, currency)}</TableCell>
                      <TableCell className="max-w-[360px] whitespace-normal pr-4 text-muted-foreground">{a.description ?? EMPTY}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
