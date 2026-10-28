"use client"

import { useEffect, useState } from "react"
import { CircleAlertIcon, LoaderIcon, UserSearchIcon } from "lucide-react"

import { OnePercentMovementBadge, TypeBadge } from "@/components/ownership-one-percent/one-percent-badges"
import { SharesChange, StatusBadge } from "@/components/ownership/ownership-badges"
import { Badge } from "@/components/ui/badge"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { requestJson } from "@/lib/client-api"
import { percentLabel, sharesLabel } from "@/lib/ownership"
import { monthLabel, type OnePercentEntity } from "@/lib/ownership-one-percent"

/**
 * The investors whose name contains the words searched (normalized as KSEI's names: "PT" / "Tbk" / titles and
 * punctuation ignored), each with every company it holds more than 1% of: shares and percentage at the latest month it
 * is listed, and its change since the first stored month.
 */
export function EntityOnePercent({ query, onTicker }: { query: string; onTicker: (ticker: string) => void }) {
  const [entities, setEntities] = useState<OnePercentEntity[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    requestJson<OnePercentEntity[]>(`/api/ownership/one-percent/entities?${new URLSearchParams({ q: query })}`)
      .then(({ body }) => !cancelled && setEntities(body))
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : String(e)))
    return () => {
      cancelled = true
    }
  }, [query])

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <UserSearchIcon className="size-4 text-primary" />
          Investors matching &ldquo;{query}&rdquo;
        </CardTitle>
        <CardDescription>
          Every company each investor holds more than 1% of, at the latest month end it is listed. Click a ticker for
          its shareholders.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {error && (
          <p role="alert" className="flex items-start gap-2 text-xs text-destructive">
            <CircleAlertIcon className="mt-px size-3.5 shrink-0" />
            <span>{error}</span>
          </p>
        )}
        {!entities ? (
          !error && (
            <p className="flex items-center gap-2 text-sm text-muted-foreground">
              <LoaderIcon className="size-3.5 animate-spin" /> Looking for the investor
            </p>
          )
        ) : entities.length === 0 ? (
          <p className="text-sm text-muted-foreground">No investor of the stored lists has this name.</p>
        ) : (
          entities.map((e) => {
            const total = e.holdings.filter((h) => h.listed).length
            return (
              <div key={e.holderKey} className="flex flex-col gap-2">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-medium">{e.name}</span>
                  <TypeBadge type={e.investorType} group={e.typeGroup} />
                  <StatusBadge status={e.status} />
                  {e.domicile && <span className="text-xs text-muted-foreground">{e.domicile}</span>}
                  <Badge variant="outline" className="ml-auto">
                    {e.holdings.length} {e.holdings.length === 1 ? "company" : "companies"}
                    {total !== e.holdings.length ? ` · ${total} in the latest list` : ""}
                  </Badge>
                </div>
                <div className="overflow-x-auto rounded-lg border">
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead className="pl-3">Company</TableHead>
                        <TableHead className="text-right">Shares</TableHead>
                        <TableHead className="text-right">% listed</TableHead>
                        <TableHead>Latest</TableHead>
                        <TableHead className="pr-3 text-right">Since first list</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {e.holdings.map((h) => (
                        <TableRow key={h.ticker}>
                          <TableCell className="pl-3">
                            <button
                              type="button"
                              onClick={() => onTicker(h.ticker)}
                              className="font-mono font-medium hover:underline"
                            >
                              {h.ticker}
                            </button>
                            <div className="max-w-[220px] truncate text-xs text-muted-foreground">{h.issuerName}</div>
                          </TableCell>
                          <TableCell className="text-right tabular-nums">{sharesLabel(h.shares)}</TableCell>
                          <TableCell className="text-right tabular-nums">{percentLabel(h.percent)}</TableCell>
                          <TableCell>
                            <div className="flex flex-wrap items-center gap-1">
                              <OnePercentMovementBadge movement={h.lastMovement} />
                              {!h.listed && (
                                <Badge variant="outline" className="text-amber-700 dark:text-amber-300">
                                  last listed {monthLabel(h.lastDate)}
                                </Badge>
                              )}
                            </div>
                          </TableCell>
                          <TableCell className="pr-3 text-right text-xs tabular-nums">
                            <SharesChange value={h.change} />
                            <div className="text-muted-foreground">since {monthLabel(h.firstDate)}</div>
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </div>
              </div>
            )
          })
        )}
      </CardContent>
    </Card>
  )
}
