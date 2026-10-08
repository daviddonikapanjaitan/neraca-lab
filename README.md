# Neraca Lab

Application for analyzing financial statements using LLM, with an AI stock screener that ranks
IDX stocks through the eyes of Warren Buffett, Charlie Munger, Peter Lynch, Philip Fisher,
Keith Gill (Roaring Kitty) and a Risk agent, and an in-depth AI analysis of one stock from its
stored filings, documents and news.

- Backend using: Spring Boot 4 (Java 21) + PostgreSQL 17 with pgvector + Redis 7 + Spring AI 2.0 (OpenRouter)
- Frontend using: Next.js 16 (React 19, TypeScript) + shadcn/ui with the shadcn-fintech theme + Tailwind CSS 4

Assistant developer: Claude Code (Opus 5.5 LLM Model)

## Quick start: full stack in Docker

One script builds and starts everything (postgres, redis, backend, frontend) in Docker, waits
until it is ready and opens <http://localhost:3000>. Only Docker is required (Docker Desktop on
Windows / macOS, Docker Engine with the Compose plugin 2.20+ on Linux); no Java or Node.js.

| OS      | Start                                            | Stop                                            |
|---------|--------------------------------------------------|-------------------------------------------------|
| Windows | `scripts\start-windows.bat` (or double-click it) | `scripts\stop-windows.bat` (or double-click it) |
| macOS   | `./scripts/start-mac.sh`                         | `./scripts/stop-mac.sh`                         |
| Linux   | `./scripts/start-linux.sh`                       | `./scripts/stop-linux.sh`                       |

Stopping removes the containers but keeps the database data; it does nothing (and does not start
Docker) when nothing is running. The start scripts also take `restart`, `status`,
`logs [frontend|backend|postgres|redis]` and `help`.
The first start downloads images and builds both apps (several minutes); later starts take seconds.
The AI upload, the AI screening and the AI analysis need `backend/.env` with `OPENAI_API_KEY` (OpenRouter; copy
`backend/.env.example`), the screening's news search also `TAVILY_API_KEY`; the rest works without them. Options, ports and troubleshooting:
[`docs/v1_docs/DOCKER_DOCS.md`](docs/v1_docs/DOCKER_DOCS.md).

**Log in** at <http://localhost:3000> with the root user **`admin` / `admin`** (created at the first
start), then change its password in Admin Center > User Management.

| Service  | URL / port                                         |
|----------|----------------------------------------------------|
| frontend | <http://localhost:3000>                            |
| backend  | <http://localhost:8080> (e.g. `/api/v1/health`)    |
| postgres | localhost:5432 (db/user/password `neracalab`)      |
| redis    | localhost:6379 (password `neracalab`)              |

## Documentation

| Document                                                    | Content                                                                   |
|-------------------------------------------------------------|---------------------------------------------------------------------------|
| [`DOCKER_DOCS.md`](docs/v1_docs/DOCKER_DOCS.md)             | start / stop scripts, containers, compose files, options, troubleshooting |
| [`FRONTEND_DOCS.md`](docs/v1_docs/FRONTEND_DOCS.md)         | web app: pages, tabs, data flow, formatting, structure                    |
| [`COMPANY_API_DOCS.md`](docs/v1_docs/COMPANY_API_DOCS.md)   | exchange and company APIs: parameters, every response field, errors       |
| [`AI_INGESTION_DOCS.md`](docs/v1_docs/AI_INGESTION_DOCS.md) | AI upload endpoint: agent design, tools, workbook mapping, tests          |
| [`PRICE_INGESTION_DOCS.md`](docs/v1_docs/PRICE_INGESTION_DOCS.md) | daily price ingestion: endpoints, providers, polite crawling, settings |
| [`INGESTION_JOBS_DOCS.md`](docs/v1_docs/INGESTION_JOBS_DOCS.md) | async ingestion: stored uploads (checksum), job progress, list and download APIs |
| [`SCREENING_DOCS.md`](docs/v1_docs/SCREENING_DOCS.md)       | AI stock screening: daily ETL, Stage 1, agents (tool calling, ReAct, Reflection, Reflexion), cost, report, PDF |
| [`ANALYSIS_DOCS.md`](docs/v1_docs/ANALYSIS_DOCS.md)         | AI analysis of one stock: company data, research agent over its documents (RAG), agents, cost, report, PDF |
| [`AUTH_DOCS.md`](docs/v1_docs/AUTH_DOCS.md)                 | login, users, roles, permissions, profile: rules, APIs, root user, sessions |
| [`RAG_DOCS.md`](docs/v1_docs/RAG_DOCS.md)                   | RAG vector store (pgvector): PDF and news ingestion, chunking, embeddings, search API |
| [`DB_SCHEMA_DOCS.md`](docs/v1_docs/DB_SCHEMA_DOCS.md)       | database: every table, column, constraint and view, loading data          |

## Project structure

