# Neraca Lab

Application for analyzing financial statements using LLM.

- Backend using: Spring Boot 4 (Java 21) + PostgreSQL 17 + Redis 7 + Spring AI 2.0 (OpenRouter)
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
The AI upload needs `backend/.env` with `OPENAI_API_KEY` (copy `backend/.env.example`); the rest
works without it. Options, ports and troubleshooting:
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
| [`AUTH_DOCS.md`](docs/v1_docs/AUTH_DOCS.md)                 | login, users, roles, permissions, profile: rules, APIs, root user, sessions |
| [`DB_SCHEMA_DOCS.md`](docs/v1_docs/DB_SCHEMA_DOCS.md)       | database: every table, column, constraint and view, loading data          |

## Project structure

```text
neraca_lab/
├── docker-compose.yaml          full stack: includes backend/docker-compose.yaml + frontend
├── scripts/                     start-* and stop-* for Windows (.bat), macOS and Linux (.sh)
├── backend/                     Spring Boot application
│   ├── Dockerfile
│   ├── docker-compose.yaml      postgres + redis + backend
│   ├── .env.example             template for backend/.env (AI key; .env is git-ignored)
│   └── src/main/
│       ├── java/.../company/    company list / detail APIs, exchange and ticker codes
│       ├── java/.../ingestion/  AI upload endpoint: xlsx reader, mapper, agent, tools, repository,
│       │                        stored files (checksum), background upload queue
│       ├── java/.../price/      daily price ingestion: queue, providers, valuation refresh
│       ├── java/.../job/        ingestion job progress (ingestion_job): list / detail / file download APIs
│       ├── java/.../auth/       login, sessions, access check of every API, root user bootstrap
│       ├── java/.../user/       user management, role management, profile (avatar)
│       └── resources/
│           ├── application.yaml
│           └── db/              SQL scripts (no Flyway)
│               ├── V1.0.1__schema.sql
│               ├── V1.0.2__schema_market_valuation.sql
│               ├── V1.0.3__views.sql
│               ├── V1.0.7__schema_ingestion.sql   (schema script, runs after V1.0.3)
│               ├── V1.0.8__schema_auth.sql        (schema script: users, roles, sessions)
│               ├── V1.0.9__schema_ingestion_created_by.sql   (who started an ingestion job)
│               ├── V1.0.4__data_HRTA_financials.sql
│               ├── V1.0.5__data_HRTA_market.sql
│               └── V1.0.6__data_metrics_valuation.sql
├── frontend/                    Next.js web app (login, companies, ingestion, admin center, profile)
│   ├── Dockerfile               standalone Next.js server (node server.js)
│   ├── .env.example             template for frontend/.env.local (NERACA_API_URL)
│   └── src/                     app/ (pages, api/ route handlers), components/ (ui = shadcn-fintech), lib/ (API client)
├── data/<TICKER>/               source data per company
│   ├── xlsx/                    IDX XBRL financial statements (FinancialStatement-<period>-<TICKER>.xlsx)
│   ├── pdf/                     the same filings as PDF
│   └── price/                   daily prices (<TICKER>.JK_daily_yahoo.csv)
│                                HRTA: xlsx, pdf and prices, loaded as seed data on every start;
│                                INDF: xlsx 2024-III .. 2026-II only, load them with the upload (Ingestion page)
└── docs/v1_docs/                DOCKER_DOCS.md, FRONTEND_DOCS.md, COMPANY_API_DOCS.md,
                                 AI_INGESTION_DOCS.md, PRICE_INGESTION_DOCS.md,
                                 INGESTION_JOBS_DOCS.md, AUTH_DOCS.md, DB_SCHEMA_DOCS.md
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
never commit keys. Use real passwords outside local development.

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
| `INGESTION` | Ingestion page, upload / price / job APIs (and the company list)         |
| `COMPANIES` | Companies pages and company APIs                                         |

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
| `/ingestion`                     | upload an IDX XBRL `.xlsx`, fetch prices (exchange / ticker dropdowns), live table of every ingestion job with details and file download |
| `/login`                         | username / password login (every other page needs it)                                                                                                             |
| `/admin/users`, `/admin/roles`   | Admin Center (`ADMIN`): add, view, update and delete users and roles                                                                                               |
| `/profile`                       | own profile: picture, address, phone, date of birth (username and email are read-only)                                                                             |

Pages fetch the backend in Server Components (`NERACA_API_URL`, server-side only), so the
backend needs no CORS setup. The login stores the session token in an httpOnly cookie; the
Next.js server sends it to the backend as a bearer token, and the sidebar shows only the pages the
user's permissions allow. The ingestion page's uploads, price requests, job polling and file
downloads go through Next.js Route Handlers (`src/app/api/`), so the browser never calls the backend
directly either. Details: [`docs/v1_docs/FRONTEND_DOCS.md`](docs/v1_docs/FRONTEND_DOCS.md).

## Tests

The backend tests need the Postgres on localhost:5432 (the full stack, or
`cd backend && docker compose up -d postgres redis`).

```bash
(cd backend && ./mvnw test)                      # 100 tests
(cd frontend && npm run lint && npm run build)   # type check, lint, production build
```

`FilingMapperHrtaTest` maps the six HRTA filings and compares every field with the validated seed
data; `BackendApplicationTests` starts the application context. `CompanyControllerTest` calls the
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
files rejected without storing, the job list filters and the file download (identical bytes, the
name of each upload). `PriceIngestionControllerTest` also checks that price jobs are recorded in
`ingestion_job`. The tests delete the job rows they create.

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
| `V1.0.4__data_HRTA_financials.sql`    | HRTA statements Q1 2024 .. H1 2026 from the six IDX filings in `data/HRTA` |
| `V1.0.5__data_HRTA_market.sql`        | HRTA share counts and daily prices 2024-01-02 .. 2026-09-30                |
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
| `ingestion_file`      | uploaded `.xlsx` workbooks (`BYTEA`), one row per SHA-256 checksum         |
| `ingestion_job`       | progress of every upload and price ingestion (status, stage, result JSONB, started by which user) |
| `users`               | accounts: unique username / email, BCrypt password, profile, avatar        |
| `roles`, `role_permissions`, `user_roles` | roles, their permissions (`ADMIN`, `INGESTION`, `COMPANIES`), assignments |
| `user_sessions`       | login sessions (SHA-256 of the token, expiry)                              |

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
