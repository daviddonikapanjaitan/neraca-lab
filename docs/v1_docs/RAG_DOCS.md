# RAG vector store (PDF documents and news)

The RAG vector store holds text that the AI features can retrieve by meaning: PDF documents of a
company (financial statements, annual reports) and the company's news articles. Every document is
split into chunks; each chunk is embedded (a 1536-number vector) and stored in PostgreSQL with
**pgvector**, linked to its company by a foreign key to `company`.

Two pages under **Ingestion** fill it (permission `INGESTION`):

| Page                | Path                    | Job type   | Source                                                  |
|---------------------|-------------------------|------------|---------------------------------------------------------|
| PDF Documents (RAG) | `/ingestion/rag-pdf`    | `RAG_PDF`  | an uploaded `.pdf` of a stored company                  |
| News (RAG)          | `/ingestion/rag-news`   | `RAG_NEWS` | the news of a stored IDX company within a date range    |

Both run as background jobs in `ingestion_job` (shown in the jobs table of every Ingestion page);
each page also lists what is stored and has a search box that runs the same similarity search the
RAG answers will use.

## 1. Flow

```text
PDF  upload -> check (.pdf, %PDF header, text layer, company exists) -> ingestion_file (once per SHA-256)
            -> RAG_PDF job -> PDFBox text per page -> chunks (page range kept) -> embeddings
            -> rag_document (source_key = checksum) + rag_chunk (vector(1536))

News request (company, from, to) -> RAG_NEWS job
            -> headlines in the range: EmitenNews, Investor.id (older pages walked back),
               IDX Channel, Pasardana (latest only), Tavily (date-range search, when configured)
            -> skip URLs already stored for the company -> read each article (title, lead, body)
            -> chunks -> embeddings -> rag_document (source_key = URL) + rag_chunk
```

- **Chunking** (`TextChunker`): whitespace collapsed (PDF tables are full of padding), cut at line
  ends, else at word boundaries, into chunks of at most `chunk-chars` (1,500) characters; each chunk
  repeats the last `chunk-overlap` (200) characters of the previous one, so a sentence cut at a
  boundary is still whole in one chunk. A PDF chunk records its pages (`page_from`, `page_to`).
- **Embeddings** (`EmbeddingClient`): `POST {spring.ai.openai.base-url}/embeddings` (OpenRouter,
  OpenAI-compatible) with `openai/text-embedding-3-small`, 64 texts per request, retried twice on a
  timeout, network error, 408, 429 or 5xx. Every response is checked: one vector per text, in order,
  1536 dimensions. Cost: about USD 0.02 per million tokens; the HRTA FY2025 statement (107 pages,
  142 chunks) costs well under one cent.
- **Storing** (`RagRepository.store`): one transaction per document: the document row is upserted on
  `(company_id, source_type, source_key)` and its chunks are replaced, so a document never has half
  of its chunks and ingesting the same source again replaces it.
- **Search** (`RagRepository.search`): cosine distance (`<=>`) on the HNSW index, optionally
  narrowed to one company and one source type. `hnsw.iterative_scan = relaxed_order` keeps the
  index scan going until enough rows pass those filters; the hits are then sorted exactly.

## 2. PDF documents

- Only `.pdf` files with a text layer. A scanned PDF (images only), a password-protected or an
  unreadable file is rejected with 422 before anything is stored. Limit 20 MB (the upload limit).
- The company is chosen on the page; a file name ending with a stored ticker
  (`FinancialStatement-2025-Tahunan-HRTA.pdf`) selects it automatically.
- The file is stored once per SHA-256 checksum in `ingestion_file` (shared with the `.xlsx`
  uploads) and can be downloaded from the job (`GET /api/v1/ingestions/{id}/file`, served as
  `application/pdf`). Uploading the same file again for the same company replaces its chunks; the
  same file for another company is a separate document.
- At most one active job per file and company: a repeated upload while it is queued or running
  returns that job (200).

## 3. News

- Exchange IDX, a company stored in `company`, and a date range: presets *This month*, *Last 7
  days*, *Last 30 days*, *Previous month*, or a custom range. Dates are Jakarta time (WIB) and
  include both ends; the range is at most 366 days and may not end in the future.