```text
neraca_lab/
├── docker-compose.yaml          full stack: includes backend/docker-compose.yaml + frontend
├── scripts/                     start-* and stop-* for Windows (.bat), macOS and Linux (.sh)
├── backend/                     Spring Boot application
│   ├── Dockerfile
│   ├── docker-compose.yaml      postgres + redis + backend
│   ├── postgres/Dockerfile      postgres:17-alpine + pgvector (RAG vector store)
│   ├── .env.example             template for backend/.env (AI key; .env is git-ignored)
│   └── src/main/
│       ├── java/.../company/    company list / detail APIs, exchange and ticker codes
│       ├── java/.../ingestion/  AI upload endpoint: xlsx reader, mapper, agent, tools, repository,
│       │                        stored files (checksum), background upload queue
│       ├── java/.../price/      daily price ingestion: queue, providers, valuation refresh
│       ├── java/.../job/        ingestion job progress (ingestion_job): list / detail / file download APIs
│       ├── java/.../auth/       login, sessions, access check of every API, root user bootstrap
│       ├── java/.../user/       user management, role management, profile (avatar)
│       ├── java/.../screening/  AI stock screening: data/ (Yahoo ETL), quant/ (Stage 1), news/
│       │                        (crawlers, Tavily), agent/ (research, investor agents, reflection,
│       │                        Reflexion, synthesis), report/ (PDF), queue, service, controller
│       ├── java/.../rag/        RAG vector store: PDF text, chunker, embeddings, news collector,
│       │                        pgvector repository, queue, controller
│       ├── java/.../analysis/   AI analysis of one stock: fact sheet, research agent and its tools
│       │                        (RAG search, statements), synthesis, report/ (PDF), queue, service, controller
│       └── resources/
│           ├── application.yaml
│           └── db/              SQL scripts (no Flyway)
│               ├── V1.0.1__schema.sql
│               ├── V1.0.2__schema_market_valuation.sql
│               ├── V1.0.3__views.sql
│               ├── V1.0.7__schema_ingestion.sql   (schema script, runs after V1.0.3)
│               ├── V1.0.8__schema_auth.sql        (schema script: users, roles, sessions)
│               ├── V1.0.9__schema_ingestion_created_by.sql   (who started an ingestion job)
│               ├── V1.0.10__schema_screening.sql  (AI screening: universe, snapshots, news, runs, usage)
│               ├── V1.0.11__schema_fx.sql         (fx_rate_daily: ECB rates for listings quoted in another currency)
│               ├── V1.0.14__schema_rag.sql        (RAG vector store: rag_document, rag_chunk; needs pgvector)
│               ├── V1.0.15__schema_analysis.sql   (AI analysis of one stock: analysis_run, analysis_agent_score)
│               ├── V1.0.16__schema_syirkah.sql    (banks: balance_sheet.temporary_syirkah_funds)
│               ├── V1.0.4__data_HRTA_financials.sql
│               ├── V1.0.5__data_HRTA_market.sql
│               ├── V1.0.12__data_SMDR_shares.sql  (SMDR share counts from public sources, 2023 stock split)
│               ├── V1.0.13__data_BNGA_shares.sql  (BNGA audited share counts from its annual reports)
│               └── V1.0.6__data_metrics_valuation.sql
├── frontend/                    Next.js web app (login, companies, screening, ingestion, admin center, profile)
│   ├── Dockerfile               standalone Next.js server (node server.js)
│   ├── .env.example             template for frontend/.env.local (NERACA_API_URL)
│   └── src/                     app/ (pages, api/ route handlers), components/ (ui = shadcn-fintech), lib/ (API client)
├── data/<TICKER>/               source data per company
│   ├── xlsx/                    IDX XBRL financial statements (FinancialStatement-<period>-<TICKER>.xlsx)
│   ├── pdf/                     the same filings as PDF (upload them on Ingestion > PDF Documents (RAG))
│   └── price/                   daily prices (<TICKER>.JK_daily_yahoo.csv)
│                                HRTA: xlsx, pdf and prices, loaded as seed data on every start;
│                                HRTA 2022 .. 2024 annual, INDF (2022 .. 2026-II), GGRM (2022, 2024, 2025, 2026-II)
│                                INDY (2022 .. 2025 annual, 2026-II)
│                                SMDR (2022 .. 2025 annual, 2026-II, Infrastructure Industry taxonomy)
│                                BNGA, BMRI (2022 .. 2025 annual, 2026-II) and BTPN (2022 .. 2025 annual), banks:
│                                Financial and Sharia Industry taxonomy
│                                ASGR, SIMP and CEKA (2022 .. 2025 annual, 2026-II; quirks: full amounts under "In Million",
│                                EPS filed in millions, revenue not tagged, one amount reported twice, a product in two
│                                revenue slots, a cash flow section without activity)
│                                MYOR, PTSN (2022 .. 2025 annual, 2026-II) and NCKL (2023 .. 2025 annual, 2026-II)
│                                CMRY, CPIN (2022 .. 2025 annual, 2026-II)
│                                (NCKL FY2024: EPS one decimal place off, see AI_INGESTION_DOCS)
│                                xlsx: load them with the upload (Ingestion page)
└── docs/v1_docs/                DOCKER_DOCS.md, FRONTEND_DOCS.md, COMPANY_API_DOCS.md,
                                 AI_INGESTION_DOCS.md, PRICE_INGESTION_DOCS.md,
                                 INGESTION_JOBS_DOCS.md, AUTH_DOCS.md, DB_SCHEMA_DOCS.md,
                                 SCREENING_DOCS.md, ANALYSIS_DOCS.md, RAG_DOCS.md
                                 (see Documentation)
```

## Running the backend only

Requires Docker. Uses the same containers and database as the full stack (compose project
`backend`), so start one or the other. While the full stack runs, stop it with the stop script:
`docker compose down` here only knows postgres, redis and backend, not the frontend.

```bash
cd backend
docker compose up -d --build     # start postgres, redis and backend
docker compose logs -f backend   # follow logs
docker compose down              # stop (add -v to also delete the database volume: all data)
```

| Service  | Port | Default credentials          |
|----------|------|------------------------------|
| backend  | 8080 | -                            |
| postgres | 5432 | db/user/password `neracalab` |
| redis    | 6379 | password `neracalab`         |

Configuration lives in `backend/.env` (copy `backend/.env.example`). It holds the AI settings
(`OPENAI_API_KEY`, `OPENAI_BASE_URL`, `OPENAI_MODEL`) and optional overrides (`DB_NAME`,
`DB_USERNAME`, `DB_PASSWORD`, `REDIS_PASSWORD`, `JAVA_OPTS`). The file is ignored by git and docker;
never commit keys. Use real passwords outside local development. The AI upload sends `OPENAI_MODEL` with every
request (Spring AI 2.0 otherwise substitutes its default `gpt-5-mini`, which earlier versions of the
upload did); each job's `metrics.models` shows the model OpenRouter answered with.

To run the backend from the IDE instead, start only the infrastructure with
`docker compose up -d postgres redis` (in `backend/`); `application.yaml` defaults to `localhost`.
The IDE backend uses port 8080, so stop the full stack (or the backend container) first.

## Login, users, roles and permissions

Every page and API needs a login (username and password); only `POST /api/v1/auth/login`,
`POST /api/v1/auth/logout` and `GET /api/v1/health` are public. A user has one or more roles, a
role has one or more permissions, and the backend checks them on every API request:

| Permission  | Opens                                                                    |
|-------------|--------------------------------------------------------------------------|
| `ADMIN`     | Admin Center (User Management, Role Management) and `/api/v1/admin/**`   |
| `INGESTION` | Ingestion pages, upload / price / RAG / job APIs (and the company list)  |
| `COMPANIES` | Companies pages and company APIs                                         |
| `SCREENING` | Screening pages (Screening Stocks, Analysis): runs, analyses, reports, PDF export |

