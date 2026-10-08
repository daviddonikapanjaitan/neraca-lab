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
| `metrics`                          | duration, model calls, tool calls, tool errors, parallel tool groups, model retries, models the responses report |

A typical filing takes 1-2 minutes and 10-15 model calls (CEKA: 72-129 s, 11 calls, no retries).

**Speed.** Most of a job's time used to be spent waiting for the model, not working: DeepSeek V4 Flash
(a reasoning model) thought for over a minute before answering the planner and the reviewer, OpenRouter
reset such calls after 60 s ("stream was reset: CANCEL"), and each was retried twice before falling back
(planner: 3 min 16 s for the default plan anyway; 25 of the last 30 jobs had `planFromModel: false` and
2+ model retries, and took 4-5 minutes). Measured on the real planner request (CEKA FY2025): reasoning on
69 s / 8,773 reasoning tokens, `reasoning.effort: low` > 150 s, reasoning off 10 s / 0 reasoning tokens with
a complete plan. The agent only picks tools - every number is computed and verified in Java - so it runs
without reasoning (`model-reasoning: false`), and a call that still stalls is abandoned after 45 s and
retried (`model-call-timeout`) instead of waiting for the 180 s read timeout.

When the model still fails mid-run after its retries (a provider that keeps stalling), the run is not
failed: `DeterministicFinisher` does the remaining standard steps with the agent's own tools (find /
register the company, save every column that extracts ready, save revenue segments under the filing's
names and slot types, save share counts, refresh derived data), and the deterministic verification decides
COMPLETED / INCOMPLETE; anything that needs judgement (an unclassified income line, a column failing its
checks) stays open, and the job notes list every step.

Measured on the five CEKA filings with the real model after these changes: 129 s, 107 s, 72 s, 79 s, 86 s,
all COMPLETED and verified, 0 model retries, the plan from the model (before: 517-903 s, failed or
incomplete; earlier filings typically 220-310 s with 2-3 retries).

**Time limit.** An upload job may run at most `neracalab.ingestion.job-timeout` (default 5 minutes,
env `INGESTION_JOB_TIMEOUT`), counted from the moment it starts running (waiting in the queue does not
count). At the limit the job is stopped and `FAILED` with the stage "Stopped after the 5 minutes limit
on <filing>" and a message naming the step it stopped before (`JobDeadline`). It really stops: a model
call waits at most until the limit (the request is abandoned; a model call writes nothing), a retry is
not started when its pause would end after the limit, no tool - in particular no write - starts after
it, and the planner's and reviewer's fallbacks do not swallow it. What was saved before the limit is
kept (derived data is refreshed for it); submit the file again to retry. A single stalled model call
can take up to the 180 s read timeout, so a slow provider can use up most of the limit (CEKA FY2024 /
FY2025 ran 8-9 minutes on three timed-out OpenRouter responses before the limit existed).

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

**Every request names `OPENAI_MODEL`.** Spring AI 2.0's `OpenAiChatOptions` fills a missing model
with its own default, `gpt-5-mini`, and per-request options override the configured default. The
agent's planner, executor and reviewer built their options without a model, so until this was fixed
every ingestion call went to `openai/gpt-5-mini` on OpenRouter, whatever `OPENAI_MODEL` said (the
stock screening was not affected: `LlmGateway` always sets its model). `IngestionAgent.options()` now
sets the configured model on every request (`IngestionAgentModelTest` pins both the Spring AI default
and the fix), and the job result lists the models the responses report (`metrics.models`, e.g.
`["deepseek/deepseek-v4-flash-0731"]`), so a substitution is visible in the job.