- Sources and how far back they reach:

  | Source       | Pages read                                                          |
  |--------------|---------------------------------------------------------------------|
  | EmitenNews   | `/tag/<ticker>`, then `/tag/<ticker>/9`, `/18`, ... (9 per page)    |
  | Investor.id  | `/tag/<ticker>`, then `/tag/<ticker>/2`, `/3`, ...                  |
  | IDX Channel  | `/tag/<ticker>` only (older articles are loaded by script)          |
  | Pasardana    | the latest listings, kept when they name the ticker or the company  |
  | Tavily       | a news search with the range's start and end date (`TAVILY_API_KEY`), social media excluded, with the page text Tavily read |

  Even with `topic: news`, Tavily also returns stock quote and company profile pages (Yahoo Finance,
  ajaib, cermati, idx.co.id), topic / tag listings and social media posts; they carry a date (the
  crawl date) but no article. `NewsUrls` keeps only article URLs: social media (also sent as
  `exclude_domains`), listing / quote / profile paths (`/quote/`, `/saham/`, `/topic/`, `/tag/`,
  `/perusahaan-tercatat/`, ...; unless the URL has an article slug of four or more words) and pages
  named after the ticker (`.../ASGR`, `.../ASGR.JK`) are skipped and counted as `notArticles` of the
  source.

  EmitenNews and Investor.id are walked back page by page until a page reaches before the range
  start, a page is empty, or `news-max-pages` (10) pages were read. Older ranges therefore come
  mostly from these two sites.
- Headlines are de-duplicated by URL, sorted newest first; at most `news-max-articles` (60) are read
  per job. A headline without a date is kept until its article is read, then dated from the article
  (and dropped when it falls outside the range).
- A site that refuses our request (e.g. HTTP 403) or yields no article text: when Tavily read the
  page (at least 300 characters), that text is stored instead (`STORED_FROM_SEARCH_TEXT`); otherwise
  the article is `FAILED` / `NO_TEXT` and the job `INCOMPLETE`.
- An article already stored for the company is skipped (`ALREADY_STORED`), so running a range again
  only adds new articles. The text embedded is title + lead + body (up to 30,000 characters).
- The sites are read through the screening's polite HTTP client (one request at a time per site,
  with a pause), so a month of news takes a few minutes.
- Job outcome: `SUCCEEDED` (also when no news exists in the range), `INCOMPLETE` when articles
  could not be read or the article limit cut the list (the result lists them), `FAILED` when no
  source could be read or the embedding API fails. The result has per-source page / found /
  in-range counts and every article with its outcome.

## 4. API

All endpoints need a session with the `INGESTION` permission.

| Method | Path | Answer |
|--------|------|--------|
| `POST` | `/api/v1/rag/pdf` (multipart `file`, `exchange` = IDX, `ticker`) | 202 new job, 200 job already active; 400 not a `.pdf` / no `%PDF` header, 422 no text layer / protected / unreadable, 404 unknown company, 413 too large |
| `POST` | `/api/v1/rag/news?exchange=IDX&ticker=HRTA&from=2026-10-01&to=2026-10-08` | 202 / 200 as above; 400 start after end, end in the future, range over 366 days, bad date; 404 unknown company |
| `GET`  | `/api/v1/rag/status` | embedding model and dimensions, chunk size, news limits, Tavily on/off, queue length, stored documents and chunks |
| `GET`  | `/api/v1/rag/documents[?exchange&ticker&source=PDF\|NEWS&limit=100]` | stored documents, most recently stored first (limit 1-500) |
| `GET`  | `/api/v1/rag/search?q=...[&exchange=IDX&ticker&source&limit=8]` | closest chunks: ticker, document, pages, published date, content, `distance` (0 = same direction; limit 1-50, query up to 2,000 characters) |

Follow a job with `GET /api/v1/ingestions/{id}`; its `result` is the `PdfResult` (pages, pages with
text, characters, chunks, model) or the `NewsResult` described above.