The root user `admin` (initial password `admin`, dummy profile data) has the built-in
Administrator role with every permission; it cannot be deleted, deactivated or lose that role.
Usernames and emails are unique. On the profile page (avatar in the sidebar footer) users change
only their picture, address, phone and date of birth.

```bash
TOKEN=$(curl -s -H "Content-Type: application/json" -d '{"username":"admin","password":"admin"}' \
        http://localhost:8080/api/v1/auth/login | python -c "import sys,json;print(json.load(sys.stdin)['token'])")
AUTH="Authorization: Bearer $TOKEN"     # used by the examples below
```

Details: [`docs/v1_docs/AUTH_DOCS.md`](docs/v1_docs/AUTH_DOCS.md).

## Uploading financial statements (AI ingestion)

```bash
curl -H "$AUTH" -F "file=@data/HRTA/xlsx/FinancialStatement-2026-II-HRTA.xlsx" \
     http://localhost:8080/api/v1/financial-statements/upload
```

`POST /api/v1/financial-statements/upload` takes an IDX XBRL workbook (`.xlsx` from idx.co.id).
Java parses and validates it (Apache POI, accounting identity checks); a Spring AI agent with tool
calling then stores the company, periods, statements, revenue segments and share counts, refreshes
the derived metrics and verifies the result. It uses Plan-and-Execute, a tool calling loop with
ReAct, sequential / parallel / conditional tool calling and a reflection review. Amounts never pass
through the model, and the final status comes from a database read-back. The job result contains
the plan, every tool call and the verification. Details: [`docs/v1_docs/AI_INGESTION_DOCS.md`](docs/v1_docs/AI_INGESTION_DOCS.md).

The upload is asynchronous. The workbook is checked, stored once per SHA-256 checksum in
`ingestion_file` (re-uploading the same file reuses the stored copy and extracts it again), and the
agent runs in the background. Every ingestion (uploads and prices) records its progress and the
user who started it in `ingestion_job`; `GET /api/v1/ingestions` lists them, `GET /api/v1/ingestions/{id}` returns one
with its result and `GET /api/v1/ingestions/{id}/file` downloads the uploaded workbook. Details: [`docs/v1_docs/INGESTION_JOBS_DOCS.md`](docs/v1_docs/INGESTION_JOBS_DOCS.md).

| HTTP | Meaning                                                                         |
|------|---------------------------------------------------------------------------------|
| 202  | job queued; final status `SUCCEEDED`, `INCOMPLETE` or `FAILED` in the job       |
| 200  | the same file is already queued / being stored: that job                        |
| 422  | not an IDX XBRL `.xlsx` workbook, or an unsupported template (nothing stored)   |

Both IDX layouts are read: the current one (2023 onwards) and the pre-2023 one of FY2022 and earlier
filings (date column headers, sheets `1410000 1 CurrentYear` / `2 PriorYear`, highly compressed
styles). Three IDX taxonomies are supported: General Industry (`1xxxxxx` sheets), Infrastructure
Industry (`3xxxxxx` sheets, e.g. SMDR), which has the same statements under other codes, and Financial
and Sharia Industry (`4xxxxxx` sheets, banks, e.g. BNGA), mapped with bank definitions (revenue =
interest + non-interest operating income; current items, short-term debt and EBITDA left NULL).
When a filing gives no share counts (several share classes, treasury shares, imprecise EPS), the
ingestion takes Yahoo Finance's published counts, but only those that reproduce the filing's own EPS;
they never replace a stored count (`neracalab.ingestion.web-share-counts`, on by default).
Workbooks are not trusted blindly: a declared rounding level contradicted by the amounts (ASGR FY2023:
"In Million" over full amounts) is corrected when the filing's own EPS confirms it, and an amount a
filing reports twice in one column (ASGR H1 2025: "Other expenses" and "Other gains (losses)") is
counted once when only that makes profit before tax reconcile; a bank's insurance claims shown for
information beside premiums already net of them (BMRI's FY2024 comparative) are not deducted again when
only that makes profit from operation reconcile; an EPS filed in the rounding unit (SIMP
H1 2026: 0.0000564 for Rp 56.35) is scaled; a filing that tags only gross profit (SIMP FY2023) is stored
without revenue, which a later filing's comparative fills (comparatives fill empty fields, never change
stored values; an EPS and its share count always come from one filing, and a share split a comparative
restates - BMRI 2:1 in 2023 - adjusts the per-share figures of that and earlier periods). A bank's temporary
syirkah funds are stored beside its liabilities (`temporary_syirkah_funds`), not in them. A filed EPS is checked against the filing's share count: a workbook whose two EPS columns
contradict each other (NCKL FY2024) gives no share counts and its EPS is cleared, and an EPS off by a power of
ten is corrected (INDF H1 2024: 0.000439 -> 439); a workbook re-run refreshes the periods it wrote. All of
these are reported as warnings. A period's revenue breakdown always comes from one filing: the period's own filing replaces
it, a later filing's comparative only fills a period without one (issuers re-cut segments between
years, mixing them counts revenue twice). Cash flow ending cash net of bank overdrafts (e.g. GGRM) is
accepted as filed; a model call that times out or hits a provider error is retried
(`neracalab.ingestion.model-retries`, default 2; a call stalled for 45 s is retried, `model-call-timeout`), the
agent runs the model without reasoning (`model-reasoning: false`: a plan in 10 s instead of 69 s; a filing in 1-2
minutes instead of 4-15), a model that fails mid-run after its retries no longer fails the job (the remaining
standard steps are done deterministically and verified), and an upload job that runs longer than
`neracalab.ingestion.job-timeout` (default 5 minutes, queue time excluded) is stopped and FAILED; what it
saved before the limit is kept. Values are verified at the stored column scale (e.g. INDY's 16-decimal
USD EPS as `NUMERIC(20,8)`); the pre-2023 revenue sheets are read too. When no par value fits (USD
share capital, INDY), shares outstanding come from an exact EPS denominator (INDY FY2023: 5,202,692,000)
and periods without a count of their own use the latest share snapshot. Details: [`docs/v1_docs/AI_INGESTION_DOCS.md`](docs/v1_docs/AI_INGESTION_DOCS.md), section 4.

