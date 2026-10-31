# Development context of Claude Code (`dev_context`, RAG)

What Claude Code learns while developing and maintaining Neraca Lab - a ticker whose workbook is missing or lacks
data, an ingestion error and its cause, a fix and why, a decision, a procedure - is saved in the table `dev_context`
so a later session finds it instead of investigating again. One row per fact, with an embedding for search by
meaning and a full-text vector for keywords.

## 1. Table (`V1.0.40__schema_dev_context.sql`)

| Column | Meaning |
|---|---|
| `context_id` | id |
| `kind` | `ISSUE` (a problem seen), `FIX` (what was changed and why), `DATA_GAP` (data the filings or sources lack), `DECISION` (a choice and its reason), `PROCEDURE` (how to do something), `NOTE` |
| `status` | `OPEN` (to do), `RESOLVED` (done), `WONT_FIX` (left so on purpose: by design, the filing's), `INFO` (a fact; default) |
| `ticker` | the company it is about, upper case; NULL for general context. No foreign key: a ticker not stored yet can have context |
| `period` | the reporting period, as `reporting_period` shows it: `2025 FY`, `2026 H1`, `2025 9M` |
| `title`, `body` | the fact, in plain words with the figures that prove it |
| `tags`, `refs` | text arrays: topics (`missing-file`, `gross-profit`, `cash` ...) and what it points to (`ingestion_error 1246`, a class, a data script, a commit) |
| `embedding`, `embedding_model` | `vector(1536)` of ticker, period, title, body and tags, by the RAG store's model (`openai/text-embedding-3-small`, `neracalab.rag.embedding-model`); NULL until computed |
| `search` | `tsvector` (simple configuration) of the same text |
| `created_by`, `created_at`, `updated_at` | who saved it (the logged-in user) and when |

Indexes: `(ticker, status, updated_at)`, `(kind, status, updated_at)`, GIN on `search` and `tags`, HNSW
(`vector_cosine_ops`) on `embedding`.

## 2. API (`/api/v1/dev-context`, permission INGESTION)

| Method | Path | |
|---|---|---|
| POST | `/api/v1/dev-context` | save one: `{"kind", "status", "ticker", "period", "title", "body", "tags": [], "refs": []}`; `kind`, `title`, `body` required; 201 with the row |
| PUT | `/api/v1/dev-context/{id}` | replace it (e.g. `OPEN` -> `RESOLVED` with what was done); embedded again |
| GET | `/api/v1/dev-context/{id}` | one row |
| DELETE | `/api/v1/dev-context/{id}` | 204 |
| GET | `/api/v1/dev-context?ticker=&kind=&status=&tag=&limit=` | rows matching every filter, newest first (limit 50, at most 100) |
| GET | `/api/v1/dev-context/search?q=&ticker=&kind=&status=&tag=&limit=` | by meaning and keywords (limit 10): `[{entry, similarity, keywordRank, score}]` |
| POST | `/api/v1/dev-context/reembed` | embeds the rows saved without an embedding: `{"embedded": n}` |

A row's JSON: `id, kind, status, ticker, period, title, body, tags, refs, embedded, createdBy, createdAt, updatedAt`.

**Search.** The nearest `limit` rows by embedding (cosine) and the best `limit` by `ts_rank` of
`websearch_to_tsquery('simple', q)` are merged; the score is the larger of the similarity (1 - cosine distance) and
the keyword rank scaled to 0 - 1 (`rank / (rank + 0.1)`), so an exact word still ranks high without embeddings.

**Without embeddings.** When the embedding cannot be computed (no `OPENAI_API_KEY`, the provider down) the row is
saved anyway with `embedded: false`; keyword search finds it and `/reembed` fills it later. Validation: `kind` and
`status` from the lists (case-insensitive), title at most 500 characters, period at most 20, ticker upper-cased.

## 3. How Claude Code uses it

The local skill `.claude/skills/dev-context` (git-ignored, like all of `.claude/`) tells Claude Code to search the
context before working on a ticker, an ingestion error, a valuation or income gap, and to save every cause found,
fix made and gap left afterwards; the skills `fix-ingestion-errors`, `fix-valuation-gaps` and
`fix-income-completeness` start and end with it. Read-only SQL works as well:

```sql
SELECT context_id, kind, status, ticker, period, title FROM dev_context WHERE status = 'OPEN' ORDER BY updated_at DESC;
SELECT title, body FROM dev_context WHERE search @@ websearch_to_tsquery('simple', 'gross profit by nature');
```

Seeded on 2026-10-30 with the review of that day: the missing FY2025 workbooks (ASLC, ADES, ALII), tickers without
gross profit or EBITDA in their filings, filing errors (SMMA, DEFI FY2022, ADCP, AGRS), open share counts (FORU,
IRSX), the share counts of V1.0.39, the fixes of that day, and the working rules (uploads one at a time, tests on the
live database, writing with psql, IDX downloads by browser, model routing, no automatic commits).
Added on 2026-10-31 with that day's review: the filings' own errors (rows 106 - 117, tag `xlsx-error`), the gross
profit gaps of AMOR, APIC and ARGO, the open EPS-scale and annualized-EPS issues (121, 122), the fixes of the day (126 -
132); the upload rule (row 57) now says uploads may be submitted together - the models run on the OpenCode Go
subscription and the queue stores one company's filings in upload order.

## 4. Tests

`DevContextControllerTest` (against the Docker Postgres, scripted embeddings; rows of ticker `ZZTST`, deleted
afterwards): save, read, search by meaning and by keyword, filters, update, delete; a row saved while the embedding
fails is found by keyword and embedded by `/reembed`; invalid kind, blank title or blank query -> 400.
