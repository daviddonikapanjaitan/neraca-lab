# Neraca Lab - AI Financial Statement Ingestion (v1)

Upload an IDX XBRL financial statement workbook (`FinancialStatement-<period>-<TICKER>.xlsx`, as
downloaded from idx.co.id) and an AI agent built with **Spring AI 2.0** stores it in the database
(see [DB_SCHEMA_DOCS.md](DB_SCHEMA_DOCS.md)) using tool calling.

## 1. Endpoint

Needs the `INGESTION` permission. Every API needs a login: `AUTH="Authorization: Bearer <token>"` from `POST /api/v1/auth/login`
([AUTH_DOCS.md](AUTH_DOCS.md), section 3).

```http
POST /api/v1/financial-statements/upload
Content-Type: multipart/form-data
file=<FinancialStatement-2026-II-HRTA.xlsx>
```

```bash
curl -H "$AUTH" -F "file=@data/HRTA/xlsx/FinancialStatement-2026-II-HRTA.xlsx" \
     http://localhost:8080/api/v1/financial-statements/upload
```

The upload is **asynchronous**: the request checks the workbook, stores it once per SHA-256
checksum in `ingestion_file` (a known file is reused) and answers at once with a job; the agent runs
in a background thread. Details of storage, job statuses and the job API:
[INGESTION_JOBS_DOCS.md](INGESTION_JOBS_DOCS.md).

| HTTP | Meaning                                                                              |
|------|--------------------------------------------------------------------------------------|
| 202  | job queued (`Location: /api/v1/ingestions/{id}`)                                     |
| 200  | the same file is already queued / being stored: that job                             |
| 422  | not an .xlsx / not an IDX XBRL workbook / unsupported template (ProblemDetail body)  |
| 413  | file larger than 20 MB                                                               |

Follow the job with `GET /api/v1/ingestions/{id}`. Agent outcome -> job status: `COMPLETED` ->
`SUCCEEDED` (everything stored and verified by a database read-back), `INCOMPLETE` -> `INCOMPLETE`
(something is pending or failed validation, see `verification`), `FAILED` -> `FAILED` (the AI
provider could not be reached or the agent crashed, see `error`). A model call that fails
transiently (read timeout, dropped connection, HTTP 408 / 429 / 5xx) is retried before the run is
given up (`model-retries`, below); e.g. an OpenRouter response that stalled past the 180 s read
timeout (`OpenAIInvalidDataException: Error reading response`) used to fail the whole job.

The job `result` (in `GET /api/v1/ingestions/{id}`) is the full audit trail of the run:

| Field                              | Content                                                                   |
|------------------------------------|---------------------------------------------------------------------------|
| `filing`, `company`                | what the workbook is, which company it belongs to                         |
| `plan`, `planFromModel`            | the plan made by the planner (Plan-and-Execute)                           |
| `steps`                            | every model turn: its `thought` (ReAct), requested and offered tools      |
| `toolCalls`                        | every tool execution: arguments, result, error, duration, `parallelGroup` |
| `rounds`                           | per execution round: verification and the reviewer's reflection           |
| `savedStatements`, `savedSegments` | rows written per column (`INSERTED`, `UPDATED`, `KEPT_EXISTING`)          |
| `verification`                     | final deterministic database read-back                                    |
| `metrics`                          | duration, model calls, tool calls, tool errors, parallel tool groups, model retries |

A typical filing takes 1-4 minutes and 10-30 model calls.

## 2. Configuration

`backend/.env` (ignored by git and docker; template: `backend/.env.example`):

```properties
OPENAI_API_KEY=sk-or-v1-...
OPENAI_BASE_URL=https://openrouter.ai/api/v1
OPENAI_MODEL=deepseek/deepseek-v4-flash-0731
```

`application.yaml` imports it (`spring.config.import: optional:file:.env[.properties]`) and points
the Spring AI OpenAI client at OpenRouter; real environment variables take precedence. Docker
compose reads the same file and passes the values to the backend container at runtime.

| Property                                | Default | Meaning                                         |
|-----------------------------------------|---------|-------------------------------------------------|
| `neracalab.ingestion.max-iterations`    | 30      | model turns per execution round                 |
| `neracalab.ingestion.reflection-rounds` | 2       | extra execution rounds the reviewer may request |
| `neracalab.ingestion.temperature`       | 0.0     | sampling temperature                            |
| `neracalab.ingestion.model-retries`     | 2       | retries of a model call that failed transiently (timeout, network error, HTTP 408 / 429 / 5xx); other errors fail at once |
| `neracalab.ingestion.retry-backoff`     | 5s      | pause before the first retry, doubled for each further one |
| `spring.ai.openai.chat.timeout`         | 180s    | read timeout of one model call                  |

