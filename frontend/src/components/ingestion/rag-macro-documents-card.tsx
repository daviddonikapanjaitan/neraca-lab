"use client"

import { useEffect, useRef, useState } from "react"
import { CircleAlertIcon, LoaderIcon, SearchIcon } from "lucide-react"

import { EmptyState } from "@/components/empty-state"
import { pageCount, TablePagination } from "@/components/table-pagination"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { requestJson } from "@/lib/client-api"
import { EMPTY, formatNumber, formatTimestamp } from "@/lib/format"
import { DEFAULT_TABLE_PAGE_SIZE, TABLE_PAGE_SIZES } from "@/lib/ingestion"
import type { MacroDocumentType, MacroRagDocumentPage, MacroRagHit, RagStatus } from "@/lib/types"
import { cn } from "@/lib/utils"

function pages(from: number | null, to: number | null): string | null {
  if (from === null) return null
  return to === null || to === from ? `page ${from}` : `pages ${from}-${to}`
}

/** The publication date (stored as 00:00 Jakarta time) as yyyy-mm-dd. */
function publishedOn(instant: string): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Jakarta" }).format(new Date(instant))
}

/** Query of one page of stored macro documents of a country (pages are 1-based). */
function query(country: string, page: number, pageSize: number): string {
  return new URLSearchParams({ country, limit: String(pageSize), offset: String((page - 1) * pageSize) }).toString()
}

/**
 * The macro documents of a country in the vector store, latest publication first and page by page (10 rows, or
 * 5 / 20 / 50, as the jobs table), and a retrieval test: the chunks closest to a question, as the research agent of
 * Macro › AI Analysis receives them.
 */