A company exists once per `(ticker, exchange)`: the upload upserts it on the database constraint
`uq_company_ticker_exchange`, and both codes are stored upper case (checks `ck_company_ticker`,
`ck_company_exchange`), so uploading the same company again, or concurrently, never creates a
second row.

## Company APIs

```bash
curl -H "$AUTH"  http://localhost:8080/api/v1/exchanges                 # supported exchanges
curl -H "$AUTH" "http://localhost:8080/api/v1/companies?exchange=IDX"   # companies of an exchange
curl -H "$AUTH"  http://localhost:8080/api/v1/companies/IDX/HRTA        # everything stored for one company
```

| Endpoint                                    | Returns                                                                                                                                                                                                                                                                                           |
|---------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `GET /api/v1/exchanges`                     | supported exchanges: `code`, `name`, `country` (e.g. for an exchange filter)                                                                                                                                                                                                                      |
| `GET /api/v1/companies?exchange=IDX`        | companies of the exchange, ordered by ticker: master data plus number of periods, first / latest period and latest price date. `exchange` defaults to `IDX`                                                                                                                                       |
| `GET /api/v1/companies/{exchange}/{ticker}` | `company`, `coverage` (row counts and date ranges per table), `periods` (most recent first, each with income statement, balance sheet, cash flow, segment figures and fundamental metrics), `segments`, `shareSnapshots`, `latestPrice`, `latestMarketSnapshot`, `valuations`, `corporateActions` |

Exchange and ticker are case-insensitive (`idx/hrta` works). Amounts are full currency units with the
database sign conventions; a statement that is not stored for a period is `null`. The detail is
read in one REPEATABLE READ transaction, so it is consistent even while an upload is running.
Daily prices are summarised (range in `coverage`, last day in `latestPrice`), not listed.

| HTTP | Meaning                                                         |
|------|-----------------------------------------------------------------|
| 200  | OK                                                              |
| 400  | unsupported exchange (lists `supportedExchanges`) or bad ticker |
| 404  | no company with this ticker on this exchange                    |

Supported exchanges are the constants of `company/Exchange.java` (currently `IDX`). Supporting
NYSE, NASDAQ, SSE, ... later is one enum constant; the APIs then accept it. Full field reference:
[`docs/v1_docs/COMPANY_API_DOCS.md`](docs/v1_docs/COMPANY_API_DOCS.md).

## Daily price ingestion

```bash
curl -H "$AUTH" -X POST "http://localhost:8080/api/v1/prices/ingestions?exchange=IDX&ticker=HRTA"   # queue a job
curl -H "$AUTH"  http://localhost:8080/api/v1/prices/ingestions/{id}                               # job status / result
curl -H "$AUTH"  http://localhost:8080/api/v1/prices/ingestions                                    # provider, queue, recent jobs
```

| Endpoint                                                      | Does                                                                                                                                    |
|---------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------|
| `POST /api/v1/prices/ingestions?exchange=&ticker=[&full=true]` | queues an ingestion: **202** with the new job, **200** with the job already queued / running for the company; 400 / 404 as the company APIs |
| `GET /api/v1/prices/ingestions/{id}`                          | the job: `status` (`QUEUED`, `RUNNING`, `WAITING_RATE_LIMIT`, `SUCCEEDED`, `FAILED`), `message`, `result` (requests, rows inserted / updated, valuation rows) |
| `GET /api/v1/prices/ingestions`                               | `provider`, `pending` jobs and the recent jobs, most recent first                                                                        |

A background worker (one thread) fetches the
[Yahoo Finance chart API](https://query1.finance.yahoo.com/v8/finance/chart/HRTA.JK) (`HRTA.JK` for IDX) and never inside a web request;
the frontend only reads the database. Per job:

1. Range from `MAX(trading_date)` of the company (re-fetched as an overlap check) to today, or the full
   history when nothing is stored or `full=true`. No request when the last completed trading day is
   already stored. Today's bar counts only after 17:00 exchange time (`session-close-cutoff`).
2. One request; holiday placeholders, zero-volume rows that repeat the previous close and invalid rows
   are dropped. If the overlap day's close or adjusted close changed (dividend / split after it), the
   full history is fetched once more and every stored day corrected.
3. One transaction: upsert `price_daily`, then recalculate the company's `market_snapshot`,
   `valuation_snapshot` (period ends, every earlier valuation date, the new latest day) and VALUATION
   rows of `financial_metric`, with the formulas of `V1.0.6`.

`price_daily` is in the company's reporting currency. A listing quoted in another currency (INDY:
IDR on IDX, reports in USD) is converted before it is stored: each day / the ECB USD/IDR reference
rate of the previous FX day (Frankfurter API, stored in `fx_rate_daily`; one extra request), 8
decimals; days without a rate in the 7 days before are not stored. Yahoo's own `USDIDR=X` is not used
(days off by a factor of 10). The UI shows prices and EPS below 1 with 6 decimals. Details:
[`docs/v1_docs/PRICE_INGESTION_DOCS.md`](docs/v1_docs/PRICE_INGESTION_DOCS.md), section 2.

Polite crawling: one request at a time with a random 1-2 s pause, a browser User-Agent and one HTTP
client with a cookie store. On HTTP 429 the queue pauses 15, 30, then 60 minutes and retries the
same job; a 429 after that stops the run (queued jobs fail, re-submit later; each job resumes from
`MAX(trading_date)`). The queue lives in memory (a restart drops queued jobs; re-submit), but every
job and its progress is also recorded in `ingestion_job` and listed by `GET /api/v1/ingestions`.

| Setting (`application.yaml` / env)                    | Default                     |                                                     |
|-------------------------------------------------------|-----------------------------|-----------------------------------------------------|
| `neracalab.prices.provider` / `PRICE_PROVIDER`         | `yahoo`                     | `eodhd` switches to the paid EODHD API              |
| `neracalab.prices.eodhd.api-token` / `EODHD_API_TOKEN` | empty                       | required for `eodhd`                                |
| `neracalab.prices.min-delay`, `max-delay`             | `1s`, `2s`                  | pause between two requests                          |
| `neracalab.prices.backoff`                            | `[15m, 30m, 60m]`           | waits after consecutive HTTP 429                    |
| `neracalab.prices.schedule.enabled` / `PRICE_SCHEDULE_ENABLED` | `false`            | evening run: queues every active company of `IDX`   |
| `neracalab.prices.schedule.cron`                      | `0 30 17 * * MON-FRI` (WIB) |                                                     |