## 3. Design: the model orchestrates, Java owns the numbers

```text
upload ─► IdxWorkbookReader (Apache POI) ─► FilingMapper (deterministic mapping + accounting checks)
                                                   │  all amounts stay server-side in IngestionSession
                                                   ▼
          ┌──────────── IngestionAgent (Spring AI ChatModel, OpenRouter) ────────────┐
          │ 1. PLAN      planner  -> IngestionPlan (structured output)                │
          │ 2. EXECUTE   tool calling loop, ReAct, sequential / parallel / conditional│
          │ 3. REFLECT   reviewer -> Reflection (structured output) + DB read-back    │
          │              not done? feedback -> back to 2 (max reflection-rounds)      │
          └───────────────────────────────────────────────────────────────────────────┘
                                                   │ tools: extract / save / verify
                                                   ▼
                       IngestionRepository (JdbcClient upserts) ─► PostgreSQL (public schema)
```

The model never sees or sends amounts it could alter. Tools take only a column
(`CURRENT_PERIOD`, `PRIOR_PERIOD`, `PRIOR_YEAR_END`), names and categories; the values come from
the parsed workbook kept on the server. Every save re-validates and refuses invalid data, and the
final status is decided by the deterministic database read-back, not by the model.

### Tool calling patterns

| Pattern                  | Where                                                                                                                                                                                                                                  |
|--------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Tool Calling Pattern     | `IngestionTools`: `@Tool` methods (`ToolCallbacks.from(...)`) per upload session                                                                                                                                                       |
| Tool Calling Loop        | `IngestionAgent.execute`: model -> tool calls -> tool results -> model, until a final answer                                                                                                                                           |
| Sequential Tool Calling  | dependencies enforced by tools and prompt: company -> extract -> save -> segments -> refresh -> verify; writes run one at a time                                                                                                       |
| Parallel Tool Calling    | `ToolExecutor`: read-only calls of one turn (e.g. three `extractStatements`) run concurrently on virtual threads (`parallelGroup` in the trace)                                                                                        |
| Conditional Tool Calling | `IngestionAgent.offeredTools`: `registerCompany` only after `findCompany` found nothing, save tools only once the company exists, `classifyIncomeLines` only while unknown income lines exist, `refreshDerivedData` only after a write |
| ReAct                    | executor writes `Thought:` before each action and reads each observation (`steps[].thought`)                                                                                                                                           |
| Plan-and-Execute         | planner returns a structured `IngestionPlan`; the executor receives and follows it                                                                                                                                                     |
| Reflection               | reviewer judges plan, trace and DB verification; issues are fed back as a new round                                                                                                                                                    |

### Tools

| Tool                     | Kind  | Purpose                                                                                    |
|--------------------------|-------|--------------------------------------------------------------------------------------------|
| `getFilingOverview`      | read  | company identity, periods, columns, statements and segments per column, template problems  |
| `findCompany`            | read  | company by the filing's ticker, its existing segments                                      |
| `registerCompany`        | write | new company; the model supplies only the short display name                                |
| `extractStatements`      | read  | income statement, balance sheet, cash flow of a column + validation result                 |
| `classifyIncomeLines`    | write | classifies unknown income-statement lines (re-validated: profit before tax must reconcile) |
| `saveStatements`         | write | period + statements of a column (current period replaces, comparatives fill gaps only)     |
| `extractRevenueSegments` | read  | revenue by type (else by source) of a column                                               |
| `saveRevenueSegments`    | write | segments with English names and types; amounts from the filing; current period replaces the period's whole breakdown, comparatives fill only a period without one |
| `saveShareSnapshots`     | write | share counts at every date in the statements of changes in equity                          |
| `refreshDerivedData`     | write | re-runs `V1.0.6__data_metrics_valuation.sql` (market / valuation snapshots, metrics), then deletes valuation metrics whose value became NULL |
| `verifyStoredData`       | read  | database read-back: pending work and inconsistencies (the final gate of `COMPLETED`)       |

**One company per (ticker, exchange).** The ticker comes from sheet `1000000` "Entity code",
trimmed and upper-cased (`Tickers.normalize`; a code that is not a ticker rejects the upload with
422); the exchange is always `IDX`. `findCompany` looks the company up by that key, and
`registerCompany` writes it with a single
`INSERT ... ON CONFLICT ON CONSTRAINT uq_company_ticker_exchange DO UPDATE ... RETURNING`, so two
uploads of the same company, even concurrent ones, end in one row. The database enforces the
same rule independently: `uq_company_ticker_exchange`, `exchange NOT NULL`, and the upper-case
checks `ck_company_ticker` / `ck_company_exchange`.