| Property                                | Default | Meaning                                         |
|-----------------------------------------|---------|-------------------------------------------------|
| `neracalab.ingestion.max-iterations`    | 30      | model turns per execution round                 |
| `neracalab.ingestion.reflection-rounds` | 2       | extra execution rounds the reviewer may request |
| `neracalab.ingestion.temperature`       | 0.0     | sampling temperature                            |
| `neracalab.ingestion.model-retries`     | 2       | retries of a model call that failed transiently (timeout, network error, HTTP 408 / 429 / 5xx); other errors fail at once |
| `neracalab.ingestion.retry-backoff`     | 2s      | pause before the first retry, doubled for each further one |
| `neracalab.ingestion.model-reasoning`   | false   | let a reasoning model think before answering; off sends `"reasoning": {"enabled": false}` (env `INGESTION_MODEL_REASONING`) |
| `neracalab.ingestion.model-call-timeout`| 45s     | a model call taking longer counts as a stalled response and is retried (env `INGESTION_MODEL_CALL_TIMEOUT`) |
| `neracalab.ingestion.job-timeout`       | 5m      | longest an upload job may run (from start, queue time excluded); then stopped and FAILED (env `INGESTION_JOB_TIMEOUT`) |
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
| Conditional Tool Calling | `IngestionAgent.offeredTools`: `registerCompany` only after `findCompany` found nothing, save tools only once the company exists, `classifyIncomeLines` only while unknown income lines exist or a column the agent classified still fails a check (its own classification can then be revised, e.g. to `IGNORE`), `refreshDerivedData` only after a write |
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
| `classifyIncomeLines`    | write | classifies unknown income-statement lines, or revises the agent's own classification while its column still fails a check (re-validated: profit before tax must reconcile) |
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

**Infrastructure Industry taxonomy.** Issuers of the infrastructure, utilities and transportation
sector (e.g. SMDR, Samudera Indonesia, "K. Transportation & Logistic") file with the IDX
"Infrastructure Industry" taxonomy: the same statement roles and line items as the General Industry
one, under sheet codes starting with 3 instead of 1. `IdxWorkbookReader` maps them to the General codes,
so the mapper is the same:

| Role | General | Infrastructure |
|------|---------|----------------|
| balance sheet (current / non-current; by liquidity) | `1210000`, `1220000` | `3210000`, `3220000` |
| profit or loss (by function; by nature; before tax) | `1311000`, `1312000`, `1321000`, `1322000` | `3311000`, `3312000`, `3321000`, `3322000` |
| changes in equity | `1410000` (+`PY`) | `3410000` (+`PY`, pre-2023 `3410000 1 CurrentYear`) |
| cash flow (direct; indirect) | `1510000`, `1520000` | `3510000`, `3520000` |
| PP&E / right-of-use, revenue by type / source | `1611000`, `1612000`, `1617000`, `1618000` | `3611000`, `3612000`, `3617000`, `3618000` |