export function RagMacroDocumentsCard({
  initial,
  initialCountry,
  status,
}: {
  /** first page of the stored documents of {@code initialCountry} (from the server; new after a job) */
  initial: MacroRagDocumentPage
  initialCountry: string
  status: RagStatus
}) {
  const [country, setCountry] = useState(initialCountry)
  const [list, setList] = useState(initial)
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(DEFAULT_TABLE_PAGE_SIZE)
  /** query of the documents shown; differs from the chosen country and page while they load */
  const [loadedQuery, setLoadedQuery] = useState(() => query(initialCountry, 1, DEFAULT_TABLE_PAGE_SIZE))
  const [listError, setListError] = useState<string | null>(null)
  const [question, setQuestion] = useState("")
  const [searching, setSearching] = useState(false)
  const [hits, setHits] = useState<MacroRagHit[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  /** the query and server data shown: the page is fetched again when either changes */
  const shown = useRef({ query: query(initialCountry, 1, DEFAULT_TABLE_PAGE_SIZE), initial })

  const typeLabel = (code: MacroDocumentType) =>
    status.macroDocumentTypes.find((t) => t.code === code)?.label ?? code
  const countryName = status.macroCountries.find((c) => c.code === country)?.name ?? country
  const current = query(country, page, pageSize)
  const loading = loadedQuery !== current

  // the chosen country and page, and the current page again when a finished job brought new server data
  useEffect(() => {
    if (shown.current.query === current && shown.current.initial === initial) return
    let cancelled = false

    async function load() {
      try {
        const { body } = await requestJson<MacroRagDocumentPage>(`/api/rag/macro/documents?${current}`)
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
      const params = new URLSearchParams({ q, country, limit: "5" })
      const { body } = await requestJson<MacroRagHit[]>(`/api/rag/macro/search?${params}`)
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
          Stored macro documents
          {loading && !listError && <LoaderIcon className="size-3.5 animate-spin text-muted-foreground" />}
        </CardTitle>
        <CardDescription>
          Macro PDFs of a country, embedded with <span className="font-mono">{status.embeddingModel}</span>. Each is
          linked to its country, not to a company; the AI macro analysis of that country searches them.
        </CardDescription>
        <CardAction className="hidden sm:block">
          <Badge variant="outline" className="tabular-nums">
            {formatNumber(status.stored.macroDocuments, 0)} macro documents
          </Badge>
        </CardAction>
      </CardHeader>
      <CardContent className="flex flex-col gap-4 px-0">
        <div className="flex flex-col gap-2 px-4">
          <form onSubmit={search} className="flex flex-col gap-2 sm:flex-row">
            <Select
              items={status.macroCountries.map((c) => ({ value: c.code, label: c.name }))}
              value={country}
              onValueChange={(v) => {
                if (v && v !== country) {
                  setCountry(v)
                  setPage(1)
                  setHits(null)
                }
              }}
            >
              <SelectTrigger aria-label="Country" className="sm:w-44">
                <SelectValue placeholder="Country" />
              </SelectTrigger>
              <SelectContent>
                {status.macroCountries.map((c) => (
                  <SelectItem key={c.code} value={c.code}>
                    {c.name}
                    <span className="ml-1 text-xs text-muted-foreground tabular-nums">({c.documents})</span>
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <Input
              value={question}
              onChange={(e) => setQuestion(e.target.value)}
              placeholder="Test a question, e.g. Bank Indonesia guidance on the rupiah"
              aria-label="Search the macro documents"
              maxLength={2000}
            />
            <Button type="submit" variant="outline" disabled={!question.trim() || searching} className="shrink-0">
              {searching ? <LoaderIcon className="animate-spin" /> : <SearchIcon />}
              Search
            </Button>
          </form>
          <p className="text-xs text-muted-foreground">
            The country narrows the table and the search. Search returns the 5 closest chunks of that country&apos;s
            documents (cosine distance; lower is closer).
          </p>
          {error && (
            <p role="alert" className="flex items-start gap-2 text-xs text-destructive">
              <CircleAlertIcon className="mt-px size-3.5 shrink-0" />
              <span>{error}</span>
            </p>
          )}
          {hits && (
            <div className="flex flex-col gap-2">
              {hits.length === 0 && (
                <p className="text-sm text-muted-foreground">No macro documents of {countryName} are stored.</p>
              )}
              {hits.map((hit) => (
                <div key={hit.chunkId} className="rounded-lg border px-3 py-2">
                  <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground">
                    <span className="font-medium text-foreground">{hit.issuer ?? countryName}</span>
                    <span className="min-w-0 truncate">{hit.title}</span>
                    {pages(hit.pageFrom, hit.pageTo) && <span>· {pages(hit.pageFrom, hit.pageTo)}</span>}
                    <span>· {publishedOn(hit.publishedAt)}</span>
                    <Badge variant="secondary" className="ml-auto tabular-nums">
                      distance {hit.distance.toFixed(3)}
                    </Badge>
                  </div>
                  <p className="mt-1 line-clamp-4 text-sm whitespace-pre-line">{hit.content}</p>
                </div>
              ))}
            </div>
          )}
        </div>

        {listError && (
          <p role="alert" className="flex items-start gap-2 px-4 text-xs text-destructive">
            <CircleAlertIcon className="mt-px size-3.5 shrink-0" />
            <span>Cannot load the stored macro documents: {listError}</span>
          </p>
        )}

        <div className={cn("flex flex-col gap-3 transition-opacity", loading && "opacity-60")}>
          {list.documents.length === 0 ? (
            <EmptyState
              variant="generic"
              title={`No macro documents of ${countryName} stored yet`}
              description="Upload a macro PDF above. Its chunks will show here once the job is done."
            />
          ) : (
            <div className="overflow-x-auto">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead className="pl-4">Published</TableHead>
                    <TableHead>Document</TableHead>
                    <TableHead className="hidden md:table-cell">Pages</TableHead>
                    <TableHead className="text-right">Chunks</TableHead>
                    <TableHead className="hidden pr-4 text-right lg:table-cell">Stored</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {list.documents.map((doc) => (
                    <TableRow key={doc.documentId}>
                      <TableCell className="pl-4 whitespace-nowrap tabular-nums">{publishedOn(doc.publishedAt)}</TableCell>
                      <TableCell>
                        <div className="max-w-[420px]">
                          <div className="truncate font-medium">{doc.title}</div>
                          <div className="truncate text-xs text-muted-foreground">
                            {doc.issuer ?? EMPTY} · {typeLabel(doc.documentType)} · {doc.fileName ?? EMPTY}
                          </div>
                        </div>
                      </TableCell>
                      <TableCell className="hidden md:table-cell tabular-nums">{formatNumber(doc.pages, 0)}</TableCell>
                      <TableCell className="text-right tabular-nums">{formatNumber(doc.chunks, 0)}</TableCell>
                      <TableCell className="hidden pr-4 text-right text-xs text-muted-foreground lg:table-cell">
                        {formatTimestamp(doc.updatedAt)}
                      </TableCell>
                    </TableRow>
                  ))}
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
                noun="macro documents"
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
