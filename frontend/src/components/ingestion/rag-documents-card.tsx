"use client"

import { useEffect, useRef, useState } from "react"
import { CircleAlertIcon, ExternalLinkIcon, LoaderIcon, SearchIcon } from "lucide-react"

import { EmptyState } from "@/components/empty-state"
import { pageCount, TablePagination } from "@/components/table-pagination"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { requestJson } from "@/lib/client-api"
import { EMPTY, formatDate, formatNumber, formatTimestamp } from "@/lib/format"
import { DEFAULT_TABLE_PAGE_SIZE, TABLE_PAGE_SIZES } from "@/lib/ingestion"
import type { RagDocumentPage, RagHit, RagSourceType, RagStatus } from "@/lib/types"
import { cn } from "@/lib/utils"

/** Only http(s) links of stored news are rendered as links. */
function safeUrl(url: string | null): string | null {
  return url && /^https?:\/\//i.test(url) ? url : null
}

function pages(from: number | null, to: number | null): string | null {
  if (from === null) return null
  return to === null || to === from ? `page ${from}` : `pages ${from}-${to}`
}

/** The beginning of a ticker: letters, digits, '.' or '-', starting with a letter or digit (as the backend checks). */
const TICKER_PREFIX = /^[A-Z0-9][A-Z0-9.-]{0,19}$/

/** Wait after the last keystroke in the ticker box before the table is filtered. */
const FILTER_DELAY_MS = 300

/** Query of one page of stored documents (pages are 1-based). */
function query(source: RagSourceType, prefix: string, page: number, pageSize: number): string {
  const params = new URLSearchParams({ source, limit: String(pageSize), offset: String((page - 1) * pageSize) })
  if (prefix) params.set("tickerPrefix", prefix)
  return params.toString()
}

/**
 * What the vector store holds for one source type (PDF or NEWS), newest first and page by page (10 rows, or
 * 5 / 20 / 50, as the jobs table), and a retrieval test: the chunks closest to a question, as the RAG answers will
 * receive them. The ticker box narrows the table to the tickers starting with it.
 */