Own labels of the taxonomy that the mapper reads: "Payments for acquisition of property and equipment"
and "Payments for advances for purchase of property and equipment" (capex; General: "... property,
plant and equipment"), "Short-term non-bank loans" (short-term debt). "Current other financial assets"
is the General "Other current financial assets" (not a marketable security, as there). An unknown
profit-or-loss line such as "Interconnection expenses" is classified by the agent
(`classifyIncomeLines`, re-validated), like any unknown line. Sheet 1000000 is shared by all taxonomies.

**Financial and Sharia Industry taxonomy (banks).** Banks (e.g. BNGA, Bank CIMB Niaga, "G. Financials /
G1. Banks") file with sheet codes starting with 4. `IdxWorkbookReader` maps them to the General codes
too and records the taxonomy (`IdxWorkbook.taxonomy()`, `IdxTaxonomy.FINANCIAL`), but the statements
and line items differ, so `FilingMapper` reads them with its own bank mapping:

| Role | Financial | read as |
|------|-----------|---------|
| balance sheet by order of liquidity | `4220000` | `1220000` (the current / non-current `4210000` is rejected) |
| profit or loss by nature (OCI before tax; net of tax) | `4322000`, `4312000` | `1322000`, `1312000` |
| changes in equity | `4410000` (+`PY`) | `1410000` |
| cash flow (direct; indirect) | `4510000`, `4520000` | `1510000`, `1520000` |
| PP&E (incl. right-of-use) / right-of-use roll-forward | `4611000`, `4612000` | `1611000`, `1612000` |

| Column | Bank value |
|--------|------------|
| `revenue` | interest and sharia income + fee and commission, trading, FX, investment, dividend and other operating income |
| `cost_of_revenue` / `gross_profit` | interest expense (+ the syirkah fund holders' share) / revenue - interest expense |
| `operating_expenses` | G&A + selling + impairment charges + other operating expenses (recoveries are other operating income) |
| `operating_income` | "Total profit from operation" as filed, checked against the lines |
| `ebit`, `ebitda` | NULL: interest is a bank's operating revenue and cost |
| `cash_and_equivalents` | cash and cash equivalents of the cash flow statement at that date (the balance sheet shows only "Cash") |
| `marketable_securities` | marketable securities less allowance (government bonds excluded) |
| `total_liabilities` | "Total liabilities" as filed |
| `temporary_syirkah_funds` | "Total temporary syirkah funds" (sharia depositors; neither liabilities nor equity); assets = liabilities + these + equity. Before, they were added to `total_liabilities` (BMRI FY2025: 2,502,546,028 million stored for 2,212,925,204 filed) |
| `long_term_debt` | borrowings + securities issued + subordinated loans, all maturities (deposits, interbank deposits and repos are not debt) |
| `short_term_debt`, `current_assets`, `current_liabilities`, `accounts_receivable`, `inventory` | NULL: no maturity split / no current classification / no trade receivables or inventories |
| `capital_expenditure` | PP&E + intangibles acquisitions net of disposals, as filed |
| `debt_issued` / `debt_repaid` | borrowing, bond, MTN, sukuk and subordinated loan proceeds / repayments, plus the net change in securities issued |

The NULLs are deliberate: total debt, net debt, enterprise value, current ratio, working capital and
NCAV are not computed for a bank (they would mislead), while P/E, P/B and P/S are. The profit-or-loss
checks re-add "Total profit from operation" and profit before tax from the classified lines
(`IncomeLineCategory.FINANCIAL_KNOWN`; "Insurance commission income", a bank's bancassurance fees, is revenue
(BTPN FY2023: 54,570 million); a bank's insurance subsidiary: "Revenue from insurance premiums" is revenue and
"Claim expenses" cost of revenue (BMRI); other insurance lines are left to `classifyIncomeLines`). There are
no revenue-segment notes. Share counts are not guessed: BNGA has two share classes with different
par values and treasury stock, and its 2-decimal EPS does not give an exact count, so no
`share_snapshot` count is derived from the filings; the audited counts come from the annual reports
instead (`V1.0.13__data_BNGA_shares.sql`, see section 4).
Other IDX taxonomies (property, securities, insurance) are not supported.

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
  A count is carried only to dates of the same filing with the same share capital and treasury
  stock; a stock split leaves share capital unchanged, so a split after the last filing that gave a
  count is not visible (INDY's later EPS, 0.0019 .. 0.00196, agree with 5,202,692,000). SMDR (USD
  reporter, EPS with 3 decimals, a 2023 stock split: 0.065 -> 0.005) gets no share counts from its
  filings; they come from public sources instead (`V1.0.12__data_SMDR_shares.sql`): 3,275,120,000 shares
  of Rp 25 until the 1:5 split of 2023-01-31, 16,375,600,000 of Rp 5 since (KSEI), no treasury stock,
  consistent with every reported EPS. The prices are split-adjusted, so the counts are too:
  16,375,600,000 at every date from 2020-12-31 (from when the filings show share capital unchanged);
  earlier prices get no market cap. The script runs on start and after every upload (it needs the
  company) and only fills counts that are empty. SMDR on 2026-10-05: USD 0.0221727 x 16,375,600,000 =
  USD 363.1 million (Rp 398 x 16,375,600,000 = Rp 6.5 trillion), P/E 6.57.
  BNGA (two share classes, class A Rp 5,000 and class B Rp 50, treasury shares for the MESOP / MRT
  programmes, EPS with 2 decimals) gets no share counts from its filings either; the audited year-end
  counts of note 33 of its 2023 and 2025 annual reports come from `V1.0.13__data_BNGA_shares.sql`:
  outstanding 24,929,713,961 (2021), 24,933,123,961 (2022), 25,024,439,161 (2023), 25,137,965,543
  (2024), 25,140,519,043 (2025), each + treasury shares = 25,131,606,843 issued (25,142,205,843 after
  the 10,599,000-share issue of 2024-01-31), which matches the filed share capital exactly
  (71,853,936 x 5,000 + 25,059,752,907 x 50 = Rp 1,612,257 million). Same rules as SMDR's script.
  BNGA on 2026-10-06: Rp 1,700 x 25,140,519,043 = Rp 42.7 trillion, P/E 6.23, P/B 0.76.
- **Share counts from the web** (`WebShareCounts`, before the agent runs): when the filing gives no share
  count, the ingestion reads Yahoo Finance's published year-end and quarter-end shares outstanding,
  issued and treasury shares (`/ws/fundamentals-timeseries`, `annual|quarterlyOrdinarySharesNumber`,
  `...ShareIssued`, `...TreasurySharesNumber`; no crumb). `FilingMapper.withWebShareCounts` uses a count
  only at a date of the filing's statements of changes in equity and only when (1) outstanding + treasury
  = issued whenever all three are published and (2) profit attributable to the parent / count reproduces
  the filing's own basic EPS of the period that date opens or closes, within half a unit of the EPS's last
  decimal + 2% (period-end vs weighted shares; BNGA at most 0.4%). A wrong company, unit or a stock split
  the filing does not reflect is far outside and rejected (SMDR's FY2022 filing: 0.013 vs 0.065). Accepted
  counts are saved by `saveShareSnapshots` like filing counts but only fill dates without a stored count
  (never replacing a filing or seed count); weighted shares stay unknown. Results on the real filings:
  BNGA FY2022 .. FY2025 get exactly the audited counts above; BNGA H1 2026 rejects Yahoo's 2026-06-30
  point (outstanding = issued although 336,000 treasury shares are published). A failed fetch (network,
  HTTP 429) leaves the counts empty with a note; the ingestion itself continues. Switch off with
  `neracalab.ingestion.web-share-counts: false`.
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
- **Declared rounding contradicted by the amounts.** ASGR's FY2023 workbook declares "Jutaan / In
  Million" but carries full amounts (total assets 2,682,813,000,000, not 2,682,813); taken at its word,
  every amount was stored a million times too large (and the derived metrics overflowed). The mapper
  reads the amounts as full amounts (`FilingMapper.unit()` = 1, warning in the overview and the job
  notes) when every amount of the three statements (at least 10, EPS excluded) is a whole multiple of
  the declared unit and the filing's own EPS agrees: profit attributable to the parent / basic EPS gives a
  plausible share count (ASGR: 1,348,780,500) with full amounts and an impossible one (> 10^13) with the
  declared unit. The checks keep the declared rounding as their tolerance. A unit under which the EPS
  implies an impossible share count is a template problem (rejected), never stored.
- **EPS filed in the rounding unit.** SIMP's H1 2026 workbook has its amounts correctly in millions but
  files the basic EPS in millions too (0.0000563514954 for Rp 56.35). Profit / EPS then gives the real
  share count (15.5 billion) from the filed figures and an impossible one with the declared unit, while the
  amounts are not whole multiples of the unit (which tells this case from full amounts): the per-share
  figures are multiplied by the unit, with a warning. Plausible share counts: 10^6 .. 10^13.
- **Revenue not tagged.** SIMP's FY2023 workbook tags only "Total gross profit", no "Sales and revenue"
  or cost. When neither is reported but gross profit is, and profit before tax reconciles from it, revenue
  and cost of revenue are left empty with a warning (instead of blocking the filing).
- **Gaps filled across filings.** A comparative column fills the fields a stored row has empty
  (outcome `FILLED_GAPS`, e.g. SIMP FY2023 revenue from the FY2024 filing) and never changes a stored
  value; the period's own filing replaces stored values, but a field it does not report keeps the value
  another filing stored. Whatever the upload order, SIMP FY2023 ends with the revenue of the FY2024
  filing and every other figure of its own filing. An EPS and its share count come from one filing: a
  comparative fills a share count only where the stored EPS equals its own (BMRI FY2022: the FY2023
  filing's post-split count beside the pre-split EPS gave EPS x shares = twice the profit).
- **Share split restated in a comparative.** BMRI split 2:1 in 2023: its FY2022 filing states EPS 882.52,
  the FY2023 filing's comparative 441.26 for the same profit. When the profit attributable to the parent is
  unchanged (0.5%) and the stored EPS is a whole multiple or fraction k = 2..100 of the comparative's (1%),
  the period's per-share figures are replaced by the comparative's (outcome `SPLIT_ADJUSTED`), and every
  earlier period whose profit / EPS gives the same share count as the period's old figures (10%) is divided
  by k (BMRI FY2021: 601.06 -> 300.53); the same test keeps a period from being adjusted twice. Share counts
  and prices are split-adjusted, so EPS-based ratios stay consistent. Other restatements in comparatives
  (BMRI's FY2023 cash flows in its FY2024 filing, FY2024 EPS 602.41 -> 597.67) keep the period's own filing.
- **EPS checked against the share count.** A filed EPS is checked against the filing's own share
  count. (1) Across columns: each column's profit attributable to the parent / EPS must fit the share
  capital range of its period (80% of the smaller .. 105% of the larger count) at the same par value.
  NCKL's FY2024 workbook files its current EPS one more decimal place off (10.11 after its rounding unit,
  for 101.1): it fits only par 10, the prior column's 92.39 only par 100. Before, par 10 was inferred and
  631 billion shares (ten times too many) were stored. Now a workbook whose columns contradict each other
  gives no share counts, and its EPS and share counts are *rejected*: stored as NULL even over a value
  stored before (`MappedStatement.rejected`); the company's other filings agree and supply them (NCKL
  FY2025's comparative fills FY2024 with EPS 101.1 and 63,098,600,000 shares). (2) Within a column, with
  the share count known: an EPS off by exactly a power of ten is corrected when profit / shares confirms
  it within 2% (INDF's H1 2025 workbook files the H1 2024 EPS in millions, 0.000439, stored as 439); any
  other mismatch of 3x or more is not stored.
- **A filing refreshes what it wrote.** A comparative column normally only fills gaps, but a period
  whose data this very filing wrote (`reporting_period.source_filing`) is refreshed by it (save mode
  "REFRESH"), so re-running a workbook after a mapper fix corrects its own data (INDF H1 2024: 0.000439
  -> 439).
- **A cash flow section without any activity.** CEKA's H1 2026 cash flow has no financing line and no
  financing total. A missing section total counts as 0 (warning) only when the section has no reported
  line and the other totals add up to the net change in cash exactly (228,589,953,937 - 9,003,638,218 =
  219,586,315,719); otherwise it is an error as before.
- **One amount reported twice in a column.** Also a specific line and a residual "Other ..." line with
  the same amount (CEKA H1 2025: "Interest and finance costs" and "Other expenses" 79,134; interest paid
  79,134): counted once on the specific line, only when profit before tax then reconciles exactly.
- **One amount reported twice in a column (signed line).** ASGR's H1 2026 filing reports an H1 2025 loss both as
  "Other expenses" 2,750 and as "Other gains (losses)" -2,750 (millions); profit before tax 139,690
  counts it once. When profit before tax does not reconcile, an expense (income) line and a signed
  gains / losses line with the same contribution are treated as one amount, counted once on the signed
  line (the filing's current-column presentation), but only if profit before tax then reconciles
  exactly; a `double_reported` warning and derivation record it. A line the agent classified is never
  second-guessed.
- **An insurance expense line shown for information (banks).** BMRI's FY2025 filing shows, in its FY2024
  column, "Claim expenses" 10,574,450 million beside "Revenue from insurance premiums" 2,520,813 that are
  already net of the claims; its "Total profit from operation" 76,059,595 does not deduct them (the FY2025
  column reports the net insurance result 550,415 and no claims). When "Total profit from operation" does
  not reconcile and ignoring one insurance expense line (`FilingMapper.INSURANCE_EXPENSE_LINES`) makes every
  check pass, that line is ignored; a `memo_line` warning and derivation record it. A line the agent
  classified is never second-guessed. Before, the FY2024 column was blocked and the job INCOMPLETE.
- Unsupported (rejected with 422): balance sheet by order of liquidity (`1220000`), profit or loss
  by nature (`1312000` / `1322000`) in the General and Infrastructure taxonomies; the current /
  non-current balance sheet (`4210000`) and profit or loss by function (`4311000` / `4321000`) in the
  Financial taxonomy.

Segments are stored by name; a name a breakdown files on several lines (CEKA: "Produk Palm Kernel" as
domestic revenue 2 and export revenue 1) is qualified by its slot, "Produk Palm Kernel (domestik)" /
"(ekspor)" (before, the second line overwrote the first: CEKA FY2025 segments summed to 6.34 instead of
9.73 trillion). Names filed once keep their name; a name that still repeats blocks the breakdown.

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
| `FilingMapperInfrastructureTest` (unit) | the Infrastructure Industry taxonomy: all five SMDR filings map without errors or unclassified lines; capex includes the infrastructure labels (FY2025: -81,650,677); every comparative column equals the previous filing's current column except SMDR's own reclassification of 161,196 from "Other income" to "Other gains (losses)" in FY2024 operating income; no share count is guessed |
| `ModelSpeedSettingsTest` (unit, local HTTP server) | the real Spring AI client sends `"reasoning": {"enabled": false}` (and nothing when reasoning is on); a call slower than the per-call timeout is retried at once and a fast answer used; a call that always stalls gives up after the retries in under 3 s |
| `DeterministicFinisherTest` (Docker Postgres, rolled back) | a model failing every call: HRTA H1 2026 is still stored, refreshed and verified complete by the deterministic finish; display names from legal names; segments keep the filing's names and types and reuse stored English names |
| `JobDeadlineTest` (unit) | the time limit inside the agent: a slow model call stops at it, no retry pause past it, no model call and no tool after it, the planner's fallback does not swallow it |
| `UploadJobTimeoutTest` (Docker Postgres) | an upload job over a 3 s limit (mock agent working 4 s) ends `FAILED` with "Stopped after the 3 seconds limit on HRTA 2026 H1" within seconds of the limit |
| `FilingMapperEpsTest` (unit) | NCKL FY2024: contradicting EPS columns give no share counts, EPS and share counts rejected (cleared), FY2025's comparative gives 101.1 at par 100; all MYOR, NCKL and PTSN filings map without errors |
| `FilingMapperCekaTest` (unit) | all five CEKA filings without errors; "Produk Palm Kernel" domestic and export kept as two segments (FY2025 total = revenue 9,733,304,188,977); a repeated name blocks the breakdown; H1 2026 financing 0 without lines or total; H1 2025 interest also filed as "Other expenses" counted once |
| `IngestionAgentRetryTest` (unit) | a model call is retried after a read timeout (`OpenAIInvalidDataException` with an `InterruptedIOException`), gives up after `model-retries`, never retries a permanent error |
| `IngestionAgentModelTest` (unit) | Spring AI fills a missing model with `gpt-5-mini`; the agent's options name the configured model and the model a response reports is recorded in `metrics.models` |
| `ConfiguredChatModel` (test helper) | the model the agent tests use, never written in Java: `spring.ai.openai.chat.model` from `application.yaml` (`${OPENAI_MODEL:<default>}`), with `OPENAI_MODEL` from the environment, else `backend/.env`, else the `application.yaml` default (the application's own precedence) |
| `BngaShareSeedTest`              | the BNGA share-count script fills the five audited year-end counts (2021 .. 2025), outstanding + treasury = issued at every date, runs idempotently and never replaces a count already stored (rolled back) |
| `SmdrShareSeedTest`              | the SMDR share-count script fills 16,375,600,000 at eight dates from 2020-12-31 and the 2023-01-31 1:5 split, runs idempotently and never replaces a count already stored (rolled back) |
| `FilingMapperFinancialTest` (unit) | the Financial and Sharia Industry taxonomy: all five BNGA filings and all four BTPN filings map without errors, warnings or unclassified lines; all five BMRI filings map without errors; BMRI FY2025's FY2024 column ignores "Claim expenses" shown for information (operating income 76,059,595 million, revenue 186,767,596), never against an agent classification; FY2025 bank income statement (revenue 30,631,359 million, operating income 8,782,085), balance sheet (cash equivalents from the cash flow, debt 8,140,477, current items NULL) and cash flow (capex -820,540, net securities issued in debt issued); the 2026 H1 prior year end; every comparative column equals the previous filing's current column; no share count is guessed |
| `StatementGapFillTest` (Docker Postgres, rolled back) | a comparative fills an empty revenue (`FILLED_GAPS`) but not a stored gross profit, nothing left to fill is `KEPT_EXISTING`, and the period's own filing does not wipe the filled revenue |
| `ShareSplitTest` (Docker Postgres, rolled back) | a 2:1 split restated by a comparative (`SPLIT_ADJUSTED`): the period and an earlier period on the old basis adjusted, a period on another basis not, nothing adjusted twice; a restated (non-split) EPS never gets the comparative's share count; split factors |
| `FilingMapperAsgrTest` (unit) | all five ASGR filings map without errors; the H1 2025 loss reported twice in the H1 2026 filing is counted once (profit before tax 139,690, operating income 116,372), not in the current column, never against an agent classification; the FY2023 workbook's full amounts under an "In Million" label are read as full amounts (total assets 2,682,813 million, as in the FY2022 workbook); SIMP: FY2023 full amounts without revenue (gross profit 3,358,216 million, revenue from the FY2024 comparative), H1 2026 EPS filed in millions (56.35), every SIMP filing without errors |
| `FilingMapperWebSharesTest` (unit) | web share counts against the real filings: BNGA FY2025 gets the three audited counts, H1 2026 rejects the inconsistent 2026-06-30 point, counts five times too high are rejected, SMDR's FY2022 filing rejects the split-adjusted count and its FY2025 filing accepts it, a filing with its own counts is kept |
| `WebShareCountsTest` (unit) | the fallback with a mocked Yahoo client: completes BNGA, a failed fetch leaves the filing unchanged with a note, no fetch when disabled or when the filing has counts |
| `IdxWorkbookReaderTest` (unit) | sheet names: General kept, pre-2023 `1 CurrentYear` / `2 PriorYear`, Infrastructure `3xxxxxx` and Financial `4xxxxxx` -> `1xxxxxx`; taxonomy of a sheet code |
| `IngestionRepositorySegmentsTest` | a current-period breakdown removes a segment that only another filing stored for the period (rolled back)                                                                                                                                          |
| `BackendApplicationTests` (unit) | the application context starts (SQL init, Spring AI client, agent beans)                                                                                                                                                                             |
| `FinancialStatementUploadTest`   | the asynchronous upload with a mocked agent: 202 job, background processing, stored once per checksum, wrong files rejected, file download; see [INGESTION_JOBS_DOCS.md](INGESTION_JOBS_DOCS.md)                                                     |
| End-to-end (manual)              | uploading all six filings through the endpoint into an empty database reproduces the seed data                                                                                                                                                       |

Run the unit tests with `cd backend && ./mvnw test` (needs the Postgres on localhost:5432: the full
stack from the start scripts, or `docker compose up -d postgres redis` in `backend/`).

End-to-end check of this version (empty database, all six HRTA filings uploaded through the
endpoint via OpenRouter). Correction: these runs and every run below were made before the model fix
(section 2), so they ran on `openai/gpt-5-mini`, not the configured `deepseek/deepseek-v4-flash-0731`.
First run on DeepSeek after the fix: SMDR 2026-II, SUCCEEDED, 317 s, 12 model calls, 2 retried read
timeouts, `metrics.models = ["deepseek/deepseek-v4-flash-0731"]`.

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