`full=true` re-fetches the whole history instead of starting at the latest stored day; a first
ingestion and a re-adjusted history switch to it automatically. Details, job fields and known
limitations: [`docs/v1_docs/PRICE_INGESTION_DOCS.md`](docs/v1_docs/PRICE_INGESTION_DOCS.md).

## RAG vector store (PDF documents and news)

Two Ingestion pages fill a pgvector store that the AI features can search by meaning; every
document is linked to its company (`rag_document.company_id` -> `company`):

- **PDF Documents (RAG)**: upload a `.pdf` with a text layer (e.g. `data/HRTA/pdf`) for a stored
  company (picked automatically from a file name ending with the ticker). Its text is read page by
  page (PDFBox), split into ~1,500-character chunks, embedded and stored.
- **News (RAG)**: choose an IDX company and a date range (this month, last 7 / 30 days, previous
  month or custom, Jakarta time, up to 366 days). Articles from EmitenNews, Investor.id, IDX Channel
  and Pasardana published in the range are read and stored; articles already stored are skipped.

```bash
curl -H "$AUTH" -F file=@data/HRTA/pdf/FinancialStatement-2025-Tahunan-HRTA.pdf -F ticker=HRTA      http://localhost:8080/api/v1/rag/pdf                                         # queue a PDF
curl -H "$AUTH" -X POST "http://localhost:8080/api/v1/rag/news?ticker=HRTA&from=2026-10-01&to=2026-10-08"
curl -H "$AUTH" "http://localhost:8080/api/v1/rag/search?ticker=HRTA&q=gold%20sales%202025"   # closest chunks
```

Embeddings: `openai/text-embedding-3-small` (1536 dimensions) through OpenRouter with the same
`OPENAI_API_KEY`. The postgres container is built from `backend/postgres/Dockerfile`
(`postgres:17-alpine` + pgvector 0.8.7), so the existing data volume keeps working. Details:
[`docs/v1_docs/RAG_DOCS.md`](docs/v1_docs/RAG_DOCS.md).

## AI stock screening

**Screening** in the sidebar (permission `SCREENING`): choose the exchange (IDX), the market cap
(large >= Rp 10T, mid Rp 1-10T, small < Rp 1T), the top N (1-50, default 25) and the investor agents
(multi-select). The run is a background job; its report is saved in the database, can be opened
again and downloaded as PDF.

```text
[Daily ETL] Yahoo Finance -> PostgreSQL (837 IDX listings, daily market data, weekly fundamentals)
[Stage 1, Java, no AI] tier, price, trading, liquidity, earnings, equity filters
                       -> quantitative scorecard per agent -> shortlist = top N x 3 (max 100)
[Stage 2, DeepSeek V4 Flash] research agent (tool calling + ReAct: EmitenNews, Pasardana,
                       IDX Channel, Investor.id, Tavily) -> six independent investor agents
                       -> Reflection (validator + critic) -> Reflexion (lessons across runs)
[Synthesis, Claude Opus 5.5] executive summary, conviction, thesis, +-5 point adjustments
[Report] database -> Screening page -> PDF; tokens and cost of every model call recorded
```

```bash
curl -H "$AUTH" -H "Content-Type: application/json" http://localhost:8080/api/v1/screenings \
     -d '{"exchange":"IDX","marketCapTier":"LARGE","topN":25,"agents":["BUFFETT","MUNGER","LYNCH","FISHER","GILL","RISK"]}'
curl -H "$AUTH" http://localhost:8080/api/v1/screenings/{id}         # report (progress while running)
curl -H "$AUTH" -OJ http://localhost:8080/api/v1/screenings/{id}/pdf # PDF
```

Each agent score is 60% quantitative scorecard + 40% AI judgement; the overall score is the
average of the investor agents, blended 20% with the Risk agent (safety). Cost is capped at
**$0.45 per run** (`SCREENING_BUDGET_USD`); a large-cap top 10 with all six agents (30 stocks
analysed) cost $0.12. The daily ETL runs Monday-Friday 18:00 WIB and before each screening; the
Ingestion page can start it by hand. Details: [`docs/v1_docs/SCREENING_DOCS.md`](docs/v1_docs/SCREENING_DOCS.md).

## AI analysis of one stock

**Screening > Analysis** in the sidebar (the Screening entry is a dropdown with Screening Stocks and
Analysis; permission `SCREENING`): choose a stock of the companies table and the investor agents.
The form shows what the database holds for it (statements, market data, PDF documents, news). The
analysis is a background job; its report and cost are saved and can be downloaded as PDF.

```text
[Company data, no AI] stored statements, metrics, prices, valuations -> fact sheet;
                      Yahoo Finance metrics -> quantitative scorecard per agent
[Research, DeepSeek]  ReAct + tool calling over the company's own documents: semantic search in
                      its PDF chunks and news (pgvector), full statements of a period -> brief with refs
[Agents, DeepSeek]    six independent investor agents on one shared dossier -> Reflection
                      (validator + critic) -> Reflexion (lessons across runs)
[Synthesis, Opus 5.5] summary, conviction, thesis, bull / bear case, risks, +-5 point adjustment
[Report]              database -> Screening > Analysis -> PDF; every call's tokens and cost recorded
```

```bash
curl -H "$AUTH" -H "Content-Type: application/json" http://localhost:8080/api/v1/analyses -d '{"ticker":"HRTA"}'
curl -H "$AUTH" http://localhost:8080/api/v1/analyses/{id}           # report (progress while running)
curl -H "$AUTH" -OJ http://localhost:8080/api/v1/analyses/{id}/pdf   # PDF
```

Cost is capped at **$0.20 per analysis** (`ANALYSIS_BUDGET_USD`); without stored documents the
research agent is skipped (no call). The DeepSeek calls of the screening and the analysis go to the
cheapest OpenRouter provider except `neracalab.screening.llm.provider-ignore` (`SCREENING_PROVIDER_IGNORE`,
default `OpenInference`, whose fp4 endpoint looped past OpenRouter's 60 s limit and ignored the answer
format); a call that fails transiently (stream reset, timeout, 408 / 429 / 5xx) is retried twice, and
an agent answer without strengths and concerns is asked again. Details: [`docs/v1_docs/ANALYSIS_DOCS.md`](docs/v1_docs/ANALYSIS_DOCS.md).

