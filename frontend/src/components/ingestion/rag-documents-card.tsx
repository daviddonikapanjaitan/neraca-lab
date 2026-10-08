"use client"

import { useState } from "react"
import { CircleAlertIcon, ExternalLinkIcon, LoaderIcon, SearchIcon } from "lucide-react"

import { EmptyState } from "@/components/empty-state"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { requestJson } from "@/lib/client-api"
import { EMPTY, formatDate, formatNumber, formatTimestamp } from "@/lib/format"
import type { RagDocument, RagHit, RagSourceType, RagStatus } from "@/lib/types"

/** Only http(s) links of stored news are rendered as links. */
function safeUrl(url: string | null): string | null {
  return url && /^https?:\/\//i.test(url) ? url : null
}

function pages(from: number | null, to: number | null): string | null {
  if (from === null) return null
  return to === null || to === from ? `page ${from}` : `pages ${from}-${to}`
}

/**
 * What the vector store holds for one source type (PDF or NEWS), newest first, and a retrieval test: the chunks
 * closest to a question, as the RAG answers will receive them.
 */
export function RagDocumentsCard({
  source,
  documents,
  status,
  limit,
}: {
  source: RagSourceType
  documents: RagDocument[]
  status: RagStatus
  /** documents loaded (the table notes when there may be more) */
  limit: number
}) {
  const [filter, setFilter] = useState("")
  const [query, setQuery] = useState("")
  const [searching, setSearching] = useState(false)
  const [hits, setHits] = useState<RagHit[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  const tickerFilter = filter.trim().toUpperCase()
  const shown = tickerFilter ? documents.filter((d) => d.ticker.startsWith(tickerFilter)) : documents
  const total = source === "PDF" ? status.stored.pdfDocuments : status.stored.newsDocuments
  const label = source === "PDF" ? "PDF documents" : "news articles"

  async function search(e: React.FormEvent) {
    e.preventDefault()
    const q = query.trim()
    if (!q || searching) return
    setSearching(true)
    setError(null)
    try {
      const params = new URLSearchParams({ q, source, limit: "5" })
      if (tickerFilter) params.set("ticker", tickerFilter)
      const { body } = await requestJson<RagHit[]>(`/api/rag/search?${params}`)
      setHits(body)
    } catch (err) {
      setHits(null)
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setSearching(false)
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Stored {label}</CardTitle>
        <CardDescription>
          Embedded with <span className="font-mono">{status.embeddingModel}</span> ({status.embeddingDimensions}{" "}
          dimensions) in chunks of about {formatNumber(status.chunkChars, 0)} characters. Each document is linked to
          its company.
        </CardDescription>
        <CardAction className="hidden sm:block">
          <Badge variant="outline" className="tabular-nums">
            {formatNumber(total, 0)} {label} · {formatNumber(status.stored.chunks, 0)} chunks in total
          </Badge>
        </CardAction>
      </CardHeader>
      <CardContent className="flex flex-col gap-4 px-0">
        <div className="flex flex-col gap-2 px-4">
          <form onSubmit={search} className="flex flex-col gap-2 sm:flex-row">
            <Input
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
              placeholder="Ticker (all)"
              aria-label="Filter by ticker"
              className="sm:w-32"
              maxLength={20}
            />
            <Input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder={
                source === "PDF" ? "Test a question, e.g. total revenue 2025" : "Test a question, e.g. dividend plans"
              }
              aria-label="Search the vector store"
              maxLength={2000}
            />
            <Button type="submit" variant="outline" disabled={!query.trim() || searching} className="shrink-0">
              {searching ? <LoaderIcon className="animate-spin" /> : <SearchIcon />}
              Search
            </Button>
          </form>
          <p className="text-xs text-muted-foreground">
            The ticker narrows the table and the search. Search returns the 5 closest chunks (cosine distance; lower
            is closer).
          </p>
          {error && (
            <p role="alert" className="flex items-start gap-2 text-xs text-destructive">
              <CircleAlertIcon className="mt-px size-3.5 shrink-0" />
              <span>{error}</span>
            </p>
          )}
          {hits && (
            <div className="flex flex-col gap-2">
              {hits.length === 0 && <p className="text-sm text-muted-foreground">No chunks stored for this filter.</p>}
              {hits.map((hit) => {
                const url = safeUrl(hit.sourceUrl)
                return (
                  <div key={hit.chunkId} className="rounded-lg border px-3 py-2">
                    <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground">
                      <span className="font-mono font-medium text-foreground">{hit.ticker}</span>
                      <span className="min-w-0 truncate">{hit.fileName ?? hit.title}</span>
                      {pages(hit.pageFrom, hit.pageTo) && <span>· {pages(hit.pageFrom, hit.pageTo)}</span>}
                      {hit.publishedAt && <span>· {formatDate(hit.publishedAt)}</span>}
                      <Badge variant="secondary" className="ml-auto tabular-nums">
                        distance {hit.distance.toFixed(3)}
                      </Badge>
                    </div>
                    <p className="mt-1 line-clamp-4 text-sm whitespace-pre-line">{hit.content}</p>
                    {url && (
                      <a
                        href={url}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="mt-1 inline-flex items-center gap-1 text-xs text-primary hover:underline"
                      >
                        Open the article <ExternalLinkIcon className="size-3" />
                      </a>
                    )}
                  </div>
                )
              })}
            </div>
          )}
        </div>

        {shown.length === 0 ? (
          <EmptyState
            variant="generic"
            title={tickerFilter ? `No ${label} for ${tickerFilter}` : `No ${label} stored yet`}
            description={
              source === "PDF"
                ? "Upload a PDF above. Its chunks will show here once the job is done."
                : "Ingest the news of a company above. The stored articles will show here."
            }
          />
        ) : (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="pl-4">Company</TableHead>
                  <TableHead>{source === "PDF" ? "Document" : "Article"}</TableHead>
                  <TableHead className="hidden md:table-cell">{source === "PDF" ? "Pages" : "Published"}</TableHead>
                  <TableHead className="text-right">Chunks</TableHead>
                  <TableHead className="hidden pr-4 text-right lg:table-cell">Stored</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {shown.map((doc) => {
                  const url = safeUrl(doc.sourceUrl)
                  return (
                    <TableRow key={doc.documentId}>
                      <TableCell className="pl-4">
                        <span className="font-mono font-medium">{doc.ticker}</span>
                        <div className="max-w-[180px] truncate text-xs text-muted-foreground">{doc.companyName}</div>
                      </TableCell>
                      <TableCell>
                        <div className="max-w-[420px]">
                          {url ? (
                            <a
                              href={url}
                              target="_blank"
                              rel="noopener noreferrer"
                              className="line-clamp-2 font-medium hover:underline"
                            >
                              {doc.title}
                            </a>
                          ) : (
                            <div className="truncate font-medium">{doc.fileName ?? doc.title}</div>
                          )}
                          <div className="truncate text-xs text-muted-foreground">
                            {doc.sourceName ?? EMPTY} · {formatNumber(doc.characters, 0)} characters
                          </div>
                        </div>
                      </TableCell>
                      <TableCell className="hidden md:table-cell tabular-nums">
                        {source === "PDF" ? formatNumber(doc.pages, 0) : formatDate(doc.publishedAt)}
                      </TableCell>
                      <TableCell className="text-right tabular-nums">{formatNumber(doc.chunks, 0)}</TableCell>
                      <TableCell className="hidden pr-4 text-right text-xs text-muted-foreground lg:table-cell">
                        {formatTimestamp(doc.updatedAt)}
                      </TableCell>
                    </TableRow>
                  )
                })}
              </TableBody>
            </Table>
            {documents.length >= limit && (
              <p className="px-4 pt-2 text-xs text-muted-foreground">Showing the {limit} most recently stored.</p>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  )
}
