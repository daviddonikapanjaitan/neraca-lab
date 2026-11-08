import Link from "next/link"
import { CircleAlertIcon, InfoIcon } from "lucide-react"

import { EmptyState } from "@/components/empty-state"
import { Badge } from "@/components/ui/badge"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import {
  boardLabel,
  type CompanyProfile,
  DIVIDEND_SOURCE_LABELS,
  type DividendRow,
} from "@/lib/company-profile"
import { EMPTY, formatCompact, formatDate, formatNumber, formatPercent, formatPrice, formatTimestamp } from "@/lib/format"
import { cn } from "@/lib/utils"

function Field({ label, children, wide }: { label: string; children: React.ReactNode; wide?: boolean }) {
  return (
    <div className={cn("flex min-w-0 flex-col gap-0.5", wide && "sm:col-span-2 lg:col-span-3")}>
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="text-sm break-words">{children ?? EMPTY}</dd>
    </div>
  )
}

function Tile({ label, value, hint }: { label: string; value: string; hint: string }) {
  return (
    <div className="flex flex-col gap-0.5 rounded-xl border p-3">
      <span className="text-xs text-muted-foreground">{label}</span>
      <span className="text-xl font-semibold tracking-tight tabular-nums">{value}</span>
      <span className="text-[11px] text-muted-foreground">{hint}</span>
    </div>
  )
}

/** Amount per share: as many decimals as it needs (IDR 1,250; USD 0.0123). */
function perShare(value: number | null | undefined, currency: string | null | undefined): string {
  if (value === null || value === undefined) return EMPTY
  const digits = Math.abs(value) >= 100 ? 2 : Math.abs(value) >= 1 ? 4 : 6
  return `${currency ? `${currency} ` : ""}${formatNumber(value, digits)}`
}

function sourceBadge(row: DividendRow) {
  return (
    <Badge variant={row.source === "YAHOO" ? "ghost" : "secondary"} className="font-normal">
      {DIVIDEND_SOURCE_LABELS[row.source]}
    </Badge>
  )
}

/**
 * The Profile tab of a company: the IDX company profile (listing date, board, IDX-IC classification, business,
 * registrar) and the dividends - every cash dividend with its yield on the cum-date close, and per fiscal year the
 * payout ratio of the dividends paid (cash flow statement) and of those declared from the year's profit (IDX).
 */
