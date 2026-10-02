import { Badge } from "@/components/ui/badge"
import { Card, CardContent } from "@/components/ui/card"
import { EMPTY, formatCompact, formatDate, formatNumber } from "@/lib/format"
import type { CompanyDetail } from "@/lib/types"

export function CompanyHeader({ detail }: { detail: CompanyDetail }) {
  const { company, latestPrice, latestMarketSnapshot } = detail

  return (
    <Card>
      <CardContent className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
        <div className="flex min-w-0 items-start gap-4">
          <div className="flex size-14 shrink-0 items-center justify-center rounded-2xl bg-primary text-lg font-bold text-primary-foreground">
            {company.ticker.slice(0, 2)}
          </div>
          <div className="min-w-0 space-y-1.5">
            <div className="flex flex-wrap items-center gap-2">
              <h1 className="text-xl font-semibold tracking-tight">{company.companyName}</h1>
              <Badge variant="outline" className="font-mono">
                {company.exchange}: {company.ticker}
              </Badge>
              {!company.active && <Badge variant="destructive">Inactive</Badge>}
            </div>
            <p className="truncate text-sm text-muted-foreground">
              {company.legalName ?? company.companyName} · {company.exchangeName}
            </p>
            <div className="flex flex-wrap gap-1.5">
              {company.sector && <Badge variant="secondary">{company.sector}</Badge>}
              {company.industry && <Badge variant="secondary">{company.industry}</Badge>}
              {company.country && <Badge variant="ghost">{company.country}</Badge>}
              {company.currency && <Badge variant="ghost">Reports in {company.currency}</Badge>}
              {company.fiscalYearEnd && (
                <Badge variant="ghost">Fiscal year end {formatDate(company.fiscalYearEnd)}</Badge>
              )}
            </div>
          </div>
        </div>

        <div className="flex shrink-0 gap-6 border-t pt-4 lg:border-t-0 lg:border-l lg:pt-0 lg:pl-6">
          <div>
            <p className="text-xs text-muted-foreground">Last close</p>
            <p className="text-2xl font-semibold tracking-tight tabular-nums">
              {latestPrice ? formatNumber(latestPrice.closePrice, 2) : EMPTY}
            </p>
            <p className="text-[11px] text-muted-foreground">
              {latestPrice ? `${company.currency ?? ""} · ${formatDate(latestPrice.tradingDate)}` : "No price data"}
            </p>
          </div>
          <div>
            <p className="text-xs text-muted-foreground">Market cap</p>
            <p className="text-2xl font-semibold tracking-tight tabular-nums">
              {formatCompact(latestMarketSnapshot?.marketCap)}
            </p>
            <p className="text-[11px] text-muted-foreground">
              {latestMarketSnapshot ? `${company.currency ?? ""} · ${formatDate(latestMarketSnapshot.snapshotDate)}` : "No market data"}
            </p>
          </div>
        </div>
      </CardContent>
    </Card>
  )
}
