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
| [`TECHNICAL_DOCS.md`](docs/v1_docs/TECHNICAL_DOCS.md)       | technical analysis: Companies › Technicals page, indicator engine (ta4j), IDX tick sizes and ARA / ARB, APIs, refresh, tests |
| [`MACRO_DOCS.md`](docs/v1_docs/MACRO_DOCS.md)               | macro data (Ingestion › Macro): series and sources (FRED, Bank Indonesia, CNBC, CFTC), point in time, signals and the liquidity / risk regimes |
| [`MACRO_ANALYSIS_DOCS.md`](docs/v1_docs/MACRO_ANALYSIS_DOCS.md) | AI macro analysis (Macro › AI Analysis): research agent over the stored macro data, commodities, Forex and indexes, the Tudor Jones / Druckenmiller / Soros / Dalio agents, investability 0 - 100, cost, report, PDF |
| [`indicator-snapshot.md`](docs/indicator-snapshot.md)       | the versioned IndicatorSnapshot JSON (schemaVersion 3, technical only) the future technical agents read |
| [`macro.md`](macro.md)                                      | requirements of the macro data (Druckenmiller, Tudor Jones) and how they were implemented |
| [`commodities.md`](commodities.md)                          | which further commodities matter for screening IDX stocks (nickel, copper, tin, HBA, ...): insight, not built yet |

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
│       ├── java/.../technical/  technical analysis: indicator engine (ta4j + custom), IDX rules, macro signals,
│       │                        preset summaries, APIs, refresh
│       ├── java/.../market/     Forex pairs and commodities: catalog, ingestion (Yahoo, World Bank), bad-print cleaning
│       ├── java/.../macro/      macro data: catalog, sources (FRED, Bank Indonesia, SEKI, CNBC, CFTC, Yahoo), queue, API;
│       │                        outlook/ (the Macro page's rules, commodities board), analysis/ (AI macro analysis:
│       │                        research agent, macro agents, reflection, Reflexion, synthesis, queue, PDF)
│       ├── java/.../crawl/      the polite HTTP client of the crawlers (one request per host, 2 s apart)
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
│               ├── V1.0.17__schema_screening_selection.sql (screening of selected stocks: screening_run.tickers)
│               ├── V1.0.18__schema_ingestion_error.sql (error log of the .xlsx ingestion API: ingestion_error)
│               ├── V1.0.19__schema_ingestion_cost.sql  (model cost of the .xlsx ingestion: llm_usage.job_id)
│               ├── V1.0.20__schema_screening_currency.sql (fundamental_snapshot.financial_currency: USD reporters)
│               ├── V1.0.23__schema_technical.sql  (technical analysis: IHSG, events, macro, snapshots, chart settings)
│               ├── V1.0.25__schema_markets_macro.sql (Forex / commodity bars, macro catalog, release dates)
│               ├── V1.0.27__schema_macro_analysis.sql (AI macro analysis: runs, agent scores, lessons, llm_usage)
│               ├── V1.0.4__data_HRTA_financials.sql
│               ├── V1.0.5__data_HRTA_market.sql
│               ├── V1.0.12__data_SMDR_shares.sql  (SMDR share counts from public sources, 2023 stock split)
│               ├── V1.0.13__data_BNGA_shares.sql  (BNGA audited share counts from its annual reports)
│               ├── V1.0.21__data_RAJA_shares.sql  (RAJA share counts and its 1:5 stock split of 2026-07-16)
│               ├── V1.0.22__data_TUGU_shares.sql  (TUGU's 1:2 stock split of 2023-05-24, no filing restates it)
│               ├── V1.0.24__data_BACH_shares.sql  (BACH share count after its IPO of 2026-07-08)
│               ├── V1.0.6__data_metrics_valuation.sql
│               └── V1.0.26__data_markets_macro.sql (catalog: 28 instruments, 25 macro series)
├── frontend/                    Next.js web app (login, companies, screening, ingestion, admin center, profile)
│   ├── Dockerfile               standalone Next.js server (node server.js)
│   ├── .env.example             template for frontend/.env.local (NERACA_API_URL)
│   └── src/                     app/ (pages, api/ route handlers), components/ (ui = shadcn-fintech), lib/ (API client)
├── data/IDX_XBRL/<TICKER>/      source data per company: xlsx/ (IDX XBRL filings), pdf/, price/
├── data/ICMD/                   Indonesian Capital Market Directory PDFs, 2010 .. 2019 (reference, not ingested)
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
│                                BRIS (2022 .. 2025 annual, 2026-II; sharia bank, syirkah share filed negative)
│                                STAA (2022 .. 2025 annual), PWON (2022 .. 2025 annual, 2026-II; cash flow cash
│                                includes deposits) and CBDK (2024, 2025 annual, 2026-II; Property and Real Estate
│                                Industry taxonomy)
│                                NSSS (2022 .. 2025 annual, 2026-II; profit or loss by nature: stored by the
│                                full-text extraction, share counts from Yahoo Finance checked against its EPS)
│                                AUTO, DKFT, DMAS, IMPC, PTBA, RAJA, SMSM, WIIM (2022 .. 2025 annual, 2026-II),
│                                MSIN (the same; profit or loss by nature, 1322000) and MSTI (2023, 2025 annual,
│                                2026-II) - RAJA: share counts and its 1:5 split of 2026-07-16 from V1.0.21
│                                (NCKL FY2024: EPS one decimal place off, see AI_INGESTION_DOCS)
│                                TUGU (2022 .. 2025 annual, 2026-II; insurer, Insurance Industry taxonomy 6xxxxxx,
│                                decided by the full-text extraction; 2:1 split of 2023-05-24 from V1.0.22),
│                                MOLI (the same years; EPS filed in other units per filing), CNMA (2023 .. 2025
│                                annual, 2026-II; balance sheet by order of liquidity)
│                                BBYB, BTPS, NOBU (banks), MARK, NRCA, GOTO (also pdf/) (2022 .. 2025 annual,
│                                2026-II), BLES (2024, 2025 annual, 2026-II), DWGL (2022, 2024, 2025 annual, 2026-II)
│                                ACES, ASSA, BIRD, BJTM (bank), EKAD, ERAA, MPMX, MTDL, OMED, WINS, TPIA (USD) and WIFI
│                                (2022 .. 2025 annual, 2026-II; WIFI: a financing total without its dividends, amounts
│                                entered twice, a 3 rupiah slip), DCII and UNVR (the same, also pdf/), BREN (2023 ..
│                                2025 annual, 2026-II, also pdf/), DATA, DGWG, ERAL (2024, 2025 annual, 2026-II), BACH
│                                (2026-II only: listed 2026-07-08, IPO share count from V1.0.24, no TTM yet)
│                                BEST, BUKA, MAIN, NICL, PRDA, SMRA (2022 .. 2025 annual, 2026-II); BKSL (the same;
│                                its 2024-Tahunan file is a ratio-analysis export, not XBRL: refused, FY2024 comes
│                                from FY2025's comparative; shares of several par values, counts from Yahoo Finance);
│                                DSSA (USD; splits 1:10 of 2024-07-18 and 1:25 of 2026-04-09 from Yahoo Finance);
│                                CUAN (USD from FY2024; 2023-Tahunan is in IDR and refused, FY2023 comes from
│                                FY2024's USD comparative, no FY2022; split 1:10 of 2025-07-15)
│                                BAJA (also pdf/), KRAS (USD; also pdf/), BULL (USD), GJTL, GMFI (USD), JKON, PNLF (insurer,
│                                decided by the full-text extraction), RISE (Property Industry taxonomy), SMBR
│                                (2022 .. 2025 annual, 2026-II), DOOH, INET (2023 .. 2025 annual, 2026-II), FORE
│                                (2025 annual, 2026-II); BBCA's pdf/ (2022 .. 2025 annual, 2026-II)
│                                ADMR, ADRO, AKRA, AMRT, ASII, BBTN (bank), BRMS (USD; EPS filed per 1,000 shares),
│                                UNTR, TLKM (P&L by nature, no gross profit) and TRUK (the last two also pdf/) (2022 ..
│                                2025 annual, 2026-II), AMMN (2023 .. 2025 annual, 2026-II), AADI (2024, 2025 annual,
│                                2026-II), ARTO (sharia bank; 2022, 2025 annual, 2026-II - its 2024 file and TRUK's
│                                2024 xlsx were broken downloads and are not committed)
│                                BRPT, ENRG, ESSA, HRUM, INCO, INKP (USD), BSDE, CTRA (Property and Real Estate
│                                Industry taxonomy), BUVA (P&L by nature, no gross profit), DEWA, EMTK, HEAL and EXCL
│                                (Infrastructure Industry taxonomy, P&L by nature; FY2023 tags its revenue only in
│                                the breakdown) (2022 .. 2025 annual, 2026-II), COIN, EMAS (USD) (2025 annual, 2026-II)
│                                BUMI, ITMG (FY2023 full amounts under 'In Thousand'), MDKA, MEDC, PGAS (2026-I),
│                                PGEO (USD; PGEO P&L by nature up to FY2024), ISAT (Infrastructure Industry taxonomy,
│                                P&L by nature, no gross profit), KLBF, MAPI, SCMA (2022 .. 2025 annual, 2026-II),
│                                MBMA (USD; 2023 .. 2025 annual, 2026-II) - MDKA's and PGAS's 2024 xlsx are Excel
│                                lock files (165 bytes), not workbooks, and are not committed
│                                ABMM (USD; also pdf/), BJBR (bank), BWPT, IRSX, KETR (Infrastructure Industry
│                                taxonomy), LPPF (FY2023 leaves rows off the face of its balance sheet), MNCN (P&L by
│                                nature, no gross profit), RMKE (2022 .. 2025 annual, 2026-II - RMKE's 2024 xlsx is an
│                                Excel lock file and is not committed), CYBR (2023 .. 2025 annual, 2026-II), RANS
│                                (2026-II); GGRM's pdf/ (2022 .. 2025 annual, 2026-II)
│                                xlsx: load them with the upload (Ingestion page)
└── docs/v1_docs/                DOCKER_DOCS.md, FRONTEND_DOCS.md, COMPANY_API_DOCS.md,
                                 AI_INGESTION_DOCS.md, PRICE_INGESTION_DOCS.md,
                                 INGESTION_JOBS_DOCS.md, AUTH_DOCS.md, DB_SCHEMA_DOCS.md,
                                 SCREENING_DOCS.md, ANALYSIS_DOCS.md, RAG_DOCS.md, TECHNICAL_DOCS.md
                                 (see Documentation); docs/indicator-snapshot.md
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
| `SCREENING` | Screening pages (Screening Stocks, Selected Stocks, Analysis): runs, analyses, reports, PDF export |

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
curl -H "$AUTH" -F "file=@data/IDX_XBRL/HRTA/xlsx/FinancialStatement-2026-II-HRTA.xlsx" \
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
agent runs in the background. A filing holds no prices, and without prices a company has no market cap
and no valuation: when a filing is stored, the daily prices of its company are queued too (the whole
history for a new company, else the missing days; nothing when they are up to date), and that price job
ends with the company's valuations. Every ingestion (uploads and prices) records its progress and the
user who started it in `ingestion_job`; `GET /api/v1/ingestions` lists them, `GET /api/v1/ingestions/{id}` returns one
with its result and `GET /api/v1/ingestions/{id}/file` downloads the uploaded workbook. Details: [`docs/v1_docs/INGESTION_JOBS_DOCS.md`](docs/v1_docs/INGESTION_JOBS_DOCS.md).

When the standard extraction does not store a workbook - it cannot map it ("No balance sheet data"), a
statement fails a validation (INCOMPLETE), it fails, or it runs longer than 3 minutes - the **full-text
extraction** takes over: its own model (Claude Haiku 5.5, `neracalab.ingestion.fallback.model` /
`INGESTION_FALLBACK_MODEL`; the standard extraction stays on `OPENAI_MODEL`, DeepSeek) gets the text of the
whole workbook - every sheet of the file, whatever its taxonomy - and **decides every value that is not stored
yet** - which rows make up every database field. A workbook of a taxonomy the rules do not map (Financing Industry, e.g. BFIN: sheets
8220000 / 8322000 / 8510000; Insurance Industry, e.g. TUGU: 6220000 / 6312000 / 6510000) goes straight to the model; its share
capital, per-share unit and notes are still read by the rules, so its share counts come from the filing. No rule of the Java code decides or
overrules a value there: the code reads the amounts of the rows the model names from the workbook's cells
(so a stored figure always exists in the workbook), shows the model where its own mapping contradicts itself
(assets = liabilities + equity, ...) so it can correct it, and stores its last answer; what still does not
add up is saved, reported (INCOMPLETE) and logged, never dropped. A field for which the model names a row
that cannot be used (a row of a notes sheet, a row that does not exist) is left empty instead of being stored
as the sum of its other rows, and the model is asked again; a profit or loss presented by nature (e.g. NSSS:
no cost of sales and no gross profit row) gets no `cost_of_revenue` and no `gross_profit`; revenue must be the
profit or loss's own 'Sales and revenue' row (EXCL, BUVA, TLKM: the model had added 'Other income'), and a row left
empty on the face is shown to the model with the total of the filing's revenue breakdown (EXCL FY2023). Operating income
is checked against the filing: rows below operating profit counted in it (ISAT's 'Other gains (losses)') and, by nature,
operating expenses other than the expense rows it subtracts (ISAT FY2023, PGEO FY2023) are shown to the model when the
corrected figure and the rows below it give the filed profit before tax - by nature, where none of the model's rows do,
the template's own figures - and the correction is used when the model keeps its answer. In a filing by nature, rows the model names as cost of revenue that its operating income subtracts are counted in operating expenses (MNCN's 'Program and broadcasting expenses'). The result is saved
and verified by the standard steps (`neracalab.ingestion.fallback.*`). Every upload that was refused, taken
over, INCOMPLETE or FAILED is recorded in the table **`ingestion_error`** - the work list of Claude Code, with
`fixed_by_claude_code` set once the cause is fixed:

```sql
SELECT error_id, file_name, ticker, period, error_type, message FROM ingestion_error WHERE NOT fixed_by_claude_code;
```

A workbook the model decides by design - the Financing or Insurance Industry taxonomy, a profit or loss presented by
nature (NSSS, MSIN), a General Industry balance sheet by order of liquidity (CNMA) - that is stored and verified is not
an error and leaves no row. A row of the error log names the model of the full-text extraction and every model call of the job that
failed, with what the provider said (`details.failedModelCalls`).

**Model cost per upload.** Every model call of an upload - the standard extraction's plan, tool turns and
review (DeepSeek) and the full-text extraction's answers (Haiku) - is a row of `llm_usage` tied to the job
(`llm_usage.job_id`), with its stage, model, tokens and the cost OpenRouter reports, as for a screening run.
The IDX XBRL Ingestion page shows the cost of each job in the jobs table and the breakdown per stage and model
under "Model cost" in the job details; `GET /api/v1/ingestions` returns it as `cost`,
`GET /api/v1/ingestions/{id}` also as `usage`. A failed call is recorded with its error and no cost (what the
provider billed for it, if anything, is not known); uploads from before this was added show no cost.

```sql
SELECT u.model, count(DISTINCT u.job_id) AS uploads, count(*) AS calls, round(sum(u.cost_usd), 4) AS cost_usd
FROM llm_usage u WHERE u.job_id IS NOT NULL GROUP BY u.model;
```

Details: [`docs/v1_docs/AI_INGESTION_DOCS.md`](docs/v1_docs/AI_INGESTION_DOCS.md), sections 6 and 7.

Ingestion jobs run in parallel: each ingestion queue (uploads, prices, RAG PDF / news, screening data) has
`neracalab.jobs.workers` worker threads (default 5, env `INGESTION_WORKERS`; set `workers: 6` in
`application.yaml` for six), so five filings uploaded together - also five years of one company - are
stored at the same time, each by its own agent. The write steps of filings of one company never overlap,
and the requests to Yahoo Finance and to each news site stay paced one at a time.
`neracalab.ingestion.same-company-in-order=true` stores the filings of one company one after the other
instead. Details: [`docs/v1_docs/INGESTION_JOBS_DOCS.md`](docs/v1_docs/INGESTION_JOBS_DOCS.md), section 4.

| HTTP | Meaning                                                                         |
|------|---------------------------------------------------------------------------------|
| 202  | job queued; final status `SUCCEEDED`, `INCOMPLETE` or `FAILED` in the job       |
| 200  | the same file is already queued / being stored: that job                        |
| 422  | not an `.xlsx` or not an IDX XBRL workbook (nothing stored; logged in `ingestion_error`). A workbook the standard extraction cannot map is accepted and goes to the full-text extraction |

Both IDX layouts are read: the current one (2023 onwards) and the pre-2023 one of FY2022 and earlier
filings (date column headers, sheets `1410000 1 CurrentYear` / `2 PriorYear`, highly compressed
styles). Three IDX taxonomies are supported: General Industry (`1xxxxxx` sheets), Infrastructure
Industry (`3xxxxxx` sheets, e.g. SMDR), which has the same statements under other codes, and Financial
and Sharia Industry (`4xxxxxx` sheets, banks, e.g. BNGA), mapped with bank definitions (revenue =
interest + non-interest operating income; current items, short-term debt and EBITDA left NULL).
When a filing gives no share counts (several share classes, treasury shares, imprecise EPS), the
ingestion takes Yahoo Finance's published counts, but only those that reproduce the filing's own EPS - also
on the count of before a split Yahoo publishes after the filing (CUAN 1:10 in 2025, DSSA 1:10 in 2024 and 1:25 in
2026; the split is then recorded with its date and earlier EPS split-adjusted), or, for a period with a buyback,
when the EPS lies between profit / the start and the end count (BUKA FY2025);
they never replace a stored count (`neracalab.ingestion.web-share-counts`, on by default). A par value only one
column's EPS fits is not used (BKSL FY2025: par 50 gave 50% too many shares). A filing in another currency than the
company's stored data is refused with 422 (CUAN's FY2023 filing in IDR; CUAN reports in USD from FY2024).
Workbooks are not trusted blindly: a declared rounding level contradicted by the amounts (ASGR FY2023:
"In Million" over full amounts) is corrected when the filing's own EPS confirms it, and an amount a
filing reports twice in one column (ASGR H1 2025: "Other expenses" and "Other gains (losses)") is
counted once when only that makes profit before tax reconcile; a bank's insurance claims shown for
information beside premiums already net of them (BMRI's FY2024 comparative) are not deducted again when
only that makes profit from operation reconcile; lines outside the filing's own totals are not counted when the
totals reconcile exactly without them - a residual line that repeats the net of the others (TINS FY2024), a cash
flow line the filed section total excludes (TINS, BRI), unclassified insurance lines shown for information (BRI
H1 2025) - while a line the totals do include stays with the agent (BBCA); an EPS takes the sign of its profit
(TINS FY2023: a loss filed without it); an EPS filed in the rounding unit (SIMP
H1 2026: 0.0000564 for Rp 56.35; MSIN, by nature: 0.00000655 for Rp 6.55) is scaled; an amount filed on two
lines of the same kind (WIIM H1 2025: 'Finance income' and 'Interest income') is counted once; a 'Proforma
equity' row of a restated comparative (IMPC FY2023, MSIN FY2021) is counted with the non-controlling interests; a filing that tags only gross profit (SIMP FY2023) is stored
without revenue, which a later filing's comparative fills (comparatives fill empty fields, never change
stored values; an EPS and its share count always come from one filing, and a share split a later filing
restates - BMRI 2:1, MAPA 1:10 in 2023 - adjusts the per-share figures and share counts of that and earlier
periods, whatever the upload order, and is recorded in `corporate_action`; a split after a company's latest filing, which no filing restates yet, is recorded by a data script and treated the same - RAJA 1:5 on 2026-07-16; a year with treasury stock gets no
weighted share count - ULTJ FY2022 - and a line filed as 0 needs no classification). A bank's temporary
syirkah funds are stored beside its liabilities (`temporary_syirkah_funds`), not in them. A filed EPS is checked against the filing's share count: a workbook whose two EPS columns
contradict each other (NCKL FY2024) gives no share counts and its EPS is cleared, and an EPS off by a power of
ten is corrected (INDF H1 2024: 0.000439 -> 439); a workbook re-run refreshes the periods it wrote. All of
these are reported as warnings. A period's revenue breakdown always comes from one filing: the period's own filing replaces
it, a later filing's comparative only fills a period without one (issuers re-cut segments between
years, mixing them counts revenue twice). Cash flow ending cash net of bank overdrafts (e.g. GGRM) or
including deposits held among other current financial assets (e.g. PWON) is accepted as filed; a model call that times out or hits a provider error is retried
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
A company reporting in USD whose listing trades in IDR (CUAN, DSSA, INDY) has its prices stored in USD; the
IDX quote comes with them (`latestPrice.quote`, `quotePrice` on the market snapshot and the valuations: the stored
price x the rate it was converted with, 2 decimals - CUAN: USD 0.03009362 x 17,944 = Rp 540), and the company
page shows that quote as the price, with the USD figure and the rate beside it.

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
| `GET /api/v1/prices/status?exchange=IDX`                      | how far the stored prices reach: last completed trading day, companies up to date / behind / without prices, the IHSG index            |
| `POST /api/v1/prices/updates?exchange=IDX[&index=true]`       | **"Update all companies to today"**: queues every active company whose prices end before the last completed trading day - only its missing days (ANTM stored up to 9 Oct: 9 Oct to today) - and the IHSG and LQ45 indexes (one job; queued unless both are up to date); up-to-date companies are skipped |
| `POST /api/v1/prices/index-ingestions?exchange=IDX[&full=true]` | the IHSG index (Yahoo Finance `^JKSE`) into `index_daily` through the same queue: **202** new job, **200** job already active          |
| `GET /api/v1/markets`                                          | **Forex and Commodities**: the markets with their instruments (USD/IDR, USD/JPY; 26 commodities: energy, metals, agriculture, materials, fertilizers) and how far their bars reach |
| `POST /api/v1/markets/{FOREX\|COMMODITY}/ingestions?code=USDIDR[&full=true]` | one Forex pair / commodity through the same queue (`PRICE` job, exchange = the market): **202** / **200** |
| `POST /api/v1/markets/{FOREX\|COMMODITY}/updates`              | "Update all currencies / commodities to today": every instrument whose bars end before its last completed day |

On the page (Ingestion › Price Ingestion) the left card fetches one company, or with the market **Forex** /
**Commodities** one currency / commodity (the second dropdown becomes Currency / Commodity). The right-hand cards update
every company of the exchange to today (IDX for now) - or every currency / commodity of the market - and ingest IHSG.

Forex and commodities come from Yahoo Finance (`IDR=X`, `JPY=X`; Brent `BZ=F`, WTI `CL=F`, natural gas `NG=F`, gold `GC=F`,
silver `SI=F`, copper `HG=F`, aluminium `ALI=F`, iron ore `TIO=F`, steel hot-rolled coil `HRC=F`, palm oil `CPO=F`,
soybean oil `ZL=F`, soybean meal `ZM=F`, corn `ZC=F`, wheat `ZW=F`, sugar `SB=F`; corn, wheat, sugar and soybean oil in US
cents as quoted). Coal, LNG (Japan), nickel, tin, rubber (TSR 20), urea, DAP and potash have no free daily source, so
they are the **World Bank Pink Sheet monthly average**; cobalt is the **IMF Primary Commodity Prices** monthly average
(LME), and wood pulp - which has no free price - the US producer price index of wood pulp (FRED `WPU0911`, an index).
Bauxite has no free source and is not included. Palm oil in ringgit is `CPO=F` x `MYR=X` of
the same day: an approximation of Bursa Malaysia FCPO, which has no free source. Bad prints of the Yahoo history (USD/IDR
888.11 between 8,923 and 8,906 on 2010-11-01) are dropped: a close that jumps more than the instrument's threshold away
and straight back. Details: [`PRICE_INGESTION_DOCS.md`](docs/v1_docs/PRICE_INGESTION_DOCS.md), section 1b.

Background workers (5 companies at the same time, `neracalab.jobs.workers`) fetch the
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
(days off by a factor of 10). The UI shows prices and EPS below 1 with 6 decimals, and the IDX quote of a
converted listing beside them (Company APIs above; Technicals rounds converted bars to 2 decimals, so CUAN's
0.03009362 x 17,944 is 540, not 539.9999). Details:
[`docs/v1_docs/PRICE_INGESTION_DOCS.md`](docs/v1_docs/PRICE_INGESTION_DOCS.md), section 2.

Polite crawling: one request at a time with a random 1-2 s pause (shared by all workers), a browser
User-Agent and one HTTP client with a cookie store. On HTTP 429 the queue pauses 15, 30, then 60 minutes and retries the
same job; a 429 after that stops the run (queued jobs fail, re-submit later; each job resumes from
`MAX(trading_date)`). The queue lives in memory (a restart drops queued jobs; re-submit), but every
job and its progress is also recorded in `ingestion_job` and listed by `GET /api/v1/ingestions`.

| Setting (`application.yaml` / env)                    | Default                     |                                                     |
|-------------------------------------------------------|-----------------------------|-----------------------------------------------------|
| `neracalab.prices.provider` / `PRICE_PROVIDER`         | `yahoo`                     | `eodhd` switches to the paid EODHD API              |
| `neracalab.prices.eodhd.api-token` / `EODHD_API_TOKEN` | empty                       | required for `eodhd`                                |
| `neracalab.prices.min-delay`, `max-delay`             | `1s`, `2s`                  | pause between two requests                          |
| `neracalab.prices.backoff`                            | `[15m, 30m, 60m]`           | waits after consecutive HTTP 429                    |
| `neracalab.prices.schedule.enabled` / `PRICE_SCHEDULE_ENABLED` | `false`            | evening run: like "Update all companies" - the `IDX` companies behind, IHSG and LQ45 |
| `neracalab.ingestion.prices-after-upload` / `INGESTION_PRICES_AFTER_UPLOAD` | `true` | a stored filing queues the daily prices of its company (nothing when they are up to date) |
| `neracalab.ingestion.provider-ignore` / `INGESTION_PROVIDER_IGNORE` | `OpenInference`   | OpenRouter providers the ingestion's model calls never use |
| `neracalab.ingestion.fallback.model` / `INGESTION_FALLBACK_MODEL` | `anthropic/claude-haiku-5.5` | model of the full-text extraction (OpenRouter id); empty = `OPENAI_MODEL` |
| `neracalab.jobs.workers` / `INGESTION_WORKERS`        | `5`                         | jobs each ingestion queue (uploads, prices, RAG, screening data) runs at the same time |
| `neracalab.prices.schedule.cron`                      | `0 30 17 * * MON-FRI` (WIB) |                                                     |
| `neracalab.markets.world-bank.file-url` / `WORLD_BANK_PINK_SHEET_URL` | empty     | the Pink Sheet workbook (coal, LNG, nickel, tin, rubber, urea, DAP, potash); empty = the link on the World Bank Commodity Markets page |
| `neracalab.macro.schedule.enabled` / `MACRO_SCHEDULE_ENABLED` | `false`              | evening run (20:00 WIB): every macro series whose next release is due |
| `neracalab.crawl.min-delay`                           | `2s`                        | pause between two requests to the same host (World Bank, FRED, Bank Indonesia, CNBC, CFTC) |

`full=true` re-fetches the whole history instead of starting at the latest stored day; a first
ingestion and a re-adjusted history switch to it automatically. Details, job fields and known
limitations: [`docs/v1_docs/PRICE_INGESTION_DOCS.md`](docs/v1_docs/PRICE_INGESTION_DOCS.md).

## RAG vector store (PDF documents and news)

Two Ingestion pages fill a pgvector store that the AI features can search by meaning; every
document is linked to its company (`rag_document.company_id` -> `company`):

- **PDF Documents (RAG)**: upload a `.pdf` with a text layer (e.g. `data/IDX_XBRL/HRTA/pdf`) for a stored
  company (picked automatically from a file name ending with the ticker). Its text is read page by
  page (PDFBox), split into ~1,500-character chunks, embedded and stored.
- **News (RAG)**: choose an IDX company and a date range (this month, last 7 / 30 days, previous
  month or custom, Jakarta time, up to 366 days). Articles from EmitenNews, Investor.id, IDX Channel
  and Pasardana published in the range are read and stored; articles already stored are skipped.

```bash
curl -H "$AUTH" -F file=@data/IDX_XBRL/HRTA/pdf/FinancialStatement-2025-Tahunan-HRTA.pdf -F ticker=HRTA      http://localhost:8080/api/v1/rag/pdf                                         # queue a PDF
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
[Daily ETL] Yahoo Finance -> PostgreSQL (837 IDX listings, daily market data, weekly fundamentals;
            when the screener returns no listings, the known listings are quoted 50 per request)
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
curl -H "$AUTH" -H "Content-Type: application/json" http://localhost:8080/api/v1/screenings \
     -d '{"exchange":"IDX","tickers":["BBCA","BBRI","TLKM"],"topN":2,"agents":["BUFFETT","RISK"]}'   # selected stocks
curl -H "$AUTH" http://localhost:8080/api/v1/screenings/{id}         # report (progress while running)
curl -H "$AUTH" -OJ http://localhost:8080/api/v1/screenings/{id}/pdf # PDF
```

Each agent score is 60% quantitative scorecard + 40% AI judgement; the overall score is the
average of the investor agents, blended 20% with the Risk agent (safety). Cost is capped at
**$0.45 per run** (`SCREENING_BUDGET_USD`); a large-cap top 10 with all six agents (30 stocks
analysed) cost $0.12. The daily ETL runs Monday-Friday 18:00 WIB and before each screening; the
Ingestion page can start it by hand. **Screening > Selected Stocks** screens stocks chosen from the companies
table (searchable multi-select, up to 100) with the same steps and report; a chosen stock that fails a tradability
check (liquidity, price below Rp 50, no recent trade, watchlist board) is kept and flagged instead of dropped, while
one without the data the scorecards need is left out and named in the notes. Details: [`docs/v1_docs/SCREENING_DOCS.md`](docs/v1_docs/SCREENING_DOCS.md).

**Companies that report in USD** (about 88 IDX listings: ADRO, BYAN, INDY, ITMG, MEDC, PGAS, RAJA, ...): Yahoo gives
price, market cap, EPS and P/E in IDR but book value per share, free cash flow, EBITDA, cash, debt and the fiscal
years in USD, and its own P/B and EV / EBITDA mix the two (INDY: 2,610 IDR / 0.231 USD = P/B 11,298.7). The
screening stores the currency of the fundamentals (`fundamental_snapshot.financial_currency`, `V1.0.20`) and
converts with the stored USD/IDR rate (`fx_rate_daily`): P/B, FCF yield, EV / EBITDA and Altman Z compare like with
like (INDY P/B 0.63), and the agents get the fiscal years in IDR billions. Without a stored rate those four metrics
are unknown rather than wrong. The same metrics feed Screening Stocks, Selected Stocks and the Analysis scorecards.

## AI analysis of one stock

**Screening > Analysis** in the sidebar (the Screening entry is a dropdown with Screening Stocks, Selected
Stocks and Analysis; permission `SCREENING`): choose a stock of the companies table and the investor agents.
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
an agent answer without strengths and concerns is asked again. The fact sheet of a company that reports in USD
(INDY, SMDR, RAJA, PTSN) is translated into IDR at one rate - the rate its latest stored price was converted with,
so the price is the IDX quote again (INDY: Rp 2,790) - and the report and PDF name the rate; ratios, margins and
growth rates stay those of the USD statements. Details: [`docs/v1_docs/ANALYSIS_DOCS.md`](docs/v1_docs/ANALYSIS_DOCS.md).

## Technical analysis

**Companies › Technicals** charts every stored listing, the IHSG index and the **Forex pairs and commodities**
(dropdown IDX / Forex / Commodities): candles with moving averages, Bollinger Bands, channels, ATR
bands, support / resistance zones, retracements, 52-week and all-time levels, a base range, corporate events and ARA / ARB
days, lower panes (volume, RSI, MACD, Stochastic, OBV, ADX, ATR %, relative strength vs IHSG, 12-1 month momentum), key
levels, volume by price and IHSG regime panels - and the **IHSG index** itself (search IHSG). Below the chart, a
**summary for each preset** (Murphy, Keith Gill, Paul Tudor Jones, Druckenmiller): the stance, the price to buy at,
the price to sell at, the stop and the thesis from the indicators and price patterns, by fixed rules (no AI model; the
same data always gives the same summary; "For information only. Not investment advice."). The backend computes every
indicator deterministically (ta4j 0.22.6 and custom code), rounds every level to a valid IDX price (tick sizes, lot 100;
the index to 0.01) and detects auto-rejection days with the limits in force on each day. The latest
**IndicatorSnapshot** of every listing (versioned JSON, [`docs/indicator-snapshot.md`](docs/indicator-snapshot.md)) is
the input of the summaries and of the technical agents planned next.

The page and its APIs read only the database: daily prices from `price_daily` (filled by the price ingestion), IHSG from
`index_daily` (filled by the IHSG ingestion on Ingestion › Price Ingestion and by the technical refresh) and dividends /
splits from `corporate_event` (filled by the technical refresh). Both jobs call the Yahoo
Finance chart API directly from Java (no `yfinance` package, no API key). Currencies and commodities come from
`instrument_daily` (Price Ingestion with the market Forex / Commodities). The presets are technical only: no macro
data (that is the Macro page); a currency or commodity is read through its own trend. TradingView only supplies the open-source chart library (Lightweight Charts™) that draws in the
browser - no data comes from TradingView.

| Endpoint (permission COMPANIES) | Content |
|---|---|
| `GET /api/v1/technical/markets` | the dropdown: IDX, Forex, Commodities |
| `GET /api/v1/technical/tickers?exchange=IDX&query=` | ticker search (IHSG listed as `type` INDEX; `exchange=FOREX` / `COMMODITY`: the currencies / commodities) |
| `GET /api/v1/technical/{exchange}/{ticker}/ohlcv?interval=D\|W\|M&range=1Y&adjustment=split\|total` | bars and ARA / ARB days |
| `GET /api/v1/technical/{exchange}/{ticker}/indicators?set=sma50,sma200,rsi14,...` | indicator series (`[time, value]`) |
| `GET /api/v1/technical/{exchange}/{ticker}/levels` | zones, retracements, swings, volume profile, 52-week / all-time levels, key levels table |
| `GET /api/v1/technical/{exchange}/{ticker}/snapshot` | the agent-ready snapshot |
| `GET /api/v1/technical/{exchange}/{ticker}/events` | dividends and splits for chart markers |
| `GET /api/v1/technical/{exchange}/{ticker}/summary` | the preset summaries: stance, buy / sell / stop prices, reward to risk, thesis, evidence (`{ticker}` = IHSG for the index) |
| `GET /api/v1/technical/market?exchange=IDX` | IHSG regime and breadth (technical only: no macro data) |
| `POST /api/v1/technical/refresh` (INGESTION) | load IHSG and corporate events from Yahoo Finance, recompute every snapshot (also nightly, 19:00 WIB) |

```bash
curl -X POST -H "$AUTH" "http://localhost:8080/api/v1/technical/refresh?exchange=IDX"   # first run: IHSG since 1990, events of every listing
curl -H "$AUTH" "http://localhost:8080/api/v1/technical/IDX/HRTA/snapshot"
curl -H "$AUTH" "http://localhost:8080/api/v1/technical/IDX/ANTM/summary"   # Murphy, Gill, PTJ, Druckenmiller: buy / sell / stop, thesis
curl -H "$AUTH" "http://localhost:8080/api/v1/technical/IDX/IHSG/ohlcv?interval=D&range=1Y"   # the IHSG index
```

Details: [`docs/v1_docs/TECHNICAL_DOCS.md`](docs/v1_docs/TECHNICAL_DOCS.md).

## Macro data

**Ingestion › Macro** collects the macro data of the Druckenmiller and Paul Tudor Jones views (requirements:
[`macro.md`](macro.md)). There are 25 series:
- **Indonesia:** BI rate, CPI, SBN 10Y, reserves, M2 (Bank Indonesia pages and SEKI tables, CNBC).
- **United States:** Fed funds, 10Y / 2Y, CPI and core CPI, Fed balance sheet, TGA, reverse repo, high-yield spread,
  breakeven, M2, VIX (FRED), the dollar index (Yahoo Finance).
- **Positioning:** gold, crude and the dollar (CFTC).
- **Four series without a source a program may read** (IDX foreign flows, SBN foreign ownership, Indonesia CDS, China
  PMI) take a CSV upload.

How the data is handled:
- Every value is checked before it is stored (range, dates); large moves are listed as warnings.
- Each observation keeps the day it became public, so a snapshot never uses a figure published after its date.
- The jobs run in their own queue (type `MACRO`) and are listed with the others.

| Endpoint (permission INGESTION) | Does |
|---|---|
| `GET /api/v1/macro/series` | every series with its latest value, release date and state (up to date, behind, not fetched, failing, CSV upload) |
| `GET /api/v1/macro/series/{code}` | one series with its observations `[date, value, released]` |
| `POST /api/v1/macro/ingestions?code=US_10Y[&full=true]` | one series: **202** / **200** |
| `POST /api/v1/macro/updates` | "Update all macro data": every series whose next release is due |
| `POST /api/v1/macro/series/{code}/upload` | a CSV of `obs_date,value` (422 with the reason when rejected) |

The signals and regimes are described in [`docs/v1_docs/MACRO_DOCS.md`](docs/v1_docs/MACRO_DOCS.md), section 5.

**The Macro page** (sidebar › Macro, between Companies and Screening; permission COMPANIES) reads a country's index
through this data: Indonesia (the United States and China later), the index IHSG or LQ45 as a plain line, the macro
panel (the liquidity and risk regimes and a chart per series, each with a summary of what it says for Indonesian
equities), the Paul Tudor Jones, Druckenmiller, George Soros and Ray Dalio summaries (macro side: stance, thesis, buy
when / sell when) and the
conclusion: an investability score from 0 to 100 (six weighted parts), the verdict (investable / selective / not
investable now) and the action (buy, accumulate, wait, sell / reduce) with the buy and sell conditions met or not.
Fixed rules in Java, no AI model (`MacroOutlook`). LQ45 (`^JKLQ45`) is stored with IHSG by the same index job.
The page reads as of today (Jakarta): USD/IDR is the latest close of the Forex price ingestion (Yahoo Finance `IDR=X`)
as soon as it is stored, and every chart names its source. Companies › Technicals reads no macro data: its presets and
panels are technical only.

```bash
curl -H "$AUTH" "http://localhost:8080/api/v1/macro/outlook?country=ID&index=LQ45&range=1Y"
```

Details: [`docs/v1_docs/MACRO_DOCS.md`](docs/v1_docs/MACRO_DOCS.md), section 6a. The page also shows the commodities
and currency pairs with what each means for Indonesia (exports, imports, the rupiah; the terms of trade) and, in its
right column, the latest AI macro analysis and the country's indexes.

**AI macro analysis** (sidebar › Macro › AI Analysis; permission COMPANIES): a background job like the AI stock
screening. A research agent (DeepSeek, ReAct with tool calling) reads the stored history of the macro series,
commodities, currency pairs and IHSG / LQ45; four macro agents - Paul Tudor Jones, Druckenmiller, George Soros and Ray
Dalio - each score how investable the country is (0 - 100), checked by a validator and a critic (Reflection) and
taught by earlier runs (Reflexion); Claude Opus 5.5 writes the synthesis (summary, bull / bear case, risks, what to
monitor, sector tilts, at most +-5 points). Investability = 80 % the agents' mean (each 60 % its rules preset, 40 % the
AI) + 20 % the Macro page's rules score, with the verdict and the action of the rules. About $0.05 and 30 - 50 s per
analysis (budget $0.20). Indonesia now; the United States and China later.

