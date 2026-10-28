"use client"

import { useEffect, useState } from "react"
import { CircleAlertIcon, LoaderIcon, UserSearchIcon } from "lucide-react"

import { MovementBadge, SharesChange, StatusBadge } from "@/components/ownership/ownership-badges"
import { Badge } from "@/components/ui/badge"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { requestJson } from "@/lib/client-api"
import { dayLabel, type OwnershipEntity, percentLabel, sharesLabel } from "@/lib/ownership"

/**
 * The investors whose name contains the words searched (normalized as KSEI's names: "PT" / "Tbk" and punctuation
 * ignored), each with every company it holds more than 5% of: shares and percentage on the latest day it is listed,
 * and its change since the first stored list.
 */
export function EntityHoldings({ query, onTicker }: { query: string; onTicker: (ticker: string) => void }) {
  const [entities, setEntities] = useState<OwnershipEntity[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    requestJson<OwnershipEntity[]>(`/api/ownership/entities?${new URLSearchParams({ q: query })}`)
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
          Every company each investor holds more than 5% of, on the latest day it is listed. Click a ticker for its
          shareholders.
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
          entities.map((e) => (
            <div key={e.holderKey} className="flex flex-col gap-2">
              <div className="flex flex-wrap items-center gap-2">
                <span className="font-medium">{e.name}</span>
                <StatusBadge status={e.status} />
                {e.domicile && <span className="text-xs text-muted-foreground">{e.domicile}</span>}
                <Badge variant="outline" className="ml-auto">
                  {e.holdings.length} {e.holdings.length === 1 ? "company" : "companies"}
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
                            <MovementBadge movement={h.lastMovement} />
                            {!h.listed && (
                              <Badge variant="outline" className="text-amber-700 dark:text-amber-300">
                                last listed {dayLabel(h.lastDate)}
                              </Badge>
                            )}
                          </div>
                          <div className="text-xs text-muted-foreground">
                            {h.accounts} {h.accounts === 1 ? "account" : "accounts"}
                          </div>
                        </TableCell>
                        <TableCell className="pr-3 text-right text-xs tabular-nums">
                          <SharesChange value={h.change} />
                          <div className="text-muted-foreground">since {dayLabel(h.firstDate)}</div>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>
            </div>
          ))
        )}
      </CardContent>
    </Card>
  )
}