```bash
curl -H "$AUTH" -F file=@data/HRTA/pdf/FinancialStatement-2025-Tahunan-HRTA.pdf -F ticker=HRTA \
     http://localhost:8080/api/v1/rag/pdf
curl -H "$AUTH" -X POST "http://localhost:8080/api/v1/rag/news?ticker=HRTA&from=2026-10-01&to=2026-10-08"
curl -H "$AUTH" "http://localhost:8080/api/v1/rag/search?ticker=HRTA&q=pendapatan%20penjualan%20emas%202025"
```

## 5. Database

`V1.0.14__schema_rag.sql` (needs the pgvector extension, see `DOCKER_DOCS.md`):

- `CREATE EXTENSION IF NOT EXISTS vector`; `ingestion_job` accepts the job types `RAG_PDF` and
  `RAG_NEWS` (a `RAG_PDF` job must have its file).
- `rag_document`: one row per PDF or article of a company (`company_id` -> `company`, `ON DELETE
  CASCADE`; `file_id` -> `ingestion_file` for a PDF), unique on `(company_id, source_type,
  source_key)`.
- `rag_chunk`: `chunk_index`, `page_from` / `page_to`, `content`, `embedding vector(1536)`;
  `company_id` is repeated for filtered search; HNSW index with `vector_cosine_ops`.

Column-level reference: `DB_SCHEMA_DOCS.md`.

## 6. Settings

`neracalab.rag` in `application.yaml`:

| Setting                | Default                          | Meaning |
|------------------------|----------------------------------|---------|
| `embedding-model`      | `openai/text-embedding-3-small` (`RAG_EMBEDDING_MODEL`) | embedding model, called with `OPENAI_API_KEY` at `OPENAI_BASE_URL` |
| `embedding-dimensions` | 1536                             | must match `rag_chunk.embedding vector(1536)`; another value fails every job |
| `embedding-batch`      | 64                               | chunks per embedding request |
| `chunk-chars`          | 1500                             | longest chunk (characters) |
| `chunk-overlap`        | 200                              | characters a chunk repeats from the previous one |
| `news-max-pages`       | 10                               | tag pages per site walked back |
| `news-max-articles`    | 60                               | articles read per news job |
| `article-chars`        | 30000                            | longest article text kept |

Changing the embedding model makes old and new vectors incomparable: ingest the documents again.

## 7. Code and tests

`backend/src/main/java/com/neracalab/backend/rag/`: `RagController` (API), `RagQueue` (one worker
thread, `SerialJobWorker`), `RagIngestionService`, `PdfText`, `TextChunker`, `EmbeddingClient`,
`NewsCollector`, `RagRepository`, `RagProperties`.

| Test | Covers |
|------|--------|
| `TextChunkerTest` | cleaning, size limit, overlap, page ranges, long lines, every word kept |
| `EmbeddingClientTest` | response parsing (order, count, dimensions, errors), missing key, vector text form |
| `PdfTextTest` | the HRTA FY2025 PDF page by page and its chunks; non-PDF and text-less PDF rejected |
| `NewsCollectorTest` | Jakarta-time range, walking older pages until the range start, Investor.id page numbers, Tavily results filtered to articles with their page text (saved pages / stub, no network) |
| `NewsUrlsTest` | the ASGR search results: articles kept; quote, profile, topic and social media pages dropped |
| `RagNewsFallbackTest` | an article whose site answers 403 is stored from the search text; without usable text it fails |
| `RagRepositoryTest` | store, cosine search with filters, replace on re-ingest, news keyed by URL, cascade (real database, rolled back) |
| `RagControllerTest` | a PDF through the worker into the store and back by search (stub embeddings, cleaned up), validation errors, status, permission |
| `IngestionJobTypeConstraintTest` | `V1.0.10` and `V1.0.14` re-create `ck_ingestion_job_type` on every start; both must list every job type (a narrower list stops the application once a RAG job is stored) |

Frontend: `src/app/(dashboard)/ingestion/rag-pdf`, `rag-news`, `src/components/ingestion/rag-*.tsx`,
`company-picker.tsx`, `src/lib/date-range.ts`, route handlers in `src/app/api/rag/`.