```bash
curl -H "$AUTH" -H "Content-Type: application/json" -d '{"country":"ID","index":"IHSG"}' \
     http://localhost:8080/api/v1/macro/analyses
```

Details: [`docs/v1_docs/MACRO_ANALYSIS_DOCS.md`](docs/v1_docs/MACRO_ANALYSIS_DOCS.md).

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
| `/companies?exchange=IDX`        | Companies dropdown (Fundamentals, Technicals); Fundamentals: companies of an exchange: exchange filter, search, sector filter, sortable table, summary tiles        |
| `/companies/technicals`          | Technicals: candlestick chart (TradingView Lightweight Charts; palm oil and the monthly series as a plain line of the close) with overlays, levels, events, ARA / ARB days and lower panes; exchange or market (IDX, Forex, Commodities), ticker / currency / commodity search, interval, range, price basis, indicator presets (Murphy, Keith Gill, PTJ, Druckenmiller) with a summary of each below the chart (stance, buy / sell / stop, thesis); the IHSG and LQ45 indexes (search IHSG or LQ45; LQ45 is compared with IHSG); key levels, snapshot, volume profile and IHSG regime panels (technical only, no macro); PNG / CSV export. Settings saved per user and in the URL |
| `/macro?country=ID&index=IHSG`   | Macro: country (Indonesia; US and China later) and index (IHSG, LQ45) dropdowns, range; the index as a plain line; the macro panel (liquidity and risk regimes, a chart per series with a summary); Paul Tudor Jones, Druckenmiller, George Soros and Ray Dalio summaries (macro side: stance, buy when / sell when); the conclusion (investability score 0 - 100, verdict, action, the buy / accumulate / sell conditions met or not, what would change the call) |
| `/credits`                       | third-party notices (TradingView Lightweight Charts™)                                                                                                             |
| `/companies/{exchange}/{ticker}` | company detail in tabs: overview (KPIs, charts, data coverage), income statement, balance sheet, cash flow, segments, metrics, valuation, market & shares, filings |
| `/screening`                     | Screening dropdown (like Ingestion), Screening Stocks: exchange, market cap, top N, investor agents (multi-select); saved screenings |
| `/screening/selected`            | Selected Stocks: stocks chosen from the companies table (search, multi-select), top N, investor agents; saved screenings of selected stocks |
| `/screening/selected/{id}`       | report of a screening of selected stocks (same content; the selected stocks and their flags shown) |
| `/screening/{id}`                | screening report: progress, executive summary, ranking with per-agent scores, stock details (news, reasoning, reflection), funnel, token usage, PDF download |
| `/screening/analysis`            | Analysis: one stock of the companies table (with its stored data) and the investor agents; saved analyses (10 per page: 5 / 10 / 20 / 50) |
| `/screening/analysis/{id}`       | analysis report: progress, overall score and cost, executive summary (bull / bear case, risks), every agent's view, research brief with its excerpts and ReAct steps, key figures, data used, notes, token usage, PDF download |
| `/ingestion/xbrl`                | Ingestion dropdown (like the Admin Center), IDX XBRL: upload an `.xlsx` financial statement (`/ingestion` opens this page) |
| `/ingestion/prices`              | Price Ingestion: fetch daily prices of one company (exchange / ticker dropdowns), or of one currency / commodity (market Forex / Commodities); on the right, update every company of the exchange (or every currency / commodity) to today and ingest the IHSG index |
| `/ingestion/macro`               | Macro: "Update all macro data to today", the series per region with their source, latest value, state and actions (fetch, full history, CSV upload, history chart) |
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
(cd backend && ./mvnw test)                      # 701 tests
(cd frontend && npm run lint && npm run build)   # type check, lint, production build
```

`FilingMapperHrtaTest` maps the six HRTA filings and compares every field with the validated seed
data; `FilingMapperLegacyTemplateTest` maps the pre-2023 INDF FY2022 filing and compares it with the
FY2022 comparatives of the FY2023 filing; `IngestionRepositorySegmentsTest` checks that a period's
own filing replaces its whole revenue breakdown (rolled back); `IngestionVerifierOverdraftTest` checks that
GGRM's cash net of bank overdrafts (FY2022, FY2024, FY2025) is accepted as filed, `IngestionVerifierDepositsTest`
that PWON's cash including deposits (FY2022 .. 2026-II) is;
`IngestionAgentRetryTest` that a model call is retried after a read timeout but not after a
permanent error; `IngestionAgentModelTest` that every ingestion request names the configured model
(Spring AI's own default would be `gpt-5-mini`). Both take the model from the configuration, not from
Java code: `ConfiguredChatModel` resolves `spring.ai.openai.chat.model` of `application.yaml` with
`OPENAI_MODEL` from the environment, else `backend/.env`, else the `application.yaml` default; `PriceIngestionServiceTest` also converts a listing quoted in another currency and
`EcbFxRateProviderTest` parses a real ECB (Frankfurter) response; `FilingMapperIndySharesTest`
derives INDY's share count from the FY2023 EPS and no count from the other INDY filings;
`FilingMapperInfrastructureTest` maps the five SMDR filings (Infrastructure Industry taxonomy);
`FilingMapperPropertyTest` the three CBDK filings (Property and Real Estate Industry taxonomy: real estate assets as
inventory, investment properties as capex, a restated FY2024);
`FilingMapperFinancialTest` maps the five BNGA, five BMRI, four BTPN and five BRIS filings (Financial and Sharia Industry
taxonomy, banks; BMRI's insurance claims shown for information; BRIS's syirkah share filed negative is a cost); `ReclassificationTest` that the agent can revise its own
classification while the column still fails;
`FilingMapperWifiTpiaTest` the WIFI cash flows (a financing total that leaves out the dividends, amounts entered
twice, a 3 rupiah slip) and TPIA's self-contradicting comparatives; `FilingMapperRiseSmbrTest` an FX effect the filed
cash roll-forward leaves out (RISE) and SMBR's comparative that counts investing flows twice; `IngestionVerifierSegmentsTest` a refused comparative
breakdown of a period already stored as a note; `FilingMapperAsgrTest` the ASGR and SIMP filings (full amounts under an "In Million" label, an amount reported twice,
EPS filed in millions, revenue not tagged); `StatementGapFillTest` that comparatives only fill empty fields; `ShareSplitTest` the split adjustment in both upload orders (EPS, balance sheet shares, share snapshots); `FilingMapperBbriTest` BRI's repeated comparative lines; `FilingMapperTinsTest` the TINS filings (residual lines outside the filing's totals, a workbook without current-year figures, a loss per share without its sign); `FullTextExtractorTest`, `IngestionServiceFallbackTest` and `FullTextExtractionDatabaseTest` the full-text extraction (row references only, checks and corrections, when it takes over, stored with the standard steps, its own model, a row of a notes sheet leaves the field empty, no gross profit for a profit or loss by nature - NSSS, profit from operations asked for again when left empty - MSIN) and the error log (with the failed model calls); `IngestionUsageRecorderTest` and `IngestionJobCostTest` the model cost of an upload (every call recorded with its stage, model, tokens and reported cost; a failed attempt and its retry; the sums per job and per stage and model); `FilingMapperWebSharesTest` also a published share count checked against the EPS of a profit or loss by nature; `FilingMapperMikaTest` a segment line the breakdown's own total leaves out (MIKA); `FilingMapperAuditedTickersTest` the MAPA, ULTJ and BBCA findings (split on two bases, treasury stock, lines filed as 0); `JobWorkerPoolTest`, `JobWorkersConfigurationTest`, `FinancialStatementQueueWorkersTest` and `CompanyWriteLockTest` the ingestion workers (five jobs at the same time, `neracalab.jobs.workers`, filings of one company together with their write steps one at a time); `FilingMapperCekaTest` the five CEKA filings; `FilingMapperEpsTest` the EPS checks and the MYOR, NCKL, PTSN filings;
`PresetSummariesTest` the preset summaries (valid IDX prices, stop < buy < sell, the trend rules, determinism; a currency pair read through its own trend; no macro data); `IndexIngestionServiceTest`, `PriceUpdateServiceTest` and `PriceUpdateControllerTest` the IHSG ingestion and the update of every company to today;
`InstrumentIngestionServiceTest`, `BarCleanerTest`, `WorldBankPinkSheetClientTest`, `ImfPcpsClientTest` and `MarketControllerTest` the Forex and commodity prices (the last completed day by the market's zone, the bad prints of the real USD/IDR and USD/JPY histories, coal monthly from the World Bank workbook - downloaded once for every monthly commodity of an update, palm oil in ringgit as CPO=F x MYR=X, the markets API);
`MacroSourceParsersTest`, `MacroSeriesTest`, `MacroIngestionServiceTest`, `MacroSignalsTest` and `MacroControllerTest` the macro data (FRED, CNBC, CFTC, the Bank Indonesia form and export, a SEKI table; release dates; the checks before storing; revisions; point in time; the regimes; the CSV upload);
`MacroOutlookTest` and `MacroOutlookControllerTest` the Macro page (the rules: not investable / investable / accumulate below the 200-day average, the panels' tones, derived series, point in time, missing data; the API and its refusals); `IndexIngestionServiceTest` also LQ45 with IHSG;
`MacroAnalysisControllerTest`, `MacroAnalysisRulesTest` and `MarketBoardTest` the AI macro analysis (end to end with a scripted model: research with tools, four agents, synthesis, scores, PDF; an active analysis not queued twice; point in time, flows summed per month, stored highs and lows, the validator's rules, the blend and the action; the commodities board for Indonesia);
`IdxRulesTest`, `IndicatorEngineTest`, `StructureTest`, `SnapshotCalculatorTest` and `TechnicalControllerTest` the technical analysis (IDX tick sizes and auto rejection, indicators against reference formulas, pivots / zones / retracements / volume profile / base, the snapshot schema on 300 real days of HRTA, SMSM and MYOR, every API);
`JobDeadlineTest` and `UploadJobTimeoutTest` the 5-minute job limit; `ModelSpeedSettingsTest` reasoning off on the
wire and the per-call timeout; `DeterministicFinisherTest` a run finished without the model;
`FilingMapperWebSharesTest` and `WebShareCountsTest` the Yahoo Finance share counts (only counts that fit the filing's EPS; a count carried to a date the website does not publish when share capital is unchanged, there is no treasury stock and the EPS confirms it - BBCA FY2021); `FilingMapperAdroTest`, `IngestionVerifierRoundingTest` and `FilingMapperAdmrArtoBrmsTest` the filings behind the error log of 2026-10-18 (ADRO: revenue from the breakdown when only gross profit is tagged; UNTR: ending cash with decimals within the filing's unit; ADMR: lines with decimals outside a rounded total; ARTO: syirkah funds from their lines; BRMS: EPS filed per 1,000 shares); `FilingMapperCoinDewaEnrgHealTest`, `FullTextExtractorTest` and `IngestionVerifierRoundingTest` the filings behind the error log of 2026-10-19 (COIN: a final tax deducted twice; DEWA: 'Other income' inside 'Other gains (losses)'; ENRG: one amount on two lines a unit apart; HEAL: a tax expense filed as a benefit; ESSA: a cash roll-forward that contradicts itself, still refused; BUVA: share counts from the par value of a filing by nature when no published count fits; GMFI, KRAS: no rupiah par value for a share capital in dollars; EXCL, BUVA: revenue is the 'Sales and revenue' row, from the breakdown where the row is empty; EXCL FY2022: a breakdown with decimals within the filing's unit); `FilingMapperItmgMdkaMedcHrumTest`, `FullTextExtractorTest` and `IngestionVerifierRoundingTest` the filings behind the error log of 2026-10-21 (ITMG FY2023: full amounts the EPS does not tell apart; ITMG, MDKA H1 2025: 'Other gains (losses)' inside a residual line of its direction; ITMG H1 2025, MDKA H1 2026: one column's EPS a power of ten off, told by the published counts; MEDC, HRUM: a discontinued EPS row that is no such EPS, while UNVR, EMTK, MPMX and KRAS keep theirs; MEDC FY2022: a balance sheet 0.30 USD apart; ISAT, PGEO: operating income and expenses by nature); `FilingMapperKetrLppfTest`, `FullTextExtractorTest` and `IngestionVerifierOverdraftTest` the filings behind the error log of 2026-10-22 (KETR FY2024: 'Finance income' filed again within 'Other income'; LPPF FY2023: rows left off the face of the balance sheet are not stored as 0, and overdrafts within the stored short-term debt; MNCN H1 2026: an expense by nature named as cost of revenue is an operating expense); `FilingMapperWebSplitsTest` the filings behind the notes of 2026-10-16 (BKSL: a par value only one column's EPS fits; CUAN, DSSA: published counts on the count of before a later split; BUKA, DSSA: a period-end count after a buyback); `IngestionServiceCurrencyTest` that a filing in another currency than the company's stored data is refused (CUAN's IDR FY2023); `CompanyControllerTest` and `TechnicalControllerTest` also the IDX quote of a USD reporter (Rp 540.00, not 539.9999); `PricesAfterUploadTest` and `FinancialStatementQueueWorkersTest` the prices queued for the company of a stored filing; `FilingMapperTuguMoliCnmaTest` the filings behind the error rows of 2026-10-14 (TUGU, an insurer: Insurance Industry taxonomy, share counts, EPS unit; MOLI: EPS in other units per filing, no false split; CNMA: depreciation by order of liquidity); `FilingMapperTotlMidiTest` the filings behind the error rows of 2026-10-13 (TOTL: one segment filed on several lines of one kind; MIDI: a deconsolidation line the filed ending cash excludes); `FilingMapperErrorLogTest` the filings behind the error rows of 2026-10-11 (proforma equity, an amount on two lines, the EPS unit of a filing by nature, every filing of eight new companies without a failed check) and `DerivedDataRefreshTest` the refresh of the derived data (seconds, the same rows, requests that waited are served by one run);
`SmdrShareSeedTest` and `BngaShareSeedTest` check the SMDR and BNGA share-count scripts (fill once, never replace a stored count); `ShareSplitTest` the share splits (also that a split-adjusted USD EPS keeps its decimals: RAJA 0.0054 / 5 = 0.00108); `SameAsStoredTest` that filing values are compared at the stored column scale
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
(saved Yahoo responses, the quote batch of the fallback universe, the currency of the fundamentals), `QuantScreeningTest` (metrics, scorecards, funnel, shortlist; selected stocks:
flagged and excluded stocks; a company reporting in USD: converted metrics, fiscal years in IDR, unknown without a rate),
`ScreeningAgentsTest` (scripted model: options, cost metering and budget, ReAct with a tool call,
reflection critic, synthesis rules), `FundamentalRepositoryTest` and `ScreeningControllerTest`
(pipeline mocked: validation, job, report, PDF; selectable companies, selection validation, runs by scope); see `docs/v1_docs/SCREENING_DOCS.md`, section 9.
RAG: `TextChunkerTest`, `EmbeddingClientTest`, `PdfTextTest` (the HRTA FY2025 PDF), `NewsCollectorTest`
(date range and paging on saved pages, only the news sites read), `RagNewsIngestionTest`, `NewsRetryTest` (passing failures of the news sites retried), `RagRepositoryTest` (pgvector store and cosine search, rolled back)
and `RagControllerTest` (a PDF through the worker into the store with stub embeddings, validation,
permission); `IngestionJobTypeConstraintTest` that every script re-creating the job-type check lists every job type; see `docs/v1_docs/RAG_DOCS.md`, section 7.
Analysis: `AnalysisAgentsTest` (scripted model: research only with documents, ReAct with a vector-store
search, invented refs dropped, tool limits, synthesis bounds and fallback, fact sheet, also translated into IDR for a USD reporter) and
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
| `V1.0.17__schema_screening_selection.sql` | screening of selected stocks: `screening_run.tickers`, `market_cap_tier` NULL for such runs |
| `V1.0.18__schema_ingestion_error.sql` | `ingestion_error`: every upload that was refused, taken over by the full-text extraction, INCOMPLETE or FAILED; `fixed_by_claude_code` |
| `V1.0.19__schema_ingestion_cost.sql`  | `llm_usage.job_id`: every model call of a financial statement upload with its model, tokens and cost (per job on the IDX XBRL Ingestion page) |
| `V1.0.20__schema_screening_currency.sql` | `fundamental_snapshot.financial_currency`: the currency Yahoo reports a listing's fundamentals in (USD for INDY, ADRO, ...); the screening metrics convert with `fx_rate_daily` |
| `V1.0.23__schema_technical.sql`       | technical analysis: `index_daily`, `corporate_event`, `corporate_event_fetch`, `macro_series`, `indicator_snapshot`, `chart_setting` (new tables only) |
| `V1.0.25__schema_markets_macro.sql`   | Forex / commodity prices (`market_instrument`, `instrument_daily`), the macro catalog (`macro_series_def`), release dates and revisions of `macro_series`; job type `MACRO` |
| `V1.0.4__data_HRTA_financials.sql`    | HRTA statements Q1 2024 .. H1 2026 from the six IDX filings in `data/IDX_XBRL/HRTA` |
| `V1.0.5__data_HRTA_market.sql`        | HRTA share counts and daily prices 2024-01-02 .. 2026-09-30                |
| `V1.0.12__data_SMDR_shares.sql`       | SMDR share counts (16,375,600,000 split-adjusted, from 2020-12-31) and its 2023 1:5 stock split |
| `V1.0.13__data_BNGA_shares.sql`       | BNGA audited year-end share counts 2021 .. 2025 (note 33 of the annual reports; two share classes and treasury shares) |
| `V1.0.21__data_RAJA_shares.sql`       | RAJA share counts (21,135,412,500 split-adjusted, from 2021-12-31), its 1:5 stock split of 2026-07-16 and the EPS of the periods before it (/ 5) |
| `V1.0.22__data_TUGU_shares.sql`       | TUGU's 1:2 stock split of 2023-05-24 (par Rp 100 -> Rp 50), which no filing restates: counts 3,555,575,600 split-adjusted from 2021-12-31, the per-share figures before it (/ 2) |
| `V1.0.24__data_BACH_shares.sql`       | BACH's IPO of 2026-07-08 (615,000,000 new shares at Rp 442), after its only filing: 4,084,430,000 shares from the listing (the filing gives 3,469,430,000 before it) |
| `V1.0.6__data_metrics_valuation.sql`  | derived for all companies: market snapshots, valuation snapshots, metrics  |
| `V1.0.26__data_markets_macro.sql`     | the catalogs: 28 instruments (USD/IDR, USD/JPY and 26 commodities) and 25 macro series with their sources |

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
| `index_daily`, `corporate_event`, `corporate_event_fetch`, `macro_series` | technical analysis inputs: IHSG daily bars, dividends / splits (Yahoo), macro observations (with release dates) |
| `market_instrument`, `instrument_daily` | Forex pairs and commodities and their bars (daily; the World Bank, IMF and FRED series monthly) |
| `macro_series_def`    | the macro series: source, unit, frequency, publication lag, sanity range     |
| `indicator_snapshot`, `chart_setting` | the latest technical snapshot per company (versioned JSON); a user's last chart settings |
| `news_article`, `news_article_ticker`, `news_source_fetch`, `news_brief` | news cache of the screening research agent |
| `screening_run`, `screening_candidate`, `screening_agent_score` | screening reports: parameters, shortlist, scores and reasoning |
| `analysis_run`, `analysis_agent_score` | analysis reports of one stock: what the agents saw, research brief, synthesis, scores and reasoning |
| `screening_lesson`, `llm_usage` | Reflexion memory (screening and analysis); tokens and cost of every model call (screening, analysis and financial statement upload) |

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

At runtime: upload the filing (`POST /api/v1/financial-statements/upload`). When it is stored, the
prices of its company are queued by themselves (the whole history for a new company, else the missing
days), and the price job ends with the company's market caps and valuations; to request them by hand:
`POST /api/v1/prices/ingestions?exchange=IDX&ticker=<TICKER>`. Revenue segments are stored only
when the filing fills the breakdown sheets 1617000 / 1618000 (INDF, for example, leaves them blank).
As seed data that exists on every start:

1. Put the filing in `data/IDX_XBRL/<TICKER>/xlsx/` (and prices in `data/IDX_XBRL/<TICKER>/price/`).
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