## 4. Workbook structure and mapping

An IDX XBRL workbook has one sheet per taxonomy role. Used sheets:

| Sheet                         | Content                               | Use                                                              |
|-------------------------------|---------------------------------------|------------------------------------------------------------------|
| `1000000`                     | general information                   | ticker, names, sector, periods, currency, rounding, audit status |
| `1210000`                     | balance sheet (current / non-current) | `balance_sheet`                                                  |
| `1311000` / `1321000`         | profit or loss by function            | `income_statement`                                               |
| `1510000` / `1520000`         | cash flow, direct / indirect          | `cash_flow_statement`                                            |
| `1410000` (+`PY`)             | changes in equity                     | share capital -> `share_snapshot`                                |
| `1611000` / `1612000` (+`PY`) | PP&E / right-of-use roll-forward      | depreciation                                                     |
| `1617000` / `1618000`         | revenue by type / by source           | `segment`, `segment_financial`                                   |

Statement sheets have a header row of XBRL contexts and one row per line item:
`Indonesian label | value per context | English label`. Lines are matched on the English label.

**Pre-2023 template.** Filings for FY2022 and earlier (e.g. `FinancialStatement-2022-Tahunan-INDF.xlsx`,
`-2022-Tahunan-HRTA.xlsx`) use an older layout, read into the same structure:

| Difference                       | Pre-2023 template                                    | Current template                         |
|----------------------------------|------------------------------------------------------|------------------------------------------|
| statement context header         | period dates, e.g. `31 December 2022`                | `CurrentYearDuration`, `CurrentYearInstant`, ... |
| roll-forward / equity sheet names | `1410000 1 CurrentYear`, `1410000 2 PriorYear` (also 1611000, 1612000) | `1410000`, `1410000PY`                    |
| `xl/styles.xml` compression      | up to ~145:1                                         | ~20:1                                    |

`IdxWorkbookReader` renames the sheets to the current names, and `StatementTable` accepts a date
header (contexts are used by position: current period first, then prior). Apache POI's zip-bomb guard
(default: reject an entry that inflates more than 100:1) is relaxed to 1000:1, with every entry
capped at 100 MB uncompressed (real filings: under 6 MB). The revenue breakdown sheets `1617000` /
`1618000` of this template are one table with the period dates above the value columns
(`slot | name | current | prior | English slot`) and are read for both columns (GGRM, HRTA and INDY
FY2022 fill them; INDF leaves them blank).

| Column           | Income / cash flow context    | Balance sheet context                     |
|------------------|-------------------------------|-------------------------------------------|
| `CURRENT_PERIOD` | CurrentYearDuration           | CurrentYearInstant                        |
| `PRIOR_PERIOD`   | PriorYearDuration (prior YTD) | annual filings only: PriorEndYearInstant  |
| `PRIOR_YEAR_END` | -                             | interim filings only: PriorEndYearInstant |

Rules:

- Amounts are multiplied by the rounding level ("Satuan Penuh" 1, "Ribuan" 1,000, "Jutaan" 1,000,000).
- Quarterly filings are year-to-date: Kuartal I = `Q1`, II = `H1`, III = `9M`, Tahunan = `FY`.
- Checks (an ERROR blocks saving): revenue - cost = gross profit; operating income + finance income -
  finance costs + non-operating items - final tax = profit before tax; profit before tax + tax =
  profit; parent + NCI = profit; assets = liabilities + equity; current + non-current totals; cash
  flow sections re-add to their totals (payments signed negative); net change and cash roll-forward.
