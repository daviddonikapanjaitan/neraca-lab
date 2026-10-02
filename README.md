# Neraca Lab

Application for analyzing financial statements using LLM.

- Backend using: Spring Boot 4 (Java 21) + PostgreSQL 17 + Redis 7 + Spring AI 2.0 (OpenRouter)
- Frontend using: Next.js

Assistant developer: Claude Code (Opus 5.5 LLM Model)

## Project structure

```text
neraca_lab/
├── backend/                     Spring Boot application
│   ├── Dockerfile
│   ├── docker-compose.yaml      postgres + redis + backend
│   ├── .env.example             template for backend/.env (AI key; .env is git-ignored)
│   └── src/main/
│       ├── java/.../company/    company list / detail APIs, exchange and ticker codes
│       ├── java/.../ingestion/  AI upload endpoint: xlsx reader, mapper, agent, tools, repository
│       └── resources/
│           ├── application.yaml
│           └── db/              SQL scripts (no Flyway)
│               ├── V1.0.1__schema.sql
│               ├── V1.0.2__schema_market_valuation.sql
│               ├── V1.0.3__views.sql
│               ├── V1.0.4__data_HRTA_financials.sql
│               ├── V1.0.5__data_HRTA_market.sql
│               └── V1.0.6__data_metrics_valuation.sql
├── data/<TICKER>/               source data per company
│   ├── xlsx/                    IDX XBRL financial statements (FinancialStatement-<period>-<TICKER>.xlsx)
│   ├── pdf/                     the same filings as PDF
│   └── price/                   daily prices (<TICKER>.JK_daily_yahoo.csv)
└── docs/v1_docs/                DB_SCHEMA_DOCS.md (tables / views), AI_INGESTION_DOCS.md (upload + agent),
                                 COMPANY_API_DOCS.md (company list / detail APIs)
```

## Running the backend

Requires Docker.

```bash
cd backend
docker compose up -d --build     # start postgres, redis and backend
docker compose logs -f backend   # follow logs
docker compose down              # stop (add -v to wipe the database volume)
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
`docker compose up -d postgres redis`; `application.yaml` defaults to `localhost`.

## Uploading financial statements (AI ingestion)

```bash
curl -F "file=@data/HRTA/xlsx/FinancialStatement-2026-II-HRTA.xlsx" \
     http://localhost:8080/api/v1/financial-statements/upload
```

`POST /api/v1/financial-statements/upload` takes an IDX XBRL workbook (`.xlsx` from idx.co.id).
Java parses and validates it (Apache POI, accounting identity checks); a Spring AI agent with tool
calling then stores the company, periods, statements, revenue segments and share counts, refreshes
the derived metrics and verifies the result. It uses Plan-and-Execute, a tool calling loop with
ReAct, sequential / parallel / conditional tool calling and a reflection review. Amounts never pass
through the model, and the final status comes from a database read-back. The response contains the
plan, every tool call and the verification. Details: [`docs/v1_docs/AI_INGESTION_DOCS.md`](docs/v1_docs/AI_INGESTION_DOCS.md).

| HTTP | Meaning                                                                      |
|------|------------------------------------------------------------------------------|
| 200  | `COMPLETED`: everything stored and verified by a database read-back          |
| 202  | `INCOMPLETE`: something is pending or failed validation (see `verification`) |
| 422  | not an IDX XBRL `.xlsx` workbook, or an unsupported template                 |
| 502  | `FAILED`: the AI provider could not be reached                               |

A company exists once per `(ticker, exchange)`: the upload upserts it on the database constraint
`uq_company_ticker_exchange`, and both codes are stored upper case (checks `ck_company_ticker`,
`ck_company_exchange`), so uploading the same company again, or concurrently, never creates a
second row.

## Company APIs

```bash
curl "http://localhost:8080/api/v1/companies?exchange=IDX"   # companies of an exchange
curl  http://localhost:8080/api/v1/companies/IDX/HRTA        # everything stored for one company
```

| Endpoint                                     | Returns                                                                                                                                                                                                                                                  |
|----------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `GET /api/v1/companies?exchange=IDX`         | companies of the exchange, ordered by ticker: master data plus number of periods, first / latest period and latest price date. `exchange` defaults to `IDX`                                                                                              |
| `GET /api/v1/companies/{exchange}/{ticker}`  | `company`, `coverage` (row counts and date ranges per table), `periods` (most recent first, each with income statement, balance sheet, cash flow, segment figures and fundamental metrics), `segments`, `shareSnapshots`, `latestPrice`, `latestMarketSnapshot`, `valuations`, `corporateActions` |

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

## Tests

```bash
cd backend
./mvnw test        # needs the Docker Postgres (docker compose up -d postgres redis)
```

`FilingMapperHrtaTest` maps the six HRTA filings and compares every field with the validated seed
data; `BackendApplicationTests` starts the application context. `CompanyControllerTest` calls both
company APIs against the HRTA seed data, `CompanyUniquenessTest` checks that a second, lower-case,
padded or exchange-less company row is rejected and that the ingestion upsert keeps one row, and
`CompanyCodesTest` covers ticker / exchange normalisation. Uploading all six HRTA filings
through the endpoint into an empty database reproduces the seed data exactly (see
`docs/v1_docs/AI_INGESTION_DOCS.md`, section 5).

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

1. Put the filing in `data/<TICKER>/xlsx/` (and prices in `data/<TICKER>/price/`).
2. Create the data script(s), e.g. `V1.0.7__data_<TICKER>_financials.sql`, following the
   upsert pattern of `V1.0.4__data_HRTA_financials.sql` / `V1.0.5__data_HRTA_market.sql`.
3. Add them to `spring.sql.init.data-locations` in `application.yaml` **before**
   `V1.0.6__data_metrics_valuation.sql`, which derives snapshots and metrics from all loaded
   data, and restart the backend.

### Running the scripts manually

Without starting the backend, the scripts can be applied directly to the Docker database:

```bash
cd backend/src/main/resources/db
cat V1.0.1__schema.sql V1.0.2__schema_market_valuation.sql V1.0.3__views.sql \
    V1.0.4__data_HRTA_financials.sql V1.0.5__data_HRTA_market.sql V1.0.6__data_metrics_valuation.sql \
  | docker exec -i neracalab-postgres psql -U <user> -d neracalab -v ON_ERROR_STOP=1
```
