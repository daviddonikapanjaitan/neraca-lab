# Neraca Lab - AI Financial Statement Ingestion (v1)

Upload an IDX XBRL financial statement workbook (`FinancialStatement-<period>-<TICKER>.xlsx`, as
downloaded from idx.co.id) and an AI agent built with **Spring AI 2.0** stores it in the database
(see [DB_SCHEMA_DOCS.md](DB_SCHEMA_DOCS.md)) using tool calling.

## 1. Endpoint

```http
POST /api/v1/financial-statements/upload
Content-Type: multipart/form-data
file=<FinancialStatement-2026-II-HRTA.xlsx>
```

```bash
curl -F "file=@data/HRTA/xlsx/FinancialStatement-2026-II-HRTA.xlsx" \
     http://localhost:8080/api/v1/financial-statements/upload
```

| HTTP | `status`     | Meaning                                                                                      |
|------|--------------|----------------------------------------------------------------------------------------------|
| 200  | `COMPLETED`  | everything in the filing is stored and the database read-back found nothing pending or wrong |
| 202  | `INCOMPLETE` | the agent finished but something is pending or failed validation (see `verification`)        |
| 422  | -            | not an .xlsx / not an IDX XBRL workbook / unsupported template (ProblemDetail body)          |
| 413  | -            | file larger than 20 MB                                                                       |
| 502  | `FAILED`     | the AI provider could not be reached or the agent crashed (`error`)                          |

The response is the full audit trail of the run:

| Field                              | Content                                                                   |
|------------------------------------|---------------------------------------------------------------------------|
| `filing`, `company`                | what the workbook is, which company it belongs to                         |
| `plan`, `planFromModel`            | the plan made by the planner (Plan-and-Execute)                           |
| `steps`                            | every model turn: its `thought` (ReAct), requested and offered tools      |
| `toolCalls`                        | every tool execution: arguments, result, error, duration, `parallelGroup` |
| `rounds`                           | per execution round: verification and the reviewer's reflection           |
| `savedStatements`, `savedSegments` | rows written per column (`INSERTED`, `UPDATED`, `KEPT_EXISTING`)          |
| `verification`                     | final deterministic database read-back                                    |
| `metrics`                          | duration, model calls, tool calls, tool errors, parallel tool groups      |

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
| `saveRevenueSegments`    | write | segments with English names and types; amounts from the filing                             |
| `saveShareSnapshots`     | write | share counts at every date in the statements of changes in equity                          |
| `refreshDerivedData`     | write | re-runs `V1.0.6__data_metrics_valuation.sql` (market / valuation snapshots, metrics)       |
| `verifyStoredData`       | read  | database read-back: pending work and inconsistencies                                       |

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
  share capital / par is a whole number of shares that reproduces the reported basic EPS.
- Depreciation = additions to accumulated depreciation (PP&E + right-of-use); amortization =
  intangibles opening + purchases - closing (current period only).
- Current-period data replaces stored data; comparatives only fill gaps (differences are reported);
  a period of unknown audit status takes the provenance of a filing that states it.
- Unsupported (rejected with 422): balance sheet by order of liquidity (`1220000`), profit or loss
  by nature (`1312000` / `1322000`).

Prices are not part of a filing; `market_snapshot` and `valuation_snapshot` need `price_daily`
rows (loaded separately, e.g. `V1.0.5__data_HRTA_market.sql`).

## 5. Tests

| Test                             | What it proves                                                                                                                                                                                                                                       |
|----------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `FilingMapperHrtaTest` (unit)    | the six HRTA filings in `data/HRTA/xlsx` map to exactly the values validated for `V1.0.4__data_HRTA_financials.sql` (`src/test/resources/ingestion/hrta_expected.json`): every field of every column, segments, share counts, par value, audit flags |
| `BackendApplicationTests` (unit) | the application context starts (SQL init, Spring AI client, agent beans)                                                                                                                                                                             |
| End-to-end (manual)              | uploading all six filings through the endpoint into an empty database reproduces the seed data                                                                                                                                                       |

Run the unit tests with `cd backend && ./mvnw test` (needs the Docker Postgres).

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