## Frontend

Next.js web app in `frontend/` with the
[shadcn-fintech](https://github.com/abderrahimghazali/shadcn-fintech) theme (MIT). Requires
Node.js 20.9+ and a running backend.

```bash
cd frontend
npm install
cp .env.example .env.local    # only if the backend is not on http://localhost:8080
npm run dev                   # http://localhost:3000
```

| Page                             | Content                                                                                                                                                            |
|----------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `/companies?exchange=IDX`        | companies of an exchange: exchange filter, search, sector filter, sortable table, summary tiles                                                                    |
| `/companies/{exchange}/{ticker}` | company detail in tabs: overview (KPIs, charts, data coverage), income statement, balance sheet, cash flow, segments, metrics, valuation, market & shares, filings |
| `/screening`                     | Screening dropdown (like Ingestion), Screening Stocks: exchange, market cap, top N, investor agents (multi-select); saved screenings |
| `/screening/{id}`                | screening report: progress, executive summary, ranking with per-agent scores, stock details (news, reasoning, reflection), funnel, token usage, PDF download |
| `/screening/analysis`            | Analysis: one stock of the companies table (with its stored data) and the investor agents; saved analyses (10 per page: 5 / 10 / 20 / 50) |
| `/screening/analysis/{id}`       | analysis report: progress, overall score and cost, executive summary (bull / bear case, risks), every agent's view, research brief with its excerpts and ReAct steps, key figures, data used, notes, token usage, PDF download |
| `/ingestion/xbrl`                | Ingestion dropdown (like the Admin Center), IDX XBRL: upload an `.xlsx` financial statement (`/ingestion` opens this page) |
| `/ingestion/prices`              | Price Ingestion: fetch daily prices (exchange / ticker dropdowns) |
| `/ingestion/screening-data`      | Screening Data IDX: update the screening data (Yahoo Finance ETL) |
| `/ingestion/rag-pdf`             | PDF Documents (RAG): upload a `.pdf` of a company into the pgvector store; stored documents (10 per page: 5 / 10 / 20 / 50) and a search test |
| `/ingestion/rag-news`            | News (RAG): store the news of an IDX company for a date range (presets or custom); stored articles (10 per page: 5 / 10 / 20 / 50) and a search test |
|                                  | Every Ingestion page ends with the jobs table: it opens on the page's own job type (tabs for the others and "All"), 10 jobs per page (5 / 10 / 20 / 50) |
| `/login`                         | username / password login (every other page needs it)                                                                                                             |
| `/admin/users`, `/admin/roles`   | Admin Center (`ADMIN`): add, view, update and delete users and roles                                                                                               |
| `/profile`                       | own profile: picture, address, phone, date of birth (username and email are read-only)                                                                             |

Pages fetch the backend in Server Components (`NERACA_API_URL`, server-side only), so the
backend needs no CORS setup. The login stores the session token in an httpOnly cookie; the
Next.js server sends it to the backend as a bearer token, and the sidebar shows only the pages the
user's permissions allow. Every Ingestion page shows the live table of every job (details, file
download). The ingestion pages' uploads, price requests, job polling and file
downloads go through Next.js Route Handlers (`src/app/api/`), so the browser never calls the backend
directly either. Details: [`docs/v1_docs/FRONTEND_DOCS.md`](docs/v1_docs/FRONTEND_DOCS.md).

## Tests

The backend tests need the Postgres on localhost:5432 (the full stack, or
`cd backend && docker compose up -d postgres redis`).

```bash
(cd backend && ./mvnw test)                      # 325 tests
(cd frontend && npm run lint && npm run build)   # type check, lint, production build
```