- Par value is not in the filing: it is inferred as the only standard par value for which
  share capital / par is a whole number of shares that reproduces the reported basic EPS. This needs
  share capital in rupiah. When no par value fits (a USD reporter: INDY's share capital is USD
  56,892,154), shares outstanding come from an exact **EPS denominator** instead (basic EPS = profit
  attributable to the parent / weighted shares outstanding, treasury shares excluded), accepted only when
  share capital and treasury stock are unchanged through the period, there are no discontinued
  operations, the EPS has enough decimals to fix the count to within one share, and profit / EPS is a
  whole number. INDY FY2023: 119,683,800 / 0.0230042062839776 = 5,202,692,000 (5,210,192,000 listed
  shares less 7,500,000 treasury shares, 0.144%). The count applies to every date of the filing with the
  same share capital and treasury stock (INDY: 2021-12-31 .. 2023-12-31); weighted shares only to the
  period it was derived from. The other INDY filings do not qualify (EPS 0.0019 allows 5.17 .. 5.45
  billion shares; FY2022's 0.0868828938473089 gives 5,210,191,995, not a whole number) and store no
  counts; valuations of later dates use the latest share snapshot, as for every company.
- Stored values are compared with the filing at the column's scale (amounts 4 decimals, EPS and share
  counts 8), with the half-up rounding Postgres applies on insert: INDY's USD EPS 0.0868828938473089
  is stored and verified as 0.08688289.
- Depreciation = additions to accumulated depreciation (PP&E + right-of-use); amortization =
  intangibles opening + purchases - closing (current period only).
- Current-period data replaces stored data; comparatives only fill gaps (differences are reported);
  a period of unknown audit status takes the provenance of a filing that states it.
- A period's revenue breakdown is stored as a whole, from one filing: issuers re-cut the same revenue
  between years (HRTA's FY2024 report splits FY2023 "Grosir" into "Grosir" + "Ekspor", the FY2023
  report shows one "Grosir" line), so mixing two breakdowns counts revenue twice. The current period
  replaces the period's breakdown (segments only an older filing reported are removed, listed in
  `removedFromPeriod`); a comparative is saved only when the period has no breakdown yet.
- Balance-sheet cash = cash-flow ending cash is checked for the current period. Many issuers present
  cash in the cash flow statement net of bank overdrafts, which the balance sheet carries within
  short-term bank loans (GGRM: ending cash 3,351,361 million vs. balance-sheet cash 3,613,292 million in
  FY2025; the 261,931 million are the overdraft part of 761,931 million short-term bank loans). The
  mapper reports this as a warning; the verification accepts it with a note when the stored figures are
  the filing's own and the difference is positive and within short-term borrowings, otherwise it is a
  problem (`INCOMPLETE`).
- Restatements show up as comparative differences and are kept as filed in the period's own filing,
  e.g. INDF's FY2023 report moves Rp 36,509 million of FY2022 operating payments to investing;
  FY2022 keeps the FY2022 filing's figures.
- Unsupported (rejected with 422): balance sheet by order of liquidity (`1220000`), profit or loss
  by nature (`1312000` / `1322000`).

Revenue segments come only from the breakdown sheets `1617000` (by type) and `1618000` (by
source). Some issuers leave both blank and disclose segments only in the PDF notes (e.g. INDF:
the sheets contain the template labels without names or amounts); the upload then stores no
segments for the filing, by design, and every other table as usual.

Prices are not part of a filing; `market_snapshot` and `valuation_snapshot` need `price_daily`
rows: run the price ingestion after the upload (`POST /api/v1/prices/ingestions?exchange=IDX&ticker=...`,
[PRICE_INGESTION_DOCS.md](PRICE_INGESTION_DOCS.md)), or load a seed script such as
`V1.0.5__data_HRTA_market.sql`.

## 5. Tests

| Test                             | What it proves                                                                                                                                                                                                                                       |
|----------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `FilingMapperHrtaTest` (unit)    | the six HRTA filings in `data/HRTA/xlsx` map to exactly the values validated for `V1.0.4__data_HRTA_financials.sql` (`src/test/resources/ingestion/hrta_expected.json`): every field of every column, segments, share counts, par value, audit flags |
| `FilingMapperLegacyTemplateTest` (unit) | the pre-2023 template: INDF FY2022 maps to the same FY2022 figures as the comparative column of the INDF FY2023 filing (except the restated operating / investing cash flow), incl. share capital and depreciation from the `1 CurrentYear` sheets; the revenue breakdowns of GGRM, HRTA and INDY FY2022 reconcile to revenue in both columns, and HRTA / INDY FY2022 equal their FY2023 filings' comparatives |
| `IngestionRepositorySegmentsTest` | a current-period breakdown removes a segment that only another filing stored for the period (rolled back)                                                                                                                                          |
| `BackendApplicationTests` (unit) | the application context starts (SQL init, Spring AI client, agent beans)                                                                                                                                                                             |
| `FinancialStatementUploadTest`   | the asynchronous upload with a mocked agent: 202 job, background processing, stored once per checksum, wrong files rejected, file download; see [INGESTION_JOBS_DOCS.md](INGESTION_JOBS_DOCS.md)                                                     |
| End-to-end (manual)              | uploading all six filings through the endpoint into an empty database reproduces the seed data                                                                                                                                                       |

