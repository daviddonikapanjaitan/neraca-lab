# RAG vector store (PDF documents, news and macro documents)

The RAG vector store holds text that the AI features can retrieve by meaning: PDF documents of a
company (financial statements, annual reports), the company's news articles, and **macro documents
of a country** (central bank, finance ministry, IMF / World Bank reports). Every document is split
into chunks; each chunk is embedded (a 1536-number vector) and stored in PostgreSQL with
**pgvector**, linked either to its company (foreign key to `company`) or to its country.

Two pages under **Ingestion** fill it (permission `INGESTION`):

| Page                | Path                    | Job type   | Source                                                  |
|---------------------|-------------------------|------------|---------------------------------------------------------|
| PDF Documents (RAG) | `/ingestion/rag-pdf`    | `RAG_PDF`  | left: an uploaded `.pdf` of a stored company; right: a macro `.pdf` of a country (section 2a) |
| Excel Documents (RAG) | `/ingestion/rag-excel` | `RAG_XLSX` | an uploaded `.xlsx` of an exchange: KSEI's daily list of the shareholders of more than 5%, or its monthly list of the shareholders of more than 1% (IDX); per stored company one ownership document of each ([OWNERSHIP_DOCS.md](OWNERSHIP_DOCS.md), [OWNERSHIP_ONE_PERCENT_DOCS.md](OWNERSHIP_ONE_PERCENT_DOCS.md)) |
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
               IDX Channel, Pasardana (latest only)
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
  unreadable file is rejected with 422 before anything is stored. Limit 50 MB (the upload limit; macro reports such as the APBN KiTa bulletin are often over 20 MB).
- The company is chosen on the page; a file name ending with a stored ticker
  (`FinancialStatement-2025-Tahunan-HRTA.pdf`) selects it automatically.