export function RagDocumentsCard({
  source,
  initial,
  status,
}: {
  source: RagSourceType
  /** first page of the stored documents, {@code DEFAULT_TABLE_PAGE_SIZE} rows (from the server; new after a job) */
  initial: RagDocumentPage
  status: RagStatus
}) {
  const [list, setList] = useState(initial)
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(DEFAULT_TABLE_PAGE_SIZE)
  const [filter, setFilter] = useState("")
  /** ticker prefix the table is filtered by (the ticker box, once typing pauses) */
  const [prefix, setPrefix] = useState("")
  /** query of the documents shown; differs from the chosen filter and page while they load */
  const [loadedQuery, setLoadedQuery] = useState(() => query(source, "", 1, DEFAULT_TABLE_PAGE_SIZE))
  const [listError, setListError] = useState<string | null>(null)
  const [question, setQuestion] = useState("")
  const [searching, setSearching] = useState(false)
  const [hits, setHits] = useState<RagHit[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  /** the query and server data shown: the page is fetched again when either changes */
  const shown = useRef({ query: query(source, "", 1, DEFAULT_TABLE_PAGE_SIZE), initial })

  const tickerFilter = filter.trim().toUpperCase()
  const filterValid = tickerFilter === "" || TICKER_PREFIX.test(tickerFilter)
  const storedTotal = source === "PDF" ? status.stored.pdfDocuments : status.stored.newsDocuments
  const label = source === "PDF" ? "PDF documents" : "news articles"
  const current = query(source, prefix, page, pageSize)
  const loading = loadedQuery !== current

  // the ticker box filters the table once typing pauses, from the first page
  useEffect(() => {
    if (!filterValid || tickerFilter === prefix) return
    const timer = setTimeout(() => {
      setPrefix(tickerFilter)
      setPage(1)
    }, FILTER_DELAY_MS)
    return () => clearTimeout(timer)
  }, [tickerFilter, filterValid, prefix])

  // the chosen page, and the current page again when a finished job brought new server data
  useEffect(() => {
    if (shown.current.query === current && shown.current.initial === initial) return
    let cancelled = false

    async function load() {
      try {
        const { body } = await requestJson<RagDocumentPage>(`/api/rag/documents?${current}`)
        if (cancelled) return
        const last = pageCount(body.total, pageSize)
        if (page > last) {
          // the page no longer exists (fewer documents than when it was chosen): show the last one
          setPage(last)
          return
        }
        shown.current = { query: current, initial }
        setList(body)
        setLoadedQuery(current)
        setListError(null)
      } catch (e) {
        if (cancelled) return
        setListError(e instanceof Error ? e.message : String(e))
      }
    }

    load()
    return () => {
      cancelled = true
    }
  }, [current, initial, page, pageSize])

  function changePageSize(value: number) {
    setPageSize(value)
    setPage(1)
  }

  async function search(e: React.FormEvent) {
    e.preventDefault()
    const q = question.trim()
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
        <CardTitle className="flex items-center gap-2">
          Stored {label}
          {loading && !listError && <LoaderIcon className="size-3.5 animate-spin text-muted-foreground" />}
        </CardTitle>
        <CardDescription>
          Embedded with <span className="font-mono">{status.embeddingModel}</span> ({status.embeddingDimensions}{" "}
          dimensions) in chunks of about {formatNumber(status.chunkChars, 0)} characters. Each document is linked to
          its company.
        </CardDescription>
        <CardAction className="hidden sm:block">
          <Badge variant="outline" className="tabular-nums">
            {formatNumber(storedTotal, 0)} {label} · {formatNumber(status.stored.chunks, 0)} chunks in total
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
              aria-invalid={!filterValid}
              className="sm:w-32"
              maxLength={20}
            />
            <Input
              value={question}
              onChange={(e) => setQuestion(e.target.value)}
              placeholder={
                source === "PDF" ? "Test a question, e.g. total revenue 2025" : "Test a question, e.g. dividend plans"
              }
              aria-label="Search the vector store"
              maxLength={2000}
            />
            <Button type="submit" variant="outline" disabled={!question.trim() || searching} className="shrink-0">
              {searching ? <LoaderIcon className="animate-spin" /> : <SearchIcon />}
              Search
            </Button>
          </form>
          <p className="text-xs text-muted-foreground">
            The ticker narrows the table (tickers starting with it) and the search (that ticker). Search returns the
            5 closest chunks (cosine distance; lower is closer).
          </p>
          {!filterValid && (
            <p role="alert" className="flex items-start gap-2 text-xs text-destructive">
              <CircleAlertIcon className="mt-px size-3.5 shrink-0" />
              <span>A ticker has only letters, digits, &apos;.&apos; or &apos;-&apos;.</span>
            </p>
          )}
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

        {listError && (
          <p role="alert" className="flex items-start gap-2 px-4 text-xs text-destructive">
            <CircleAlertIcon className="mt-px size-3.5 shrink-0" />
            <span>Cannot load the stored {label}: {listError}</span>
          </p>
        )}

        <div className={cn("flex flex-col gap-3 transition-opacity", loading && "opacity-60")}>
          {list.documents.length === 0 ? (
            <EmptyState
              variant="generic"
              title={prefix ? `No ${label} for ${prefix}` : `No ${label} stored yet`}
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
                  {list.documents.map((doc) => {
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
            </div>
          )}

          {list.total > 0 && (
            <div className="px-4">
              <TablePagination
                page={page}
                pageSize={pageSize}
                pageSizes={TABLE_PAGE_SIZES}
                total={list.total}
                noun={label}
                disabled={loading && !listError}
                onPageChange={setPage}
                onPageSizeChange={changePageSize}
              />
            </div>
          )}
        </div>
      </CardContent>
    </Card>
  )
}