Run the unit tests with `cd backend && ./mvnw test` (needs the Postgres on localhost:5432: the full
stack from the start scripts, or `docker compose up -d postgres redis` in `backend/`).

End-to-end check of this version (empty database, all six HRTA filings uploaded through the
endpoint, `deepseek/deepseek-v4-flash-0731` via OpenRouter):

| Filing       | Status    | Duration | Model calls | Tool calls | Tool errors | Parallel groups | Rounds |
|--------------|-----------|---------:|------------:|-----------:|------------:|----------------:|-------:|
| 2025-I       | COMPLETED |     77 s |          14 |         17 |           0 |               2 |      1 |
| 2025-II      | COMPLETED |     65 s |          12 |         14 |           0 |               2 |      1 |
| 2025-III     | COMPLETED |     70 s |          11 |         14 |           0 |               2 |      1 |
| 2025-Tahunan | COMPLETED |     46 s |          11 |         12 |           0 |               2 |      1 |
| 2026-I       | COMPLETED |     62 s |          10 |         14 |           0 |               2 |      1 |
| 2026-II      | COMPLETED |     62 s |          11 |         14 |           0 |               2 |      1 |

Compared with the validated seed: `reporting_period` (incl. source filing and audit flag),
`income_statement`, `balance_sheet`, `cash_flow_statement`, `segment_financial` (59 rows),
`share_snapshot` and `company` are identical (0 differences); only the English segment names are
worded differently. The same result was obtained with the filings uploaded out of chronological
order (2026-II, 2025-Tahunan, 2025-I, 2026-I, 2025-III, 2025-II).

Older annual filings (pre-2023 template and later re-cut breakdowns), uploaded after the 2024 filings:

| Filing                 | Status    | Duration | Model calls | Tool calls | Rounds | Note |
|------------------------|-----------|---------:|------------:|-----------:|-------:|------|
| INDF 2022-Tahunan      | SUCCEEDED | 50 s | 9 | 8 | 1 | pre-2023 template (rejected with 422 before: zip-bomb guard, date headers) |
| HRTA 2022-Tahunan      | SUCCEEDED | 71 s | 10 | 9 | 1 | pre-2023 template |
| HRTA 2023-Tahunan, before the segment fix | INCOMPLETE | 440 s | 39 | 36 | 3 | FY2024's comparative "Ekspor" segment left in FY2023: segments 17.13 T vs revenue 12.86 T; the agent cannot delete rows and retried |
| HRTA 2023-Tahunan, after  | SUCCEEDED | 87 s | 13 | 13 | 1 | `removedFromPeriod: ["Penjualan perhiasan dan logam mulia - Ekspor"]`; FY2022 comparative `KEPT_EXISTING` |
| GGRM 2022-Tahunan, before | INCOMPLETE | 192 s | 19 | 15 | | "balance-sheet cash 4,407,033 M != cash-flow ending cash 3,709,026 M" (cash net of bank overdrafts) |
| GGRM 2024-Tahunan, before | FAILED | 206 s | 0 | 0 | | `OpenAIInvalidDataException: Error reading response` (OpenRouter read timeout, no retry) |
| GGRM 2022 / 2024 / 2025-Tahunan, after | SUCCEEDED | 86 / 55 / 58 s | 12 / 11 / 12 | 13 / 12 / 13 | 1 | overdraft difference accepted as filed (note); 2022 with revenue segments from the pre-2023 sheets |
| INDY 2022 / 2023-Tahunan, before | INCOMPLETE | 113 / 120 s | 18 / 18 | 13 / 15 | | "basic_eps: stored 0.08688289 but the filing says 0.0868828938473089" (USD EPS, NUMERIC(20,8)) |
| INDY 2022 / 2023-Tahunan, after | SUCCEEDED | 49 / 57 s | 10 / 10 | 11 / 11 | 1 | compared at the stored scale; 2022 with revenue segments from the pre-2023 sheets |

INDY's share counts come from the FY2023 filing's exact EPS denominator (5,202,692,000, section 4);
the 2022, 2024, 2025 and 2026-II filings cannot give a count and `saveShareSnapshots` reports that
(the filing still completes). The extra rounds of INDY 2024 / 2025 came from the agent retrying that
save. Re-uploading INDY 2023-Tahunan stored the counts (SUCCEEDED, 61 s, 12 model calls, 0 tool
errors); INDY then has market caps from 2022-01-03 and valuation snapshots for every period end.