- **Document type** (`CompanyDocumentType`, `rag_document.document_type`): `FINANCIAL_STATEMENT` (the default when
  not given, and the type every company PDF stored before was given by `V1.0.29`) or `REGISTRATION`, a monthly
  registration report of securities holders. A registration report is read at the upload (not one, or another
  company's by its Kode Emiten: 422 before anything is stored) and, after its chunks, its directors' and
  commissioners' shares are stored for Companies > Registration (`REGISTRATION_DOCS.md`). The same file uploaded
  again takes the type given now (a registration report uploaded again as a financial statement loses its
  report). A file name with "registrasi" / "registration" / "LBRE" uploaded as a Financial Statement gets a hint on
  the page.
- The file is stored once per SHA-256 checksum in `ingestion_file` (shared with the `.xlsx`
  uploads) and can be downloaded from the job (`GET /api/v1/ingestions/{id}/file`, served as
  `application/pdf`). Uploading the same file again for the same company replaces its chunks; the
  same file for another company is a separate document.
- At most one active job per file and company: a repeated upload while it is queued or running
  returns that job (200).

## 2a. Macro documents of a country

The right-hand card of **PDF Documents (RAG)** (**Macro document upload**) stores a macro PDF of a
country for the AI macro analysis (`MACRO_ANALYSIS_DOCS.md`, section 3a). Which documents to collect,
and why: `instruction/pdf_rag_macro.md`.
The files uploaded so far are kept locally (not in the repository: `data/` is in `.gitignore`) in
`data/macro/<country>/` (`data/macro/indonesia/`: Bank
Indonesia's Monetary Policy Review / Report, the International Investment Position report, the
Ministry of Finance's APBN KiTa bulletin and the Indonesia Stock Exchange's monthly market
statistics, `ms_equity` / `ms_bond` / `ms_sw` `_2609.pdf`).

- **Country**: Indonesia (`ID`). The United States and China are listed but not available yet; the
  API answers 400 ("United States is not available yet: only Indonesia (ID) for now"). The list is
  the Macro page's (`MacroOutlookService.COUNTRIES`).
- **Document type** (required, `MacroDocumentType`): `MONETARY_POLICY` (BI board statements,
  Monetary Policy Review / Report), `FINANCIAL_STABILITY`, `FISCAL` (APBN KiTa, Nota Keuangan,
  KEM-PPKF), `EXTERNAL_SECTOR` (balance of payments), `ECONOMIC_OUTLOOK` (IMF Article IV, World Bank,
  ADB), `STATISTICS` (BPS releases), `DEVELOPMENT_PLAN` (RPJMN), `RESEARCH` (bank / think-tank
  research: an opinion) or `OTHER`.
- **Publication date** (required, not in the future, Jakarta time): the day the document was
  released, not the period it covers. It is stored as 00:00 Jakarta time in
  `rag_document.published_at`. The macro analysis reads only the documents published by its as-of
  date (point in time).
- **Title** (defaults to the file name, at most 300 characters) and **issuer** (optional, at most
  100; the page suggests Bank Indonesia, Ministry of Finance, OJK, BPS, IMF, World Bank, ...).
- The same checks as a company PDF (`.pdf`, `%PDF` header, text layer, 50 MB). The job is a
  `RAG_PDF` job of exchange `MACRO` whose ticker is the country (`ID`); its result is the
  `MacroPdfResult` (country, title, issuer, type, publication date, pages, chunks).
- Each chunk is **embedded with a header** in front of it: "Bank Indonesia - Monetary Policy Review
  August 2026 (monetary policy, Indonesia, 2026-08-20)". A query for "Bank Indonesia guidance" then
  also finds a page that never names its publisher. The stored text is the chunk alone.
- The same file again for the same country replaces the document, its details and its chunks: a
  wrong date or type is corrected by uploading the file again.
- **Stored macro documents** (below the company documents): the documents of a country, latest
  publication first, with a search test over that country's documents.

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

  No web search (Tavily) is used: it returned stock quote / company profile pages and social media
  posts rather than articles, and sites that refuse automated requests (HTTP 403), so only these
  four news sites are read.

  EmitenNews and Investor.id are walked back page by page until a page reaches before the range
  start, a page is empty, or `news-max-pages` (10) pages were read. Older ranges therefore come
  mostly from these two sites.
- Headlines are de-duplicated by URL, sorted newest first; at most `news-max-articles` (60) are read
  per job. A headline without a date is kept until its article is read, then dated from the article
  (and dropped when it falls outside the range).
- Passing failures are retried (`NewsRetry`): a network error or timeout, HTTP 408, 425, 429, 500,
  502, 503 or 504, and for an article also 404, up to `news-retries` (3) more times after 2 s, 4 s
  and 8 s (`news-retry-backoff`). The BNGA job of 2026-10-08 lost two articles to a single IDX
  Channel 404 and a single EmitenNews connect timeout; both pages answered normally a moment later.
  A tag page answering 404 does not exist and is not asked again.
- An article whose site still fails after the retries (`FAILED: ... answered HTTP 404 (4 attempts)`)
  or refuses our request (HTTP 403, not retried) is `FAILED`, one without article text `NO_TEXT`;
  the job is then `INCOMPLETE`. Running the same range again reads only the articles not stored.
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
| `POST` | `/api/v1/rag/pdf` (multipart `file`, `exchange` = IDX, `ticker`, optional `documentType` = `FINANCIAL_STATEMENT` (default) or `REGISTRATION`) | 202 new job, 200 job already active; 400 not a `.pdf` / no `%PDF` header / unknown document type, 422 no text layer / protected / unreadable, or (REGISTRATION) not a registration report / another company's, 404 unknown company, 413 too large |
| `POST` | `/api/v1/rag/news?exchange=IDX&ticker=HRTA&from=2026-10-01&to=2026-10-08` | 202 / 200 as above; 400 start after end, end in the future, range over 366 days, bad date; 404 unknown company |
| `GET`  | `/api/v1/rag/status` | embedding model and dimensions, chunk size, news limits, queue length, stored documents and chunks |
| `GET`  | `/api/v1/rag/documents[?exchange&ticker&tickerPrefix&source=PDF\|NEWS\|XLSX&documentType&limit=100&offset=0]` | one page of the stored documents, most recently stored first: `{ total, limit, offset, documents }` (`total` = documents matching the filters; limit 1-500, offset 0 or more; `ticker` one company, `tickerPrefix` the tickers starting with it; `documentType` FINANCIAL_STATEMENT or REGISTRATION; each document has its `documentType`, null for news) |
| `GET`  | `/api/v1/rag/search?q=...[&exchange=IDX&ticker&source&limit=8]` | closest chunks: ticker, document, pages, published date, content, `distance` (0 = same direction; limit 1-50, query up to 2,000 characters) |
| `POST` | `/api/v1/rag/macro/pdf` (multipart `file`, `country` = ID, `documentType`, `publishedOn` = yyyy-mm-dd, optional `title`, `issuer`) | 202 new job, 200 job already active for the file and country; 400 country not available / unknown, type missing / unknown, date missing / not a date / in the future, title over 300 or issuer over 100 characters, not a `.pdf`; 422 no text layer |
| `GET`  | `/api/v1/rag/macro/documents[?country&documentType&limit=100&offset=0]` | one page of the stored macro documents, latest publication first: `{ total, limit, offset, documents }` (country, title, issuer, documentType, publishedAt, pages, chunks) |
| `GET`  | `/api/v1/rag/macro/search?q=...[&country=ID&limit=8]` | closest chunks of the country's macro documents: title, issuer, type, published date, pages, content, `distance` |

The status (`GET /api/v1/rag/status`) also has `stored.macroDocuments`, `macroCountries` (code,
name, available, documents stored) and `macroDocumentTypes` (code, label, examples). The company
endpoints never return macro documents, and the macro endpoints never return company documents.

Follow a job with `GET /api/v1/ingestions/{id}`; its `result` is the `PdfResult` (pages, pages with
text, characters, chunks, model) or the `NewsResult` described above.

```bash
curl -H "$AUTH" -F file=@data/IDX_XBRL/HRTA/pdf/FinancialStatement-2025-Tahunan-HRTA.pdf -F ticker=HRTA \
     http://localhost:8080/api/v1/rag/pdf
curl -H "$AUTH" -F file=@Monetary-Policy-Review-August-2026.pdf -F country=ID -F documentType=MONETARY_POLICY \
     -F publishedOn=2026-08-20 -F issuer="Bank Indonesia" http://localhost:8080/api/v1/rag/macro/pdf
curl -H "$AUTH" "http://localhost:8080/api/v1/rag/macro/search?country=ID&q=Bank%20Indonesia%20rupiah%20guidance"
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

`V1.0.28__schema_rag_macro.sql` (macro documents of a country):

- `rag_document.company_id` and `rag_chunk.company_id` may be NULL; both tables get `country`, and
  `ck_rag_document_scope` / `ck_rag_chunk_scope` require exactly one of the two.
- `rag_document.issuer` and `document_type` (`ck_rag_document_type`); a macro document is a PDF with
  its type and publication date (`ck_rag_document_macro`).
- Unique on `(country, source_type, source_key)` for the macro documents (a partial index; the
  company documents keep `uq_rag_document_source`); indexes `ix_rag_document_country`,
  `ix_rag_chunk_country`.

`V1.0.33__schema_ownership.sql`: source type `XLSX` (a company's ownership document, `source_key` `OWNERSHIP_5_PERCENT`,
no document type, `published_at` = its latest list, `file_id` = the workbook it was last written from); the upload
`POST /api/v1/rag/xlsx` and the lists are in [OWNERSHIP_DOCS.md](OWNERSHIP_DOCS.md). The status has
`stored.xlsxDocuments`.

`V1.0.34__schema_ownership_one_percent.sql`: the same source type `XLSX` with `source_key` `OWNERSHIP_1_PERCENT` (a
company's 1% ownership document, from KSEI's monthly lists; [OWNERSHIP_ONE_PERCENT_DOCS.md](OWNERSHIP_ONE_PERCENT_DOCS.md)):
a company has one document of each key (`uq_rag_document_source`). `RagRepository.search` takes a source key, so the
analysis' research agent searches the 5% and the 1% documents apart (refs O1 and M1);
`RagRepository.documentCount(XLSX, key)` counts each (`GET /api/v1/rag/xlsx/status`, `stored[]`).

`V1.0.29__schema_registration.sql`: a company PDF's `document_type` (`FINANCIAL_STATEMENT` or `REGISTRATION`,
`ck_rag_document_company_type`; existing company PDFs set to `FINANCIAL_STATEMENT`; news has none), and the
registration tables (`REGISTRATION_DOCS.md`).

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
| `news-retries`         | 3                                | more attempts at a news page after a passing failure |
| `news-retry-backoff`   | 2s                               | pause before the first retry, doubled for each further one |

Changing the embedding model makes old and new vectors incomparable: ingest the documents again.

## 7. Code and tests

`backend/src/main/java/com/neracalab/backend/rag/`: `RagController` (API), `RagQueue` (5 worker
threads, `neracalab.jobs.workers`: that many PDFs / news ingestions at the same time; `JobWorkerPool`), `RagIngestionService`, `PdfText`, `TextChunker`, `EmbeddingClient`,
`NewsCollector`, `RagRepository`, `RagProperties`, `MacroDocumentType`. Frontend:
`rag-pdf-card.tsx` and `rag-macro-pdf-card.tsx` (the two uploads), `rag-documents-card.tsx` and
`rag-macro-documents-card.tsx` (the stored documents).

| Test | Covers |
|------|--------|
| `TextChunkerTest` | cleaning, size limit, overlap, page ranges, long lines, every word kept |
| `EmbeddingClientTest` | response parsing (order, count, dimensions, errors), missing key, vector text form |
| `PdfTextTest` | the HRTA FY2025 PDF page by page and its chunks; non-PDF and text-less PDF rejected |
| `NewsCollectorTest` | Jakarta-time range, walking older pages until the range start, Investor.id page numbers, only the four news sites read (saved pages, no network) |
| `RagNewsIngestionTest` | an article read from its site is stored; a momentary 404 is retried and stored; a lasting 404 fails after 4 attempts, a 403 at once, without stopping the job |
| `NewsRetryTest` | which failures are retried (timeout, 404 of an article, 5xx) and which not (403, 404 of a tag page), the attempt limit, the growing pause, interruption |
| `RagRepositoryTest` | store, cosine search with filters, replace on re-ingest, news keyed by URL, cascade; a macro document of a country: search by country and publication date (point in time), listing by country and type, never mixed with the company documents, replaced with its details, company or country but not both (real database, rolled back) |
| `RagControllerTest` | a PDF through the worker into the store and back by search (stub embeddings, cleaned up); a macro PDF of Indonesia through the worker (`MACRO` / `ID` job, its details, listed, found by the country's search, not a company's); validation errors of both (the United States and China not available yet, type, date, title, issuer, file); status with the macro countries and types; permission |
| `IngestionJobTypeConstraintTest` | `V1.0.10` and `V1.0.14` re-create `ck_ingestion_job_type` on every start; both must list every job type (a narrower list stops the application once a RAG job is stored) |

Frontend: `src/app/(dashboard)/ingestion/rag-pdf`, `rag-news`, `src/components/ingestion/rag-*.tsx`,
`company-picker.tsx`, `src/lib/date-range.ts`, route handlers in `src/app/api/rag/`.