`FilingMapperHrtaTest` maps the six HRTA filings and compares every field with the validated seed
data; `FilingMapperLegacyTemplateTest` maps the pre-2023 INDF FY2022 filing and compares it with the
FY2022 comparatives of the FY2023 filing; `IngestionRepositorySegmentsTest` checks that a period's
own filing replaces its whole revenue breakdown (rolled back); `IngestionVerifierOverdraftTest` checks that
GGRM's cash net of bank overdrafts (FY2022, FY2024, FY2025) is accepted as filed;
`IngestionAgentRetryTest` that a model call is retried after a read timeout but not after a
permanent error; `IngestionAgentModelTest` that every ingestion request names the configured model
(Spring AI's own default would be `gpt-5-mini`). Both take the model from the configuration, not from
Java code: `ConfiguredChatModel` resolves `spring.ai.openai.chat.model` of `application.yaml` with
`OPENAI_MODEL` from the environment, else `backend/.env`, else the `application.yaml` default; `PriceIngestionServiceTest` also converts a listing quoted in another currency and
`EcbFxRateProviderTest` parses a real ECB (Frankfurter) response; `FilingMapperIndySharesTest`
derives INDY's share count from the FY2023 EPS and no count from the other INDY filings;
`FilingMapperInfrastructureTest` maps the five SMDR filings (Infrastructure Industry taxonomy);
`FilingMapperFinancialTest` maps the five BNGA, five BMRI and four BTPN filings (Financial and Sharia Industry taxonomy,
banks; BMRI's insurance claims shown for information); `ReclassificationTest` that the agent can revise its own
classification while the column still fails;
`FilingMapperAsgrTest` the ASGR and SIMP filings (full amounts under an "In Million" label, an amount reported twice,
EPS filed in millions, revenue not tagged); `StatementGapFillTest` that comparatives only fill empty fields; `ShareSplitTest` the split adjustment; `FilingMapperCekaTest` the five CEKA filings; `FilingMapperEpsTest` the EPS checks and the MYOR, NCKL, PTSN filings;
`JobDeadlineTest` and `UploadJobTimeoutTest` the 5-minute job limit; `ModelSpeedSettingsTest` reasoning off on the
wire and the per-call timeout; `DeterministicFinisherTest` a run finished without the model;
`FilingMapperWebSharesTest` and `WebShareCountsTest` the Yahoo Finance share counts (only counts that fit the filing's EPS);
`SmdrShareSeedTest` and `BngaShareSeedTest` check the SMDR and BNGA share-count scripts (fill once, never replace a stored count); `SameAsStoredTest` that filing values are compared at the stored column scale
(INDY's 16-decimal USD EPS); `BackendApplicationTests` starts the application context. `CompanyControllerTest` calls the
exchange and company APIs against the HRTA seed data, `CompanyUniquenessTest` checks that a
second, lower-case, padded or exchange-less company row is rejected and that the ingestion upsert
keeps one row, and `CompanyCodesTest` covers ticker / exchange normalisation. Uploading all six
HRTA filings through the endpoint into an empty database reproduces the seed data exactly (see
`docs/v1_docs/AI_INGESTION_DOCS.md`, section 5). `PriceIngestionServiceTest` ingests stubbed prices
into HRTA (rolled back) and checks the new rows and valuation, the re-adjustment re-fetch, the
intraday cutoff and that the company-scoped valuation SQL matches `V1.0.6`; `PriceIngestionQueueTest`
covers the 429 back-off, `YahooPriceProviderTest` parses a real Yahoo response.
`AuthControllerTest`, `AccessControlTest` and `EndpointAccessRulesTest` cover login, sessions and
the permission of every API; `AdminUserControllerTest`, `AdminRoleControllerTest` and
`ProfileControllerTest` the user, role and profile rules (see `docs/v1_docs/AUTH_DOCS.md`).
`FinancialStatementUploadTest` (AI agent mocked) checks the asynchronous upload: immediate 202, the
background job and its recorded stages, one stored file per checksum reused on re-upload, wrong
files rejected without storing, the job list filters and pages (`limit` / `offset`, `total`) and the file download (identical bytes, the
name of each upload). `PriceIngestionControllerTest` also checks that price jobs are recorded in
`ingestion_job`. The tests delete the job rows they create.
Screening: `NewsParsersTest` (excerpts of the four news sites' headline lists), `YahooFundamentalsClientTest`
(saved Yahoo responses), `QuantScreeningTest` (metrics, scorecards, funnel, shortlist),
`ScreeningAgentsTest` (scripted model: options, cost metering and budget, ReAct with a tool call,
reflection critic, synthesis rules), `FundamentalRepositoryTest` and `ScreeningControllerTest`
(pipeline mocked: validation, job, report, PDF); see `docs/v1_docs/SCREENING_DOCS.md`, section 9.
RAG: `TextChunkerTest`, `EmbeddingClientTest`, `PdfTextTest` (the HRTA FY2025 PDF), `NewsCollectorTest`
(date range and paging on saved pages, only the news sites read), `RagNewsIngestionTest`, `NewsRetryTest` (passing failures of the news sites retried), `RagRepositoryTest` (pgvector store and cosine search, rolled back)
and `RagControllerTest` (a PDF through the worker into the store with stub embeddings, validation,
permission); `IngestionJobTypeConstraintTest` that every script re-creating the job-type check lists every job type; see `docs/v1_docs/RAG_DOCS.md`, section 7.
Analysis: `AnalysisAgentsTest` (scripted model: research only with documents, ReAct with a vector-store
search, invented refs dropped, tool limits, synthesis bounds and fallback, fact sheet) and
`AnalysisControllerTest` (HRTA end to end on its stored statements and PDF chunks with a model answering
by role: report, usage per stage, paging, PDF, validation); see `docs/v1_docs/ANALYSIS_DOCS.md`, section 8.
`ScreeningAgentsTest` also covers the shared gateway: OpenInference ignored in the provider routing, a stream
reset retried (every attempt recorded), the retry limit, no retry of a refused request, and an answer without
strengths and concerns asked again.

The tests start the application, whose startup marks ingestion jobs left active as `FAILED`. Do not
run them against the database of a backend that is processing jobs; point them at a separate
database instead, e.g. `DB_URL=jdbc:postgresql://localhost:5432/neracalab_test ./mvnw test` (create
it first with `CREATE DATABASE neracalab_test`; the scripts load the schema and seed data). The start / stop scripts are checked with
ShellCheck and bash 3.2; see `docs/v1_docs/DOCKER_DOCS.md`, section 6.

## Database

The schema is managed by plain SQL scripts in `backend/src/main/resources/db/`, executed by
Spring SQL init (`spring.sql.init.*`) on every application start, in the order listed in
`application.yaml`. Every script is idempotent (`IF NOT EXISTS`, upserts), so re-running is safe.
Hibernate does not touch the schema (`ddl-auto: none`).

All tables and views live in the PostgreSQL `public` schema (no separate schemas), so every
table can reference any other with a plain foreign key. Objects are referenced unqualified
(`company`, `income_statement`, ...).

| Script                                | Content                                                                    |
|---------------------------------------|----------------------------------------------------------------------------|
| `V1.0.1__schema.sql`                  | core tables: company, periods, statements, corporate actions               |
| `V1.0.2__schema_market_valuation.sql` | segments, prices, share counts, market / valuation snapshots, metrics      |
| `V1.0.3__views.sql`                   | analysis views (recreated on every start)                                  |
| `V1.0.7__schema_ingestion.sql`        | uploaded workbooks (`ingestion_file`) and ingestion progress (`ingestion_job`) |
| `V1.0.8__schema_auth.sql`             | users (profile, avatar), roles, role permissions, user roles, login sessions |
| `V1.0.9__schema_ingestion_created_by.sql` | `ingestion_job.created_by`: the user who started each ingestion; `app_migration` |
| `V1.0.10__schema_screening.sql`       | AI screening: universe, daily snapshot, news cache, runs, candidates, agent scores, lessons, LLM usage |
| `V1.0.11__schema_fx.sql`              | `fx_rate_daily`: ECB reference rates for listings quoted in another currency (INDY, SMDR) |
| `V1.0.14__schema_rag.sql`             | RAG vector store (pgvector): `rag_document`, `rag_chunk` (`vector(1536)`, HNSW); job types `RAG_PDF`, `RAG_NEWS` |
| `V1.0.15__schema_analysis.sql`        | AI analysis of one stock: `analysis_run`, `analysis_agent_score`, `llm_usage.analysis_id`; job type `ANALYSIS` |
| `V1.0.16__schema_syirkah.sql`         | banks: `balance_sheet.temporary_syirkah_funds` (sharia depositors; neither liabilities nor equity) |
| `V1.0.4__data_HRTA_financials.sql`    | HRTA statements Q1 2024 .. H1 2026 from the six IDX filings in `data/HRTA` |
| `V1.0.5__data_HRTA_market.sql`        | HRTA share counts and daily prices 2024-01-02 .. 2026-09-30                |
| `V1.0.12__data_SMDR_shares.sql`       | SMDR share counts (16,375,600,000 split-adjusted, from 2020-12-31) and its 2023 1:5 stock split |
| `V1.0.13__data_BNGA_shares.sql`       | BNGA audited year-end share counts 2021 .. 2025 (note 33 of the annual reports; two share classes and treasury shares) |
| `V1.0.6__data_metrics_valuation.sql`  | derived for all companies: market snapshots, valuation snapshots, metrics  |

Full column-level reference: [`docs/v1_docs/DB_SCHEMA_DOCS.md`](docs/v1_docs/DB_SCHEMA_DOCS.md).

### Tables

| Table                 | Purpose                                                                    |
|-----------------------|----------------------------------------------------------------------------|
| `company`             | company master data                                                        |
| `corporate_action`    | splits, rights issues, dividends, buybacks, ...                            |
| `reporting_period`    | one row per company per period (`FY`, `Q1`-`Q4`, `H1`, `9M`, `TTM`)        |
| `income_statement`    | income statement                                                           |
| `balance_sheet`       | statement of financial position                                            |
| `cash_flow_statement` | cash flow statement                                                        |
| `segment`             | segments / revenue lines of a company                                      |
| `segment_financial`   | segment figures per reporting period (revenue breakdown from the notes)    |
| `price_daily`         | daily OHLCV prices                                                         |
| `share_snapshot`      | share counts at a date (outstanding, weighted, treasury, float)            |
| `market_snapshot`     | daily market cap and enterprise value (derived)                            |
| `valuation_snapshot`  | price vs trailing-twelve-month fundamentals: P/E, P/B, EV/EBITDA, EV/OP .. |
| `financial_metric`    | every calculated metric in long format (margins, returns, leverage, EV/OP) |
| `ingestion_file`      | uploaded `.xlsx` workbooks and RAG `.pdf` files (`BYTEA`), one row per SHA-256 checksum |
| `ingestion_job`       | progress of every background job: uploads, prices, screening ETL, screenings, RAG PDF / news, analyses (status, stage, result JSONB, started by which user) |
| `rag_document`, `rag_chunk` | RAG vector store: PDFs and news articles of a company, text chunks with their embeddings |
| `users`               | accounts: unique username / email, BCrypt password, profile, avatar        |
| `roles`, `role_permissions`, `user_roles` | roles, their permissions (`ADMIN`, `INGESTION`, `COMPANIES`, `SCREENING`), assignments |
| `user_sessions`       | login sessions (SHA-256 of the token, expiry)                              |
| `stock_listing`, `fundamental_snapshot` | screening universe and its daily market data / fundamentals (Yahoo ETL) |
| `news_article`, `news_article_ticker`, `news_source_fetch`, `news_brief` | news cache of the screening research agent |
| `screening_run`, `screening_candidate`, `screening_agent_score` | screening reports: parameters, shortlist, scores and reasoning |
| `analysis_run`, `analysis_agent_score` | analysis reports of one stock: what the agents saw, research brief, synthesis, scores and reasoning |
| `screening_lesson`, `llm_usage` | Reflexion memory (screening and analysis); tokens and cost of every model call |

Conventions: amounts in full units of the company currency; income-statement expenses are
positive; cash-flow outflows are negative; `NULL` = not reported, `0` = reported as zero.
IDX "Kuartal I / II / III" filings are year-to-date, stored as `Q1` / `H1` / `9M`.

### Analysis views (Roaring Kitty / Keith Gill style deep value)

| View                 | Content                                                                                              |
|----------------------|------------------------------------------------------------------------------------------------------|
| `v_key_metrics`      | net cash, tangible book, NCAV (net-net), liquidity, FCF, margins, ROE/ROA/ROIC, working-capital days |
| `v_valuation`        | P/E, P/B, P/TBV, EV/EBIT, EV/OP, EV/EBITDA, FCF yield at the price on period end                     |
| `v_latest_valuation` | same multiples using the latest price and latest full report                                         |
| `v_ttm_financials`   | trailing-twelve-month revenue, operating income, EBITDA, profit and FCF per period                   |

Ratios are fractions (`0.25` = 25%). `*_annualized` columns scale partial-year flows by
12 / period months; `valuation_snapshot` uses trailing twelve months instead. Examples:

```sql
-- valuation history incl. EV / operating profit
SELECT valuation_date, share_price, market_cap, enterprise_value, pe_ratio, ev_op
FROM valuation_snapshot ORDER BY valuation_date;

-- one metric over time
SELECT metric_date, metric_value FROM financial_metric
WHERE metric_name = 'ev_op' ORDER BY metric_date;
```

### Adding a new financial statement

At runtime: upload the filing (`POST /api/v1/financial-statements/upload`), then load its prices
(`POST /api/v1/prices/ingestions?exchange=IDX&ticker=<TICKER>`). Revenue segments are stored only
when the filing fills the breakdown sheets 1617000 / 1618000 (INDF, for example, leaves them blank).
As seed data that exists on every start:

1. Put the filing in `data/<TICKER>/xlsx/` (and prices in `data/<TICKER>/price/`).
2. Create the data script(s), e.g. `V1.0.10__data_<TICKER>_financials.sql`, following the
   upsert pattern of `V1.0.4__data_HRTA_financials.sql` / `V1.0.5__data_HRTA_market.sql`.
3. Add them to `spring.sql.init.data-locations` in `application.yaml` **before**
   `V1.0.6__data_metrics_valuation.sql`, which derives snapshots and metrics from all loaded
   data, and restart the backend.

### Running the scripts manually

Without starting the backend, the scripts can be applied directly to the Docker database:

```bash
cd backend/src/main/resources/db
cat V1.0.1__schema.sql V1.0.2__schema_market_valuation.sql V1.0.3__views.sql V1.0.7__schema_ingestion.sql V1.0.8__schema_auth.sql V1.0.9__schema_ingestion_created_by.sql \
    V1.0.4__data_HRTA_financials.sql V1.0.5__data_HRTA_market.sql V1.0.6__data_metrics_valuation.sql \
  | docker exec -i neracalab-postgres psql -U <user> -d neracalab -v ON_ERROR_STOP=1
```