export function ProfilePanel({ profile, error }: { profile: CompanyProfile | null; error: string | null }) {
  if (!profile) {
    return (
      <Card>
        <CardContent>
          <EmptyState variant="generic" title="The profile cannot be loaded" description={error ?? "Unknown error"} />
        </CardContent>
      </Card>
    )
  }
  const { profile: p, summary, dividends, years, currency, listingCurrency } = profile
  const classification = [p.idxSector, p.idxSubSector, p.idxIndustry, p.idxSubIndustry].filter(Boolean)
  const shownYears = years.slice(0, 12)

  return (
    <div className="flex flex-col gap-4">
      {profile.notes.length > 0 && (
        <div className="flex flex-col gap-1">
          {profile.notes.map((note) => (
            <p key={note} className="flex items-start gap-2 text-xs text-muted-foreground">
              <InfoIcon className="mt-px size-3.5 shrink-0" />
              <span>
                {note}
                {note.startsWith("No IDX company profile") && (
                  <>
                    {" "}
                    <Link href="/ingestion/screening-data" className="text-primary hover:underline">
                      Upload profiles
                    </Link>
                  </>
                )}
              </span>
            </p>
          ))}
        </div>
      )}

      <Card>
        <CardHeader>
          <CardTitle>Company profile</CardTitle>
          <CardDescription>
            From IDX&apos;s company profile{p.profileUpdatedAt ? ` (uploaded ${formatTimestamp(p.profileUpdatedAt)})` : ""};
            the sector of the company list is Yahoo Finance&apos;s.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <dl className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            <Field label="Listing date (IPO)">
              {p.listingDate ? (
                <>
                  {formatDate(p.listingDate)}
                  {p.yearsListed !== null && (
                    <span className="text-muted-foreground"> · {p.yearsListed} {p.yearsListed === 1 ? "year" : "years"} listed</span>
                  )}
                </>
              ) : (
                <span className="text-muted-foreground">Not known yet</span>
              )}
            </Field>
            <Field label="Listing board">{boardLabel(p.listingBoard)}</Field>
            <Field label="Name at IDX">{p.idxName ?? p.legalName}</Field>
            <Field label="IDX-IC classification" wide>
              {classification.length > 0 ? classification.join(" › ") : null}
            </Field>
            <Field label="Main business activity" wide>
              {p.businessActivity}
            </Field>
            <Field label="Sector · industry (Yahoo)">
              {[p.sector, p.industry].filter(Boolean).join(" · ") || null}
            </Field>
            <Field label="Share registrar">{p.shareRegistrar}</Field>
            <Field label="Fiscal year end">{p.fiscalYearEnd ? formatDate(p.fiscalYearEnd) : null}</Field>
            <Field label="First stored price">{p.firstPriceDate ? formatDate(p.firstPriceDate) : null}</Field>
            <Field label="Yahoo first trade date">
              {p.yahooFirstTradeDate ? formatDate(p.yahooFirstTradeDate) : null}
            </Field>
            <Field label="Country">{p.country}</Field>
          </dl>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Dividends</CardTitle>
          <CardDescription>
            Cash dividends from IDX&apos;s company profile and Yahoo Finance. Yield = dividend per share / the close of
            the cum date (the last trading day with the right to the dividend), both on the share basis of that date.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4 px-0">
          <div className="grid gap-3 px-4 sm:grid-cols-2 lg:grid-cols-4">
            <Tile
              label="Dividends, last 12 months"
              value={perShare(summary.trailingPerShare, listingCurrency)}
              hint={`${summary.trailingCount} ${summary.trailingCount === 1 ? "dividend" : "dividends"} with an ex date to ${formatDate(summary.priceDate)}, per share today`}
            />
            <Tile
              label="Trailing dividend yield"
              value={formatPercent(summary.trailingYield)}
              hint={`on the close of ${formatDate(summary.priceDate)}: ${listingCurrency} ${formatPrice(summary.price)}`}
            />
            <Tile
              label="Payout ratio, last 12 months"
              value={formatPercent(summary.trailingPayout, 1)}
              hint={
                summary.epsTtm !== null
                  ? `dividends per share / EPS TTM ${perShare(summary.epsTtm, listingCurrency)} (valuation ${formatDate(summary.epsDate)})`
                  : "no trailing EPS stored"
              }
            />
            <Tile
              label="Cash payout ratio"
              value={formatPercent(summary.latestPayout, 1)}
              hint={
                summary.latestPayoutYear
                  ? `FY${summary.latestPayoutYear}: dividends paid / profit to owners · ${summary.averagePayoutYears}-year mean ${formatPercent(summary.averagePayout, 1)}`
                  : "no fiscal year with dividends paid and a profit"
              }
            />
          </div>

          {dividends.length === 0 ? (
            <EmptyState
              variant="generic"
              title="No dividends stored"
              description="Neither IDX's profile nor Yahoo Finance lists a cash dividend of this company."
            />
          ) : (
            <div className="max-h-[480px] overflow-auto">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead className="pl-4">Ex date</TableHead>
                    <TableHead className="hidden md:table-cell">Cum date</TableHead>
                    <TableHead className="hidden lg:table-cell">Payment</TableHead>
                    <TableHead>Fiscal year</TableHead>
                    <TableHead>Source</TableHead>
                    <TableHead className="text-right">Per share</TableHead>
                    <TableHead className="hidden text-right sm:table-cell">Cum-date close</TableHead>
                    <TableHead className="pr-4 text-right">Yield</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {dividends.map((d, i) => (
                    <TableRow key={`${d.exDate ?? d.cumDate ?? d.paymentDate}-${d.source}-${i}`}>
                      <TableCell className="pl-4 whitespace-nowrap">{formatDate(d.exDate)}</TableCell>
                      <TableCell className="hidden whitespace-nowrap md:table-cell">{formatDate(d.cumDate)}</TableCell>
                      <TableCell className="hidden whitespace-nowrap lg:table-cell">{formatDate(d.paymentDate)}</TableCell>
                      <TableCell className="tabular-nums">
                        {d.fiscalYear ?? EMPTY}
                        {d.type && <div className="text-xs text-muted-foreground">{d.type}</div>}
                      </TableCell>
                      <TableCell>{sourceBadge(d)}</TableCell>
                      <TableCell className="text-right tabular-nums whitespace-nowrap">
                        {perShare(d.cashPerShare, d.currency)}
                        {d.currency !== listingCurrency && d.perShareListing !== null && (
                          <div className="text-xs text-muted-foreground">{perShare(d.perShareListing, listingCurrency)}</div>
                        )}
                        {d.splitFactor !== 1 && (
                          <div
                            className="text-xs text-muted-foreground"
                            title="Splits since: Yahoo's split-adjusted amount is shown on the share basis of the dividend's date"
                          >
                            {perShare(d.cashPerShare / d.splitFactor, d.currency)} today
                          </div>
                        )}
                      </TableCell>
                      <TableCell className="hidden text-right tabular-nums whitespace-nowrap sm:table-cell">
                        {d.price === null ? EMPTY : `${listingCurrency} ${formatPrice(d.price)}`}
                        {d.priceDate && d.priceDate !== d.cumDate && (
                          <div className="text-xs text-muted-foreground">{formatDate(d.priceDate)}</div>
                        )}
                      </TableCell>
                      <TableCell className="pr-4 text-right tabular-nums">{formatPercent(d.dividendYield)}</TableCell>
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
          <CardTitle>Payout ratio by fiscal year</CardTitle>
          <CardDescription>
            {currency}. Cash payout = dividends paid in the year&apos;s cash flow statement / profit to the owners of the
            year (dividends paid may include those a subsidiary paid to its minority shareholders). Declared payout
            (IDX) = the dividends declared from the year&apos;s profit (fiscal year of the dividend) / that profit.
          </CardDescription>
        </CardHeader>
        <CardContent className="px-0">
          {shownYears.length === 0 ? (
            <EmptyState
              variant="generic"
              title="No fiscal year stored"
              description="No annual (FY) income or cash flow statement of this company is stored."
            />
          ) : (
            <div className="overflow-x-auto">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead className="pl-4">Fiscal year</TableHead>
                    <TableHead className="text-right">Profit to owners</TableHead>
                    <TableHead className="hidden text-right md:table-cell">EPS</TableHead>
                    <TableHead className="text-right">Dividends paid</TableHead>
                    <TableHead className="text-right">Cash payout</TableHead>
                    <TableHead className="hidden text-right sm:table-cell">Declared (IDX)</TableHead>
                    <TableHead className="pr-4 text-right">Declared payout</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {shownYears.map((y) => (
                    <TableRow key={y.fiscalYear}>
                      <TableCell className="pl-4 font-medium tabular-nums">FY{y.fiscalYear}</TableCell>
                      <TableCell className="text-right tabular-nums">{formatCompact(y.netIncomeToParent)}</TableCell>
                      <TableCell className="hidden text-right tabular-nums md:table-cell">
                        {y.basicEps === null ? EMPTY : formatNumber(y.basicEps, Math.abs(y.basicEps) >= 1 ? 2 : 6)}
                      </TableCell>
                      <TableCell className="text-right tabular-nums">{formatCompact(y.dividendsPaid)}</TableCell>
                      <TableCell className="text-right tabular-nums">{formatPercent(y.cashPayout, 1)}</TableCell>
                      <TableCell className="hidden text-right tabular-nums sm:table-cell">
                        {y.declaredCount === 0 ? EMPTY : perShare(y.declaredPerShare, y.declaredCurrency)}
                        {y.declaredCount > 1 && (
                          <div className="text-xs text-muted-foreground">{y.declaredCount} dividends</div>
                        )}
                      </TableCell>
                      <TableCell className="pr-4 text-right tabular-nums">{formatPercent(y.declaredPayout, 1)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
          {summary.idxDividends === 0 && dividends.length > 0 && (
            <p className="flex items-start gap-2 px-4 pt-3 text-xs text-muted-foreground">
              <CircleAlertIcon className="mt-px size-3.5 shrink-0" />
              <span>
                The declared payout needs IDX&apos;s dividend history (fiscal year of each dividend): upload the IDX
                company profiles with their details.
              </span>
            </p>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
