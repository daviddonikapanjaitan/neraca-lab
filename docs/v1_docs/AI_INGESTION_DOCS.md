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
curl -H "$AUTH" -F "file=@data/IDX_XBRL/HRTA/xlsx/FinancialStatement-2026-II-HRTA.xlsx" \
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
| 422  | not an .xlsx / not an IDX XBRL workbook (ProblemDetail body; logged in `ingestion_error`). An IDX workbook the standard extraction cannot map is accepted (202) and stored by the full-text extraction, section 6 |
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
| `neracalab.ingestion.prices-after-upload` | true  | queue the company's daily prices when a filing was stored, so that it gets its market caps and valuations (env `INGESTION_PRICES_AFTER_UPLOAD`), section 4 |
| `neracalab.jobs.workers`                | 5       | uploads stored at the same time, each by its own agent (env `INGESTION_WORKERS`), also several filings of one company ([INGESTION_JOBS_DOCS.md](INGESTION_JOBS_DOCS.md), section 4) |
| `neracalab.ingestion.same-company-in-order` | false | true: filings of one company are stored one after the other in upload order instead (env `INGESTION_SAME_COMPANY_IN_ORDER`) |
| `neracalab.ingestion.provider-ignore`   | OpenInference | OpenRouter providers the ingestion's model calls never use (env `INGESTION_PROVIDER_IGNORE`), section 6 |
| `neracalab.ingestion.fallback.*`        | on, 3m  | the full-text extraction that takes over when the standard extraction does not store a workbook, and its time limits (section 6) |
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
| `refreshDerivedData`     | write | re-runs `V1.0.6__data_metrics_valuation.sql` (market / valuation snapshots, metrics), then deletes valuation metrics whose value became NULL; one run at a time, about 2 s, and a request that waited for a whole run is served by it |
| `verifyStoredData`       | read  | database read-back: pending work and inconsistencies (the final gate of `COMPLETED`)       |

**One company per (ticker, exchange).** The ticker comes from sheet `1000000` "Entity code",
trimmed and upper-cased (`Tickers.normalize`; a code that is not a ticker rejects the upload with
422); the exchange is always `IDX`. `findCompany` looks the company up by that key, and
`registerCompany` writes it with a single
`INSERT ... ON CONFLICT ON CONSTRAINT uq_company_ticker_exchange DO UPDATE ... RETURNING`, so two
uploads of the same company, even concurrent ones, end in one row. When several filings of a new company
are uploaded together, each looks it up, finds nothing and registers it: the ones that come second get the
company the first one registered (`registered: false`) instead of a refusal. The database enforces the
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

**Property and Real Estate Industry taxonomy.** Some property developers (e.g. CBDK, Bangun Kosambi
Sukses) file with the IDX "Property and Real Estate Industry" taxonomy: the General Industry roles and
line items under sheet codes starting with 2 (`2210000`, `2311000`, `2410000`, `2510000`, ...), read
under the General codes like the Infrastructure ones. A developer's "Current real estate assets" (land,
houses and apartments for sale) are its `inventory`, and "Payments for acquisition of investment
properties" (malls, offices for rent) are `capital_expenditure`; both labels also appear in developers
filing with the General taxonomy (e.g. PWON).

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
| `cost_of_revenue` / `gross_profit` | interest expense (+ the syirkah fund holders' share, filed with either sign: BRIS files it negative, -9,136,405 million in FY2025, and it is taken as a positive cost) / revenue - interest expense |
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

**Insurance Industry taxonomy (insurers).** Insurers (e.g. TUGU, Asuransi Tugu Pratama, "G4. Insurance") file with
sheet codes starting with 6: balance sheet by order of liquidity `6220000`, profit or loss by nature `6312000` /
`6322000`, changes in equity `6410000` (+`PY`), cash flow `6510000`, PP&E / right-of-use notes `6611000` / `6612000`,
and the statements of the tabarru' funds of the sharia participants `6332000` / `6342000`. Like the Financing
Industry, the sheets are read under the General codes (`IdxTaxonomy.INSURANCE`), so the share capital, the per-share
unit and the notes are read by the rules, but the line items (premiums, reinsurance, claims, underwriting results)
are not mapped by rules: the workbook goes straight to the full-text extraction (section 6) and is decided by the
model by design. The model gets an insurer's instructions: the accumulated tabarru' funds are
`temporary_syirkah_funds` (assets = liabilities + tabarru' funds + equity), revenue = premiums less the reinsurers'
share + commission, investment and other underwriting income, cost of revenue = claims less reinsurance claims +
changes in insurance liabilities less the reinsurers' share, operating income = profit before tax + finance costs,
and revenue - cost of revenue - operating expenses - finance costs must give profit before tax (section 6). Like a
bank, an insurer has no current / non-current split and no EV. Before, the reader did not know the 6xxxxxx sheets at
all: TUGU had no share counts and no valuation, its tabarru' funds were left out of its balance sheet or put in its
liabilities, and its EPS was stored as filed in thousands and millions.
Other IDX taxonomies (securities) are not supported.

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
  RAJA (USD reporter, EPS 0.0054) split 1:5 on 2026-07-16, after its latest filing (H1 2026): every filing states
  the EPS of before the split (profit / EPS = about 4.23 billion shares) while the published counts are those of
  after it (21,135,412,500), so the web counts below were rejected at every date (5 x) and RAJA had no market cap
  and no valuation. `V1.0.21__data_RAJA_shares.sql` fills 21,135,412,500 (split-adjusted, like the prices; no
  treasury stock) at the seven period ends from 2021-12-31, records the split with its real date in
  `corporate_action` and divides the EPS of the periods before it by 5 (only an EPS still on the old count: profit
  / EPS within 10% of 4,227,082,500). Its description starts with `Recorded from public sources: `, which
  `IngestionRepository.detectedSplits` reads like a split restated in the filings, so a filing of before the split
  that is uploaded later, or again, ends on the same basis. A split-adjusted EPS keeps two decimals more than it
  has (at most 8): 0.0054 / 5 = 0.00108, where two decimals left 0.00. RAJA on 2026-10-09: USD 0.03710938 x
  21,135,412,500 = USD 784.3 million, P/E 20.3, P/B 3.58. A company without share counts in its filings that splits
  after its latest filing needs such a script until a filing restates the split.
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
  **Dates the website does not publish.** Yahoo Finance publishes four year ends, so the opening dates of an
  older filing have no count of their own: BBCA's FY2022 filing got 123,275,050,000 at 2022-12-31 and nothing
  at 2021-12-31 and 2020-12-31, and FY2021 had no market cap and no valuation. An accepted count is carried
  to another date of the same filing when (1) share capital is the same at both dates, (2) the statement of
  changes in equity shows no treasury stock at either date and the website none at the accepted one, (3) the
  website has no figure of its own for that date (a published figure that was refused is never replaced), and
  (4) the count passes the same EPS check at that date (BBCA FY2021: 31,422,660 million / 123,275,050,000 =
  254.90, filed 255). With share capital unchanged and no treasury stock only a split changes the number of
  shares, and a split the filing does not restate fails the EPS check; the carried count is split-adjusted,
  like the prices. On the stored filings this fills 2020-12-31 and 2021-12-31 of ASGR (1,348,780,500), BBCA,
  HMSP (116,318,076,900) and PTSN (5,314,344,000); a company with treasury stock (BFIN, BBRI) or a changed
  share capital gets nothing carried.
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
  problem (`INCOMPLETE`). The mirror case is accepted too: the cash flow statement counts deposits or
  restricted funds the balance sheet shows among other current financial assets (PWON: ending cash
  5,243,521,448 thousand vs. balance-sheet cash 5,186,756,706 thousand in FY2025, an excess of 56,764,742
  thousand within 684,021,490 thousand other current financial assets).
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
  figures are multiplied by the unit, with a warning. Plausible share counts: 10^6 .. 10^13. The unit is
  judged from the profit or loss as filed, by function or by nature: MSIN (by nature, 1322000) files
  0.00000655 for Rp 6.55 in FY2024, and its EPS was stored a million times too small - and every published
  share count refused - while only the profit or loss by function was read.
- **EPS unit judged by the share capital.** Where profit / EPS gives a plausible share count both as filed and
  with the declared unit, the share capital decides (`FilingMapper.epsScaleFromShareCapital`): the EPS multiplied
  by 1, 1,000 or 1,000,000 must reproduce profit attributable to the parent / (share capital / a standard par value)
  within the rounding of the figure as filed, and exactly one multiplier may fit. MOLI's H1 2026 workbook (in
  thousands) files 0.02321 for Rp 23.21 (63,221,614 thousand / 2,724,036,580 shares, par 100); TUGU's FY2023 workbook
  0.372 for Rp 371.92 (par 50) and its FY2024 workbook 0.0002 for Rp 197.11 - in millions, with four decimals, so 150
  .. 250 rupiah. Par value inference and the column check below use the rounding of the figure as filed (0.0002 in
  millions is 150 .. 250, not 199.5 .. 200.5). When two multipliers fit (MOLI FY2025: 0.277 is Rp 0.277 at par 1 or
  Rp 277 at par 1000) nothing is changed.
- **Proforma equity.** A comparative column restated for an entity combined under common control carries
  that entity's equity on a 'Proforma equity' row between the parent's equity and the non-controlling
  interests (IMPC FY2023 in its FY2024 filing: 138,044,698,258; MSIN FY2021 in its FY2022 filing: 2,418,216
  million). Total equity includes it, the parent's equity does not, so "parent equity + non-controlling
  interests = total equity" failed by exactly that row. It is counted with the non-controlling interests
  (`non_controlling_interest`), with a `proforma_equity` warning; the full-text extraction's field
  definition says the same.
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
  The result does not depend on the upload order. MAPA split 1:10 in 2023 (FY2022 filing: EPS 412 and
  2,850,400,000 shares of Rp 100; FY2023 filing: FY2022 EPS 41, 28,504,000,000 shares of Rp 10): when the
  FY2023 filing is stored first, the FY2022 filing's own per-share figures do not replace the restated ones
  (outcome `SPLIT_ADJUSTED`). The split is recorded in `corporate_action` (`STOCK_SPLIT` 1:10, dated the day
  after the restated period, description "Restated in the filings: ..."), and after every save the figures of
  dates before it that are still on the old basis are adjusted: EPS / k and share counts x k of earlier
  periods (MAPA FY2021: 88 -> 8.8), `balance_sheet.shares_outstanding` and the share snapshots (2,850,400,000
  -> 28,504,000,000 at 2020-12-31 .. 2022-12-31; before, MAPA's market cap of 2022 was a tenth of the real
  one, split-adjusted prices x pre-split shares). The verification accepts the adjusted figures of a filing
  of before the split and says so in its notes.
- **Treasury stock and weighted shares.** `basic_shares` (share capital / par value when the capital did
  not change in the period) is left empty for a period with treasury stock at its start or end: treasury
  shares are not outstanding and their count is not in the filing. ULTJ FY2022: 11,553,520,000 issued
  shares, 1,155,352,800 of them bought back; its EPS 92 is per outstanding share (10.44 billion), so the
  issued count beside it gave EPS x shares 10.6% above the profit.
- **Lines filed as 0.** An income statement line of amount zero is never reported as unclassified (BBCA
  files 0 on the 17 insurance lines it does not use; only "Other insurance expenses" with an amount is left
  to the agent).
- **EPS checked against the share count.** A filed EPS is checked against the filing's own share
  count. (1) Across columns: each column's profit attributable to the parent / EPS must fit the share
  capital range of its period (80% of the smaller .. 105% of the larger count) at the same par value.
  NCKL's FY2024 workbook files its current EPS one more decimal place off (10.11 after its rounding unit,
  for 101.1): it fits only par 10, the prior column's 92.39 only par 100. Before, par 10 was inferred and
  631 billion shares (ten times too many) were stored. Now a workbook whose columns contradict each other
  gives no share counts, and its EPS and share counts are *rejected*: stored as NULL even over a value
  stored before (`MappedStatement.rejected`); the company's other filings agree and supply them (NCKL
  FY2025's comparative fills FY2024 with EPS 101.1 and 63,098,600,000 shares). A column whose EPS fits no par
  value as filed is judged in the rounding unit (the slip corrected within a column below) and must then fit the
  other column's par value: MOLI's FY2025 workbook files 0.277 (Rp 27.70, fits only par 1) and 0.00475 (Rp 4.75,
  fits only par 100 in thousands). Before, par 1 was inferred - 272,403,658,000 shares, 100 times too many - the
  comparative was "corrected" to 0.0475 and a 1:100 share split that never was recorded; every MOLI market cap was
  100 times too large (P/E 1,105 .. 7,626). Now the filing gives no share counts and no EPS (FY2025's EPS stays
  empty); MOLI's other filings give 2,724,036,580 shares (par 100). The full-text extraction applies the same
  rejection to the EPS the model names. (2) Within a column, with
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
- **One amount reported twice in a column (two lines of the same kind).** WIIM's H1 2026 filing carries
  5,797,003,720 on 'Finance income' and again on 'Interest income' in its H1 2025 column; profit before tax
  196,550,613,747 only reconciles with the amount counted once. Two lines of the same category with the same
  amount, neither a residual line: the one filed first is the repeat, ignored only when the statement then
  passes every check (`double_reported`).
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
- Unsupported by the standard extraction (stored by the full-text extraction, section 6; rejected with 422 only when that is switched off): balance sheet by order of liquidity (`1220000`), profit or loss
  by nature (`1312000` / `1322000`) in the General and Infrastructure taxonomies; the current /
  non-current balance sheet (`4210000`) and profit or loss by function (`4311000` / `4321000`) in the
  Financial taxonomy.

Segments are stored by name; a name a breakdown files on several lines (CEKA: "Produk Palm Kernel" as
domestic revenue 2 and export revenue 1) is qualified by its slot, "Produk Palm Kernel (domestik)" /
"(ekspor)" (before, the second line overwrote the first: CEKA FY2025 segments summed to 6.34 instead of
9.73 trillion). Names filed once keep their name; a name that still repeats blocks the breakdown.

A line the breakdown's own total does not include is not counted: when the reported total equals the
revenue of the income statement and is exactly the sum of the other lines, that one line is left out with
a warning (`segment_line`). MIKA's comparative columns carry an unnamed "Service revenue 5" outside the
filed subtotal and total - 602,309,854,653 in FY2024 (the amount of the fourth service line again),
261,182,623,893 in H1 2025. Nothing is dropped when the lines add up as filed, or when no single line
explains the difference.

Revenue segments come only from the breakdown sheets `1617000` (by type) and `1618000` (by
source). Some issuers leave both blank and disclose segments only in the PDF notes (e.g. INDF:
the sheets contain the template labels without names or amounts); the upload then stores no
segments for the filing, by design, and every other table as usual.

Prices are not part of a filing; `market_snapshot` and `valuation_snapshot` need `price_daily`
rows. **A stored filing queues the price ingestion of its company** (`PricesAfterUpload`, called by
`FinancialStatementQueue` when an upload ends `SUCCEEDED` or `INCOMPLETE`): the job is recorded for the
user who uploaded the file, loads the whole history of a company without prices and only the missing days
otherwise, and ends with the valuation refresh of the company
([PRICE_INGESTION_DOCS.md](PRICE_INGESTION_DOCS.md)). Nothing is queued when the stored prices already
reach the last completed trading day, and a company with an active price job keeps that job, so five
filings of one company uploaded together fetch its prices once. A failed upload queues nothing, and a
failure to queue never changes the upload's outcome. Before, the prices had to be requested by hand
(`POST /api/v1/prices/ingestions?exchange=IDX&ticker=...`): DSNG, ELSA, ICBP, JPFA and SIDO had five
years of statements and share counts and an empty valuation history. Switch off with
`neracalab.ingestion.prices-after-upload: false`.

## 5. Tests

| Test                             | What it proves                                                                                                                                                                                                                                       |
|----------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `FilingMapperHrtaTest` (unit)    | the six HRTA filings in `data/IDX_XBRL/HRTA/xlsx` map to exactly the values validated for `V1.0.4__data_HRTA_financials.sql` (`src/test/resources/ingestion/hrta_expected.json`): every field of every column, segments, share counts, par value, audit flags |
| `FilingMapperLegacyTemplateTest` (unit) | the pre-2023 template: INDF FY2022 maps to the same FY2022 figures as the comparative column of the INDF FY2023 filing (except the restated operating / investing cash flow), incl. share capital and depreciation from the `1 CurrentYear` sheets; the revenue breakdowns of GGRM, HRTA and INDY FY2022 reconcile to revenue in both columns, and HRTA / INDY FY2022 equal their FY2023 filings' comparatives |
| `FilingMapperInfrastructureTest` (unit) | the Infrastructure Industry taxonomy: all five SMDR filings map without errors or unclassified lines; capex includes the infrastructure labels (FY2025: -81,650,677); every comparative column equals the previous filing's current column except SMDR's own reclassification of 161,196 from "Other income" to "Other gains (losses)" in FY2024 operating income; no share count is guessed |
| `ModelSpeedSettingsTest` (unit, local HTTP server) | the real Spring AI client sends `"reasoning": {"enabled": false}` (and nothing when reasoning is on); a call slower than the per-call timeout is retried at once and a fast answer used; a call that always stalls gives up after the retries in under 3 s |
| `DeterministicFinisherTest` (Docker Postgres, rolled back) | a model failing every call: HRTA H1 2026 is still stored, refreshed and verified complete by the deterministic finish; display names from legal names; segments keep the filing's names and types and reuse stored English names |
| `JobDeadlineTest` (unit) | the time limit inside the agent: a slow model call stops at it, no retry pause past it, no model call and no tool after it, the planner's fallback does not swallow it |
| `UploadJobTimeoutTest` (Docker Postgres) | an upload job over a 3 s limit (mock agent working 4 s) ends `FAILED` with "Stopped after the 3 seconds limit on HRTA 2026 H1" within seconds of the limit |
| `FilingMapperEpsTest` (unit) | NCKL FY2024: contradicting EPS columns give no share counts, EPS and share counts rejected (cleared), FY2025's comparative gives 101.1 at par 100; all MYOR, NCKL and PTSN filings map without errors |
| `FilingMapperCekaTest` (unit) | all five CEKA filings without errors; "Produk Palm Kernel" domestic and export kept as two segments (FY2025 total = revenue 9,733,304,188,977); a repeated name blocks the breakdown; H1 2026 financing 0 without lines or total; H1 2025 interest also filed as "Other expenses" counted once; a name on two lines of the same kind is one segment, added up |
| `IngestionAgentRetryTest` (unit) | a model call is retried after a read timeout (`OpenAIInvalidDataException` with an `InterruptedIOException`), gives up after `model-retries`, never retries a permanent error |
| `IngestionAgentModelTest` (unit) | Spring AI fills a missing model with `gpt-5-mini`; the agent's options name the configured model and the model a response reports is recorded in `metrics.models` |
| `ConfiguredChatModel` (test helper) | the model the agent tests use, never written in Java: `spring.ai.openai.chat.model` from `application.yaml` (`${OPENAI_MODEL:<default>}`), with `OPENAI_MODEL` from the environment, else `backend/.env`, else the `application.yaml` default (the application's own precedence) |
| `BngaShareSeedTest`              | the BNGA share-count script fills the five audited year-end counts (2021 .. 2025), outstanding + treasury = issued at every date, runs idempotently and never replaces a count already stored (rolled back) |
| `ShareSplitTest`                 | a split restated by a comparative adjusts the period and the earlier periods on the old basis once, in either upload order; a split-adjusted EPS keeps its precision (USD 0.0054 / 5 = 0.00108, rupiah figures as before) |
| `SmdrShareSeedTest`              | the SMDR share-count script fills 16,375,600,000 at eight dates from 2020-12-31 and the 2023-01-31 1:5 split, runs idempotently and never replaces a count already stored (rolled back) |
| `FilingMapperFinancialTest` (unit) | the Financial and Sharia Industry taxonomy: all five BNGA filings and all four BTPN filings map without errors, warnings or unclassified lines; all five BMRI filings map without errors; BMRI FY2025's FY2024 column ignores "Claim expenses" shown for information (operating income 76,059,595 million, revenue 186,767,596), never against an agent classification; FY2025 bank income statement (revenue 30,631,359 million, operating income 8,782,085), balance sheet (cash equivalents from the cash flow, debt 8,140,477, current items NULL) and cash flow (capex -820,540, net securities issued in debt issued); the 2026 H1 prior year end; every comparative column equals the previous filing's current column; no share count is guessed |
| `StatementGapFillTest` (Docker Postgres, rolled back) | a comparative fills an empty revenue (`FILLED_GAPS`) but not a stored gross profit, nothing left to fill is `KEPT_EXISTING`, and the period's own filing does not wipe the filled revenue |
| `ShareSplitTest` (Docker Postgres, rolled back) | a 2:1 split restated by a comparative (`SPLIT_ADJUSTED`): the period and an earlier period on the old basis adjusted, a period on another basis not, nothing adjusted twice; a restated (non-split) EPS never gets the comparative's share count; the other upload order (comparative first, then the period's own filing: MAPA 1:10) ends the same, with balance sheet shares and share snapshots adjusted, the split recorded once and the own filing uploaded again changing nothing; a restatement that is no split is replaced by the own filing; split factors |
| `FilingMapperTinsTest` (unit) | the TINS filings: FY2025's comparative counts its residual profit or loss lines once and its cash flow's residual investing line not at all, the current year still counts every line, every statement of FY2025 is storable; FY2024 as published has no current-year figures; FY2023's loss per share is scaled and gets its sign (-60) |
| `FullTextExtractorTest` (unit, scripted model) | TINS FY2024: nothing is invented for the empty 2024 column, the model decides all three statements of the comparative 2023 and every amount is the cell x the rounding unit, the rule-based statement is passed as a reference only; a mapping that contradicts itself is shown to the model, which corrects it; its last answer is stored even when it does not add up, and that is reported; amounts and unknown rows in the answer are never used; a field made up of other rows in one column (`overrides`); the model may adopt a statement that passed every check, never one that failed; BFIN (Financing Industry): every sheet of the file is in the text and the model's rows are stored, nothing of the rules is shown; a sign that contradicts the field is pointed out once with the rows concerned; MSIN FY2025 (by nature): profit from operations left empty is asked for again and stored from the rows that compute it, and is an open item of the upload when the last answer leaves it empty too |
| `ProviderIgnoreTest` (unit) | the excluded providers are sent with every request (`provider.ignore`); without exclusions nothing is sent |
| `FilingMapperBbriTest` (unit) | BBRI: the comparative cash flows of FY2025 and H1 2026 are storable (a line outside the filed section total), the current year's check is unchanged; the two unclassified insurance lines of H1 2025 are ignored because the filing's totals reconcile exactly without them, and every statement of the H1 2026 filing is storable; BBCA's insurance line, which its totals include, is not ignored |
| `IngestionServiceFallbackTest` (unit, scripted stages) | when the full-text extraction takes over: unsupported workbook (agent not started), INCOMPLETE, past the 3 minutes, failed; never after a clean result; its own failure leaves what the standard extraction reached, its own timeout fails the upload; switched off; the upload endpoint accepts an unsupported workbook and refuses an unreadable one |
| `FullTextExtractionDatabaseTest` (Docker Postgres, scripted model, rolled back) | TINS FY2024 is stored from its text with the standard steps: the comparative 2023 FY in the database, nothing for 2024, the verification says why; the error log keeps what went wrong and starts unfixed |
| `FilingMapperMikaTest` (unit) | MIKA: the comparative breakdowns of FY2025 and H1 2026 reconcile once the one line outside the filed total is left out (warning `segment_line`); the current columns are stored as filed |
| `FilingMapperAuditedTickersTest` (unit) | ULTJ: no weighted shares for FY2022 (treasury stock), kept for years without; MAPA: FY2022 as EPS 412 / 2,850,400,000 shares in its own filing and 41 / 28,504,000,000 in the FY2023 comparative; BBCA: lines filed as 0 need no classification, "Other insurance expenses" with an amount is left to the agent and reconciles as an operating expense |
| `FilingMapperAsgrTest` (unit) | all five ASGR filings map without errors; the H1 2025 loss reported twice in the H1 2026 filing is counted once (profit before tax 139,690, operating income 116,372), not in the current column, never against an agent classification; the FY2023 workbook's full amounts under an "In Million" label are read as full amounts (total assets 2,682,813 million, as in the FY2022 workbook); SIMP: FY2023 full amounts without revenue (gross profit 3,358,216 million, revenue from the FY2024 comparative), H1 2026 EPS filed in millions (56.35), every SIMP filing without errors |
| `FilingMapperWebSharesTest` (unit) | web share counts against the real filings: BNGA FY2025 gets the three audited counts, H1 2026 rejects the inconsistent 2026-06-30 point, counts five times too high are rejected, SMDR's FY2022 filing rejects the split-adjusted count and its FY2025 filing accepts it, a filing with its own counts is kept, NSSS (profit or loss by nature) gets the count that fits its EPS and refuses one that does not; BBCA FY2022: the count accepted at 2022-12-31 is carried to 2021-12-31 and 2020-12-31 (share capital unchanged, no treasury stock, the FY2021 EPS confirms it), never to a date the website publishes itself, never with treasury stock (BFIN), never where the EPS contradicts it |
| `FilingMapperErrorLogTest` (unit) | the filings behind the error rows of 2026-10-11: IMPC FY2024's comparative with 'Proforma equity' (non-controlling 352,479,399,434 = 214,434,701,176 + 138,044,698,258, total 2,625,694,521,530, no failed check), WIIM H1 2026's comparative with 5,797,003,720 on two lines (counted once, profit before tax 196,550,613,747), MSIN's EPS unit per filing (1 for FY2022, 1,000,000 from FY2023) and a published count that fits it, by nature = decided by the model by design, and every filing of AUTO, DKFT, DMAS, IMPC, PTBA, RAJA, SMSM and WIIM without a failed check or an unclassified line |
| `FilingMapperTuguMoliCnmaTest` (unit) | the filings behind the error rows of 2026-10-14: TUGU read in the Insurance Industry taxonomy with 3,555,575,600 shares at par 50 (EPS in millions in FY2024, an unrestated split in FY2023), MOLI's EPS in thousands (H1 2026) and its contradicting FY2025 columns (no par 1), CNMA's depreciation with its balance sheet by order of liquidity |
| `FilingMapperTotlMidiTest` (unit) | the filings behind the error rows of 2026-10-13: TOTL's breakdowns with "Jasa Konstruksi" on several lines of one kind are one segment equal to the line its FY2024 filing shows (lines of different kinds stay apart); MIDI's H1 2025 cash roll-forward holds without the deconsolidation line its ending cash excludes |
| `DerivedDataRefreshTest` (Docker Postgres) | the refresh of the derived data: a second run changes nothing and takes seconds, the market snapshots equal the lookup per trading day they replaced, six requests at once cost one or two runs |
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

## 6. Full-text extraction (fallback) and the error log

The standard extraction above is strict: a statement that fails a validation is not saved, and a workbook
whose statement sheets it cannot map is not stored at all. When it does not get there, the **full-text
extraction** takes over: the model (DeepSeek) gets the text of the whole workbook and **decides every value
that is still to be stored** - the rules of the Java code no longer decide anything. Every such upload is
written to the table `ingestion_error` so the cause can be fixed in the rule-based code afterwards.

### When it takes over

| Trigger (`ingestion_error.error_type`) | When                                                                                         |
|----------------------------------------|----------------------------------------------------------------------------------------------|
| `UNSUPPORTED_WORKBOOK`                 | the upload is a readable IDX XBRL workbook, but the mapper reports template problems, e.g. `No balance sheet data (1210000)`, or it is of a taxonomy the rules do not map (Financing Industry, 8xxxxxx: straight to the model by design, and not logged when it succeeds). Before, this was refused with HTTP 422; now it is accepted (202) and the agent is not started |
| `INCOMPLETE`                           | the standard extraction ended with open items, e.g. `saveStatements(PRIOR_PERIOD) is blocked: income_statement failed checks [...]` |
| `SLOW_EXTRACTION`                      | the standard extraction ran longer than `neracalab.ingestion.fallback.after` (3 minutes)     |
| `EXTRACTION_FAILED`                    | the standard extraction failed (the AI provider kept failing, an unexpected error)           |

A file that is not an .xlsx or not an IDX XBRL workbook at all (no sheet 1000000, a truncated download) is
still refused with **422** - there is nothing to extract - and logged as `UPLOAD_REJECTED`.

### What it does (`FullTextExtractor`, `WorkbookText`)

**The model decides every value that is stored.** In this stage no rule of the Java code decides or
overrules a value: the code gives the model the workbook, reads the amounts of the rows the model names, and
reports what does not add up.

1. **Everything that is not stored yet.** Every column the standard extraction has not saved is the
   model's to decide - all its statements, not only the one that failed. Columns the standard extraction
   saved and verified are not touched. The model is **always asked**: no rule decides beforehand that a
   statement has nothing to extract. Only after its answer, a column it found nothing for because the file
   has nothing in it is reported (the current period: `INCOMPLETE` with the reason, see TINS FY2024 below).
2. **The text of the whole workbook goes to the model - every sheet of the file**, whatever its taxonomy
   (`IdxWorkbook.allSheets`; only the template's technical sheets Context, InlineXBRL, hidden and Token are
   left out): the general information, every row of the balance sheet, profit or loss and cash flow that
   carries an amount, the equity statement and the notes sheets (up to `max-chars`). The statements are
   recognised by the second digit of the sheet code (x2xxxxx balance sheet, x3xxxxx profit or loss, x5xxxxx
   cash flow; x = 1 General, 2 Property, 3 Infrastructure, 4 Financial, 8 Financing, ...), so a taxonomy the
   rules do not know is read like any other. Each statement row has a reference:

   ```text
   ## Sheet 1311000: [1311000] Statement of profit or loss and other comprehensive income ...
   columns: c1=CurrentYearDuration, c2=PriorYearDuration
   1311000!R6 | Sales and revenue | c1=11552733 | c2=10856422
   1311000!R7 | Cost of sales and revenue | c1=8788780 | c2=8106640
   ```
   With it the model gets, **as a reference only**, what the rule-based extraction had read for these
   statements, which of its checks failed and which lines it could not classify ("it may be wrong or
   incomplete, you decide").
3. **The model answers which rows make up every database field**, with references and signs:

   ```json
   {"income_statement": {"revenue": [{"row": "1311000!R6", "sign": 1}],
                         "income_tax": [{"row": "1311000!R38", "sign": -1}],
                         "sga_expense": [{"row": "1311000!R9", "sign": 1}, {"row": "1311000!R10", "sign": 1}]},
    "overrides": {"PRIOR_PERIOD": {"income_statement": {"operating_expenses": [{"row": "1311000!R9", "sign": 1}]}}},
    "adopt": ["CURRENT_PERIOD balance_sheet"]}
   ```
   - The amounts are read from the workbook's own cells and multiplied by its rounding unit. The model
     decides what a value is made of; it does not type the digits, so a stored figure always exists in the
     workbook and cannot be mistyped. An amount written into the answer is ignored; a reference that is not
     in the text is dropped and reported.
   - `overrides`: a field that is made up of other rows in one column than in the others (a line that
     counts in one period and repeats another line in the comparative).
   - `adopt`: a statement the rule-based extraction read without a failed check may be adopted by the model
     as it is, instead of mapping it again. A statement that failed cannot be adopted.
4. **The model sees where its own mapping contradicts itself** - total assets != liabilities (+ temporary
   syirkah funds) + equity, equity != parent + non-controlling interests, gross profit != revenue - cost of
   revenue, a required total without rows (profit from operations, profit before tax and profit of the
   period; total assets, liabilities and equity; the three cash flow totals) - and may answer again, up to
   `rounds` (3) times ("if the filing
   itself is inconsistent, keep your mapping and say so"). **Its last answer is what is stored.** A
   contradiction that remains does not drop the statement: it is saved as mapped (with a warning), the
   upload ends `INCOMPLETE` and the contradiction is in `ingestion_error`. Three slips are pointed out to
   the model once, with the rows concerned, and its next answer stands (they are hints, never a problem of
   the upload): an outflow that comes out positive (dividends, repayments, capital expenditure, buybacks) or
   a positive amount that comes out negative; an asset row among the lease liabilities; retained earnings
   without the appropriated part.
   **A row that cannot be named** - one that is not in the text, or a row of a notes sheet (only the rows of
   the balance sheet, profit or loss and cash flow sheets can be) - **leaves its whole field empty**: the sum
   of the field's other rows is not what the model meant. The model is told and asked again; a field still
   broken in its last answer is an open item (`INCOMPLETE`). Before, the row was dropped and the rest stored:
   NSSS FY2025 on Haiku took the cost of revenue from a notes sheet (`1670000!R11`), so "revenue less
   nothing" - 2,013,801 million, the revenue - was stored as gross profit in an upload that ended
   `SUCCEEDED`.
   **A profit or loss by nature** (General taxonomies, sheet 1312000 / 1322000, e.g. NSSS: expenses by type,
   no "Cost of sales and revenue" and no "Total gross profit" row) has no `cost_of_revenue` and no
   `gross_profit`: the model is told to leave both empty rather than build them from expense rows, and a
   value an earlier upload stored for them is cleared (`FilingMapper.incomeByNature()`), not kept as a gap.
5. **Saved by the standard steps.** The model's statements replace the mapper's in the session and are
   saved by the same tools as every upload (`DeterministicFinisher`): the company, the statements (the
   current period replaces, comparatives only fill gaps), revenue segments, share counts, the derived data.
   The verification is the database read-back (stored = what the model decided, the balance sheet balances,
   segments sum to revenue), so `SUCCEEDED` still means verified. The job stage says which columns the
   model decided (`...; full-text extraction of PRIOR_PERIOD`), its result carries `fallback` (trigger,
   reason, columns, statements, problems) and the verification notes name the columns.

Fields the model decides: the income statement, balance sheet and cash flow columns of section 4,
including `depreciation` and `amortization` where the statements show them as rows of their own (else they
come from the roll-forward notes, as usual). Not rows of the statements, and therefore not the model's:
`rd_expense`, and the share counts, which come from the share capital of the equity statement or - when
that gives none - from Yahoo Finance checked against the filing's EPS, as for every upload. `ebit` /
`ebitda` are derived from the model's values. An EPS gets the sign of the profit attributable to the parent.

For a workbook of a taxonomy the rules do not map, nothing of the rule-based extraction is shown to the
model and nothing can be adopted.

Measured with DeepSeek V4 Flash, the model of the full-text extraction until it got its own
(`neracalab.ingestion.fallback.model`, Claude Haiku 5.5; the NSSS row below):

| Workbook | What the model decided | Calls, time | Result |
|----------|------------------------|-------------|--------|
| TINS FY2024 (no figures for 2024) | balance sheet and profit or loss of the comparative 2023, 55 fields, nothing to lean on | 1 call, 9 s | every amount equals the FY2023 filing's own figure except the two the FY2024 workbook restates |
| BBRI H1 2026, all six statements (before the mapper could read its comparative) | adopted the four that passed every check; mapped the comparative profit or loss and cash flow | 1 call, 17 s | profit from operation 35,004,968 and profit before tax 34,744,196 as filed; it counted two insurance lines in revenue and cost of revenue that the filing's totals exclude (the mapper now stores 123,348,477 / 33,153,552) |
| BBRI FY2025, all six statements | adopted five, mapped the comparative cash flow | 1 call, 20 s (after two provider stream resets of 60 s each) | every value as the rule-based extraction |
| BFIN, five filings (Financing Industry, nothing of the rules) | all 28 statements | 1-2 calls, 10-47 s each | all 93 totals (assets, liabilities, equity, profit before tax, profit, EPS, the cash flow totals, cash) equal the workbook's cells |
| NSSS, five filings (profit or loss by nature, 1312000), Claude Haiku 5.5 | all statements of every column; `cost_of_revenue` and `gross_profit` left empty | 1-3 calls, 40-136 s each, USD 0.003-0.008 per filing | all five `SUCCEEDED` and verified; on DeepSeek three of them ended `INCOMPLETE` (gross profit != revenue - cost of revenue, the two built from expense rows by nature) |

**Share counts of a filing by nature.** A filing without usable share counts gets Yahoo Finance's, checked
against the filing's own EPS (section 4). That check read the profit attributable to the parent and the EPS
from the profit or loss by function only, so every count of NSSS was refused ("no profit or loss period of
this filing opens or closes on that date") and the company had no market cap and no valuation. It now reads
them from the profit or loss as filed, by function or by nature (the totals carry the same labels): NSSS
FY2025, 647,824 million / 23,801,568,645 shares = 27.2177, the EPS filed. A count that does not fit the EPS is
still refused.

**A filing by nature is the model's to decide.** NSSS (1312000) and MSIN (1322000) present profit or loss
by nature, which the rule-based extraction does not map: such a workbook goes straight to the full-text
extraction, and when that stores and verifies it nothing went wrong - it is no longer written to the error
log (`FilingMapper.decidedByModel`: a filing by nature, the Financing or Insurance Industry taxonomy, or a General
Industry balance sheet by order of liquidity (CNMA, 1220000), with nothing else wrong). An upload of it that ends `INCOMPLETE` or `FAILED` is still logged. `cost_of_revenue` and
`gross_profit` are never stored for it, whatever the model answers (MSIN: 'Program and broadcasting
expenses' named as cost of revenue, no gross profit beside it) - they are cleared before the checks.
MSIN, five filings uploaded together, Claude Haiku 5.5: one call each, 21-40 s, USD 0.002-0.003, all
`SUCCEEDED`; share counts 60,676,178,205 from Yahoo Finance accepted for 2023-12-31 .. 2025-12-31 (397,637
million / count = 6.5534, the FY2024 EPS 6.55).

**Debt where there are no borrowings.** A non-financial company whose balance sheet has no borrowing row gets 0
for `short_term_debt`, `long_term_debt` and `lease_liabilities` when the model names no rows for them, as the
rule-based extraction stores them (the sum of no rows). Left empty, total debt, net debt and EV were unknown: CNMA
(no bank loans since FY2023) had no EV, EV / EBITDA or EV / operating profit in any valuation. A bank, financing
company or insurer keeps them empty (no EV). **Depreciation** of a balance sheet the rules do not map (by order of
liquidity) comes from the PP&E note as for every filing: the balance sheet as filed tells whether there are
right-of-use assets without a note of their own (CNMA: they are in the PP&E note; FY2025 791,951 million).
**Operating income** includes the template's 'Other income' and 'Other expenses' rows, as in the rules (CNMA's
operating income had them in some years and not in others), unless the filing's own profit before tax leaves a row
out.

**An insurer's revenue and costs give its profit before tax.** For the Insurance Industry taxonomy, revenue - cost of
revenue - operating expenses - finance costs must equal profit before tax; where it does not, the model is shown
the difference and, when exactly one row (or pair of rows) of those fields closes it with its sign reversed, that
row (`FullTextExtractor.signHint`). TUGU deducts 'Decrease (increase) in premium income ceded to reinsurancer' and
'Increase (decrease) in insurance liabilities ceded to reinsurers' as filed, although the labels read like rows
signed already; only that reading reconciles every column of its five filings (FY2022: revenue 3,219,450,604 -
cost 1,672,348,143 - G&A 1,029,905,247 = 517,197,214 thousand).

**Providers.** The model calls of the ingestion never go to an OpenRouter provider listed in
`neracalab.ingestion.provider-ignore` (default `OpenInference`, env `INGESTION_PROVIDER_IGNORE`): its DeepSeek
endpoint loops on long answers until OpenRouter resets the stream after 60 seconds (`stream was reset:
CANCEL`). Before, a call routed to it cost a minute and a retry - the two stream resets in the table above,
and likely many of the standard extractions that ran into their time limit.

### Timing

The standard extraction gets `fallback.after` (3 minutes) of the job, then the full-text extraction its own
`fallback.timeout` (5 minutes: one answer takes 10-30 seconds, but a provider that drops the stream after 60
seconds is retried twice); an upload that passes both is stopped and `FAILED`. Without the fallback
(`enabled: false`) the standard extraction has the whole `job-timeout` (5 minutes), as before.

| Property (`neracalab.ingestion.fallback.*`) | Default | Meaning                                                    |
|---------------------------------------------|---------|------------------------------------------------------------|
| `enabled` (`INGESTION_FALLBACK_ENABLED`)    | `true`  | `false`: an upload ends where the standard extraction ends; an unsupported workbook is refused with 422 again |
| `model` (`INGESTION_FALLBACK_MODEL`)        | `anthropic/claude-haiku-5.5` | its own model (OpenRouter id), stronger than the standard extraction's DeepSeek flash at about the same cost per upload (one long answer, up to `rounds` times); empty = the standard extraction's model. Recorded as `details.fullTextExtraction.model` in `ingestion_error` |
| `after` (`INGESTION_FALLBACK_AFTER`)        | `3m`    | longest the standard extraction runs before the full-text extraction takes over |
| `timeout` (`INGESTION_FALLBACK_TIMEOUT`)    | `5m`    | longest the full-text extraction runs                      |
| `model-call-timeout`                        | `150s`  | one of its model calls (one long JSON answer)              |
| `max-chars`                                 | `120000`| most workbook text sent to the model; the statement sheets always fit, notes sheets are left out first |
| `rounds`                                    | `3`     | answers of the model: the first, then its own corrections; the last one is stored |

### The error log `ingestion_error`

One row per upload that did not go through cleanly: refused at the upload, taken over by the full-text
extraction (whatever the end), `INCOMPLETE` or `FAILED`. A clean upload writes nothing. The application only
inserts; **Claude Code reads the open rows, fixes the cause in the ingestion code, and marks the row.**

```sql
-- what is open
SELECT error_id, occurred_at, file_name, ticker, period, error_type, message, fallback_used, final_status
FROM ingestion_error WHERE NOT fixed_by_claude_code ORDER BY occurred_at;

-- everything about one error (failed checks, open items, what the full-text extraction did)
SELECT message, final_message, jsonb_pretty(details) FROM ingestion_error WHERE error_id = 26;

-- after the fix
UPDATE ingestion_error
SET fixed_by_claude_code = TRUE, fixed_at = now(), fix_note = 'what was changed, which test covers it'
WHERE error_id = 26;
```

| Column                  | Meaning                                                                                     |
|-------------------------|---------------------------------------------------------------------------------------------|
| `error_id`, `occurred_at` | key, when                                                                                 |
| `job_id`, `file_id`, `file_name` | the job (`NULL` for an upload refused with 422) and the stored workbook; the file can be downloaded with `GET /api/v1/ingestions/{job_id}/file` |
| `exchange`, `ticker`, `period` | the filing, e.g. `IDX`, `TINS`, `2025 FY`                                            |
| `error_type`            | `UPLOAD_REJECTED`, `UNSUPPORTED_WORKBOOK`, `INCOMPLETE`, `SLOW_EXTRACTION`, `EXTRACTION_FAILED` |
| `message`               | what went wrong in the standard extraction: the failed checks, the open items, the error    |
| `details` (JSONB)       | `pending`, `problems`, `error`, `notes`, `fullTextExtraction` (trigger, reason, `model`, columns, statements, problems, steps, and `error`: why it stopped when it did not finish) and `failedModelCalls`: every model call of the job that failed - a timeout, a stream reset, a provider error - with `stage` (`PLAN`, `EXECUTE`, `REVIEW`, `FULL_TEXT`), `model`, `error` (what the provider said), `durationMs`, `at`; absent when no call failed |
| `fallback_used`, `fallback_columns` | whether the full-text extraction took over, and for which columns               |
| `final_status`, `final_message` | how the upload ended: `SUCCEEDED`, `INCOMPLETE`, `FAILED`, or `REJECTED` (422)       |
| `fixed_by_claude_code`  | `FALSE` until Claude Code has fixed the cause (default `FALSE`)                             |
| `fixed_at`, `fix_note`  | when, and what was changed - or why it cannot be fixed in code                              |

### The uploads of 2026-10-14 (errors 346 - 355): an insurer, an EPS in three units, a balance sheet by liquidity

Fifteen rows (346 - 355, and 367 - 371 from the uploads of the fix itself), three companies, all fixed in code and
uploaded again on 2026-10-14.

| Cause | Rows | Fix |
|---|---|---|
| **An insurer's taxonomy the reader did not know.** TUGU files in the Insurance Industry taxonomy (6xxxxxx). No sheet was read - not even the share capital - so TUGU had no share counts and no valuation; the model mapped it as a General Industry company: the tabarru' funds (43,696,710 thousand at 2026-06-30) left out of the balance sheet (347: assets != liabilities + equity) or put in its liabilities, an operating income of its own (H1 2025: -2,238,844,699 thousand), the EPS as filed in thousands (FY2023 0.372) and millions (FY2024 0.0002). The 2:1 split of 2023-05-24 (par Rp 100 -> Rp 50) is restated by no filing: the FY2023 filing's comparative EPS is from before it. | 346 - 350; 367, 368 (the model added 'Decrease (increase) in premium income ceded to reinsurancer' instead of deducting it) | `IdxTaxonomy.INSURANCE`, decided by the model by design, with an insurer's instructions; revenue - costs must give profit before tax, the row whose reversed sign closes the gap pointed out; EPS unit from the share capital (par 50, 3,555,575,600 shares); the split recorded by `V1.0.22__data_TUGU_shares.sql`. Statements deleted and the five filings uploaded again: all `SUCCEEDED`, every period reconciles, EPS 89 / 97.5 / 370 / 200 / 200 / 135 (FY2021 .. H1 2026, split-adjusted), P/E 5.66 and P/B 0.57 at Rp 1,465 (2026-10-13). |
| **A balance sheet by order of liquidity, an incomplete valuation.** CNMA's General Industry balance sheet is by order of liquidity (1220000); the model stored all four filings, but no debt rows meant NULL debt (no EV), no right-of-use row meant no depreciation (no EBITDA), and its operating income had 'Other income' in some years only. | 351 - 354 | Debt 0 for a non-financial balance sheet without borrowing rows; the balance sheet as filed for depreciation; 'Other income' / 'Other expenses' operating as in the rules; such a workbook is decided by the model by design. Uploaded again: all `SUCCEEDED`, no row; FY2025 EV / EBITDA 5.36. |
| **A column that contradicts itself (closed without a rule), and an EPS in three units.** MOLI's H1 2025 column reconciles to its profit before tax only without its 'Other income' line of -1,985,667 thousand, for no reason the filing gives; the full-text extraction stores it with the filed totals. MOLI files its EPS in rupiah, hundredths and thousandths of the right figure from filing to filing: par 1 was inferred from FY2025 (272,403,658,000 shares) and a 1:100 split that never was recorded - every market cap 100 times too large. | 355; 369 - 371 (the same column in the uploads of the fix) | EPS unit from the share capital (H1 2026: thousands, par 100); a column fitting no par as filed judged in the rounding unit, so FY2025's columns contradict each other and give no share counts and no EPS. Statements, share counts and the false split deleted and the five filings uploaded again: 2,724,036,580 shares from 2021-12-31, no split, P/E 9.13 at Rp 398. |

Tests: `FilingMapperTuguMoliCnmaTest` (the insurance taxonomy, TUGU's share counts at par 50 and its EPS in millions,
FY2023's unrestated split, MOLI's EPS units in FY2025, H1 2026 and FY2024, CNMA's depreciation),
`FullTextExtractorTest` (an insurer's tabarru' funds and share capital, its revenue and costs against profit before
tax with the sign hint, a contradicting filing's EPS not stored, no debt without borrowing rows),
`IdxWorkbookReaderTest` (the 6xxxxxx sheets).

### The uploads of 2026-10-13 (errors 321 - 323): a memo line in the cash roll-forward, one name on several lines

Three rows from the filings of MIDI and TOTL; nothing is wrong with the workbooks. Fixed in the rule-based
extraction, both filings uploaded again: `SUCCEEDED` on the standard extraction, no row.

| Cause | Rows | Fix |
|---|---|---|
| **The cash of a deconsolidated subsidiary listed, not counted.** MIDI's H1 2026 filing shows, in its H1 2025 column, 'Cash and cash equivalent of deconsolidated subsidiaries' -79,630 million between beginning and ending cash, but its ending cash is beginning + net increase exactly (298,489 + 238,397 = 536,886; the proceeds from the disposal, 200,455, are in investing). The roll-forward check counted the line and failed by 79,630; the column went to the full-text extraction. | 321 (`SUCCEEDED`, rescued) | The line is a step of the roll-forward unless the filing's own figures prove otherwise: when the roll-forward holds exactly without it and not with it, it is not counted (warning `cash_rollforward`). A filing that does count the line is checked as before. The stored H1 2025 cash flow statement (the model's) equals what the rules now read. |
| **One segment filed on several lines.** TOTL names service revenue 1 .. 4 "Jasa Konstruksi" (H1 2026: 331,002,951 + 306,031,443 + 451,135 + 1,410,223,936 thousand; H1 2025 likewise, plus an unnamed "Service revenue 5" its total leaves out) and domestic revenue 1 .. 5 in FY2023. A name that repeats within one kind of slot was refused ("Segment names repeat within the breakdown even with their slots"), so both breakdowns of both filings stayed open - although they add up to the filed total and the revenue. The full-text extraction cannot help: a breakdown is not one of its statements. | 322, 323 (`INCOMPLETE`) | Lines with the same name, the same kind of slot and the same type are one segment, added up (warning `segment_names`); the reconciliation with the breakdown's total and the revenue still decides. A name on lines of different kinds stays two segments with their slot as qualifier (CEKA). Proof on the filings: the five FY2023 lines sum to 3,006,816,008, the single "Jasa Konstruksi" line TOTL's FY2024 filing shows for FY2023. Stored: "Jasa Konstruksi" + "Lainnya" for FY2022, FY2023, H1 2025 and H1 2026, each equal to the revenue. |

Row 243 (`FinancialStatement-2024-Tahunan-MSTI.xlsx`, 2026-10-11) is closed without a change: the uploaded
file was not an .xlsx workbook, was refused with 422 and is not stored (below). FY2024 of MSTI is stored from
the comparative column of its FY2025 filing; uploading a good copy of the FY2024 filing replaces it.

Tests: `FilingMapperTotlMidiTest` (the TOTL breakdowns of H1 2026, H1 2025, FY2023 and FY2022 on the real
filings, the sum against the FY2024 filing, lines of different kinds kept apart; MIDI's H1 2025 roll-forward
and its untouched current column), `FilingMapperCekaTest` (a name on two lines of the same kind is added up).

### The BFIN uploads (errors 64 - 68): "no figures" for a complete workbook

All five BFI Finance filings ended `INCOMPLETE` within a second: `CURRENT_PERIOD ...: the workbook has no
figures for this period ... The file itself lacks the data`. That message was **wrong**. The workbooks are
complete; BFI Finance files in the **Financing Industry taxonomy**, whose sheets are numbered 8220000
(balance sheet), 8322000 (profit or loss), 8510000 (cash flow). Two faults in the code, not in the files:

- The workbook reader kept only the sheets whose numbers the rule-based extraction knows. So the "text of
  the whole workbook" given to the full-text extraction was the general information sheet alone.
- The full-text extraction then decided in Java that the statements were empty and never asked the model.

Fixed: the reader keeps every sheet of the file for the full-text extraction; statements are recognised in
any taxonomy; the model is always asked. The Financing Industry taxonomy is read under the usual sheet
roles (so the share capital, the EPS unit and the notes are available) but its line items are not mapped by
rules: such a workbook is accepted and goes **straight to the model** (`decidedByModel`), without the
3 minutes of the standard extraction, and a successful upload of it is not logged as an error.
Re-uploaded: all five `SUCCEEDED` in 24 - 78 seconds, `full-text extraction of CURRENT_PERIOD,
PRIOR_PERIOD`. One thing the filing itself lacks: the FY2023 workbook has no "Bonds payable" row (its
liabilities do not add up to its total), so 2023's debt is the borrowings alone.

### The uploads of 2026-10-11 (errors 120 - 243): thirty-five rows, five causes

Fifty filings of ten new companies were uploaded, five of a company at a time. Thirty-four rows of the error
log came from five causes; every file was uploaded again after the fixes and none left a row.

| Cause | Rows | Fix |
|---|---|---|
| **The refresh of the derived data took 20 s and more.** The model calls took 1-10 s each; the three minutes went into the last step. `refreshDerivedData` recalculated `market_snapshot` for every company with a lookup in `v_key_metrics` per price row (157,000 of them) and upserted every row, one run at a time, each caller holding its company's write lock: 43 s on average and up to 138 s that day, with the saves of sibling uploads waiting up to 60 s behind it. | 234, 235, 237, 238, 239, 241, 242 (`SLOW_EXTRACTION`, all rescued) | `V1.0.6`: the balance sheet of a trading day is found by range over the balance sheets read once, and only snapshots that are missing or differ are written (the same rows: 20 s -> 1 s); the fundamentals of the valuation snapshots are read once (3.7 s -> 0.1 s). A refresh request that waited while a whole run started and ended is served by that run. Ten filings uploaded together afterwards: refresh 2.0 s on average, 3.0 s at most; every upload `SUCCEEDED` in 26-43 s on the standard extraction. |
| **A filing by nature logged although stored.** | 120, 121, 136 - 217, 230 - 232 (NSSS, MSIN, `SUCCEEDED`) | Decided by the model by design, not recorded (above). MSIN's EPS unit and cost of revenue fixed with it. Rows 122 - 124 (NSSS on DeepSeek, gross profit from expense rows by nature) were solved by the Haiku change. |
| **A company registered by a sibling upload.** MSIN FY2025 looked the company up (not found), another of the five registered it, and `registerCompany` refused: the full-text extraction stored nothing. | 229 | `registerCompany` adopts the company registered meanwhile. |
| **'Proforma equity'.** | 233 (MSIN FY2021), 236 (IMPC FY2023) | Counted with the non-controlling interests (section 4). |
| **An amount filed on two lines of the same kind.** | 240 (WIIM H1 2025) | Counted once (section 4). |

Row 243 is not a defect: `FinancialStatement-2024-Tahunan-MSTI.xlsx` as uploaded was not an .xlsx file
("No valid entries or contents found"), was rejected with 422 and is not stored; download it again.

Two things the filings themselves leave open, noted in the job, not errors: **RAJA** has no share count -
every Yahoo Finance count is five times the one its EPS implies in all five filings (prices and counts on
Yahoo are adjusted for a 1:5 split that no filing reflects yet), so it has no market cap and no valuation
until a filing after the split is uploaded; **MSIN**'s H1 2026 workbook files its EPS ten times too high
even after the rounding unit (46.9 and 39.8 for 284,347 and 241,315 million / 60.7 billion shares = 4.69 and
3.98) - stored as filed; the valuation does not use it (`eps_ttm` = TTM profit / shares outstanding).

### The MIKA uploads (errors 96, 97): a segment line outside the filed total

MIKA FY2025 and H1 2026 ended `INCOMPLETE` after the 3 minutes of the standard extraction, with
`saveRevenueSegments(PRIOR_PERIOD)` open ("segments do not reconcile"). All six statements were stored;
only the comparative revenue breakdown was not. Its sheet 1617000 has one unnamed line, "Service revenue
5", that the filing's own services subtotal and total leave out, so the lines summed to more than the
revenue. The full-text extraction could not help: the breakdown is not one of its statements. Fixed in the
rule-based extraction (section 4, a line the breakdown's own total does not include). Re-uploaded: both
`SUCCEEDED` in 49 and 100 seconds without the fallback, 10 segment rows each, the comparative totals
4,874,369,366,591 (FY2024) and 2,563,460,446,888 (H1 2025) equal to the filed revenue.

### The BBRI uploads (errors 27, 28, 41)

BRI's comparative columns repeat lines.

- **`FinancialStatement-2026-II-BBRI.xlsx` ended INCOMPLETE although the full-text extraction ran.** The
  model had mapped the comparative profit or loss (2025 H1), but a check of the first version of the
  full-text extraction rejected it: `revenue must be 123348477 (the filing's own total row), the mapped rows
  give 129669359`. For a bank revenue is no filed row at all; 123,348,477 was the figure the failing
  rule-based extraction had derived - without "Other insurance income" 4,466,709, the line it could not
  classify. Java overruled the model with the very figure that was wrong. That check is gone: in the
  fallback the model decides every value (above). Re-uploaded: `SUCCEEDED`, `full-text extraction of
  PRIOR_PERIOD`; revenue 127,815,186, profit from operation 35,004,968 and profit before tax 34,744,196 as
  filed.
- **Why the standard extraction ran into the 3-minute limit for that column (error 41, fixed):** "Other
  insurance income" 4,466,709 and "Other insurance expenses" 4,051,819 appear only in the comparative. The
  mapper cannot classify them and left them to the agent - but no category could make the statement
  reconcile, because the filing's totals do not include them at all: profit from operation 35,004,968 and
  profit before tax 34,744,196 are exactly the other lines. The mapper now ignores unclassified lines on that
  arithmetic proof (warning `memo_line`): with them left out every check passes, the reconciliation checks
  really ran against the filed totals, and no combination of their amounts cancels out (two lines that offset
  each other could both belong to the totals; those stay with the agent). A line the totals do include is
  untouched: BBCA's "Other insurance expenses" still goes to the agent. Re-uploaded: `SUCCEEDED` in 30 seconds
  by the standard extraction. The fallback had counted the two lines in revenue (127,815,186) and cost of
  revenue; stored now: revenue 123,348,477, cost of revenue 33,153,552.
- **`FinancialStatement-2025-Tahunan-BBRI.xlsx`** (INCOMPLETE in the standard extraction, rescued by the
  fallback): the comparative cash flow has "Withdrawal of financial assets amortized cost investments"
  9,884,450 beside "Withdrawal (placement) of marketable securities" 9,884,449, and the investing total
  -188,189 is exactly the sum without the first. The H1 2026 filing's comparative repeats two more lines
  (19,245 and 13,426,357). The mapper's cash flow section check now recognises **one line that the filed
  section total does not include** (residual "Other cash ..." lines first), when the total is exactly the sum
  of the other lines, and stores the statement with a warning. Re-uploaded: `SUCCEEDED` by the standard
  extraction, no fallback.

### The two TINS uploads

- **`FinancialStatement-2025-Tahunan-TINS.xlsx` ended INCOMPLETE** (`saveStatements(PRIOR_PERIOD) is blocked:
  income_statement failed checks [... computed 1638386000000 vs reported 1657269000000 (difference
  18883000000)]`). Two lines of its comparative 2024 FY are not part of the filing's own totals, and both are
  now recognised by the mapper, so the standard extraction stores the filing (no fallback needed):
  - profit or loss: "Other expenses" 18,883 repeats the net of "Other income" 2,250 and "Other gains
    (losses)" -21,133. Profit before tax 1,657,269 reconciles with the residual lines counted once; the
    repeat is ignored with a warning (`double_reported`), only when that makes the statement reconcile
    exactly. In the current column (32,341 and 88,850) both amounts count, as before.
  - cash flow: "Other cash inflows (outflows) from investing activities" 25,395 beside an investing total
    of -453,950 that is exactly the sum of the other lines. A line the section total does not include is
    not counted (warning), only when the section then reconciles exactly.
- **`FinancialStatement-2024-Tahunan-TINS.xlsx` was refused** (`Unsupported filing: No balance sheet data
  (1210000); No profit or loss data (1311000 / 1321000)`). The workbook IDX published has an **empty
  current-year column**: the balance sheet, profit or loss and cash flow carry only the comparative 2023 FY
  (and one opening cash balance). The 2024 figures are not in the file, so no extraction can produce them.
  The upload is now accepted; the full-text extraction stores the comparative 2023 FY (one model call, about
  20 seconds) and the job ends `INCOMPLETE` with: `CURRENT_PERIOD 2024 FY: the workbook has no figures for
  this period ... download it again from IDX, or upload the next filing, whose comparative column carries
  this period`. 2024 FY is in the database from the comparative of the FY2025 filing.
- **TINS FY2023's loss per share** was stored as 0.00006: the filing gives the EPS in millions (like its
  comparative's 0.00014 for Rp 140) and without the sign of the loss of 449,690 million. The EPS unit is now
  judged from the comparative column when the current one contradicts its own profit, and an EPS takes the
  sign of the profit attributable to the parent (warning `eps_sign`): -60.

## 7. Model cost per upload (`llm_usage`)

Every model call of an upload job is recorded with its tokens and cost, as the screening does for a run
([SCREENING_DOCS.md](SCREENING_DOCS.md)): one row of `llm_usage` per attempt, tied to the job by
`llm_usage.job_id` (`V1.0.19__schema_ingestion_cost.sql`).

| Column | Content |
|--------|---------|
| `job_id` | the upload job (`ingestion_job`); deleted with it |
| `stage` | `PLAN` (the planner), `EXECUTE` (a tool turn of the agent loop), `REVIEW` (the reviewer) of the standard extraction; `FULL_TEXT` (an answer of the full-text extraction) |
| `model` | the model the request asked: `spring.ai.openai.chat.model` (`OPENAI_MODEL`, DeepSeek V4 Flash) for the standard extraction, `neracalab.ingestion.fallback.model` (`INGESTION_FALLBACK_MODEL`, Claude Haiku 5.5) for the full-text extraction |
| `ticker` | the filing's company |
| `prompt_tokens`, `completion_tokens`, `reasoning_tokens`, `cached_tokens` | as the provider reports them |
| `cost_usd`, `cost_estimated` | the cost OpenRouter reports for the call (`usage.cost`; every request asks for it with `"usage": {"include": true}`). A call without one is priced from `neracalab.screening.llm.prices` and flagged |
| `duration_ms`, `error` | how long the attempt took; why it failed (then no tokens and no cost) |

- **Every attempt is a row**, also a failed one (a timeout, a stream reset, HTTP 5xx) and each retry. What the
  provider billed for a failed or abandoned attempt, if anything, is not known and is recorded as 0.
- **Written while the job runs**, each call on its own, so the cost of a job that is stopped at its time limit or
  fails is kept. Recording never fails the ingestion: a row that cannot be written is logged
  (`IngestionUsageRecorder`).
- **Read from the same rows everywhere**: `GET /api/v1/ingestions` returns `cost` per job (calls, tokens, USD),
  `GET /api/v1/ingestions/{id}` also `usage` per stage and model
  ([INGESTION_JOBS_DOCS.md](INGESTION_JOBS_DOCS.md), section 2). The IDX XBRL Ingestion page shows the cost in
  the jobs table and the breakdown under "Model cost" in the job details.
- Uploads stored before this was added have no rows, so no cost is shown for them.
- **Failed calls go to the error log too.** When an upload is recorded in `ingestion_error` (section 6), the
  row's `details.failedModelCalls` lists the job's failed model calls from these rows, so a full-text
  extraction whose model (Haiku) timed out or was refused shows what the provider said:

  ```sql
  SELECT e.error_id, e.ticker, e.period, e.final_status, c->>'model' AS model, c->>'stage' AS stage,
         c->>'error' AS error
  FROM ingestion_error e, jsonb_array_elements(e.details->'failedModelCalls') c
  WHERE NOT e.fixed_by_claude_code ORDER BY e.occurred_at DESC;
  ```

Cost per model over the last week:

```sql
SELECT u.model, count(DISTINCT u.job_id) AS uploads, count(*) AS calls,
       sum(u.prompt_tokens) AS prompt_tokens, sum(u.completion_tokens) AS completion_tokens,
       round(sum(u.cost_usd), 4) AS cost_usd
FROM llm_usage u
WHERE u.job_id IS NOT NULL AND u.created_at > now() - interval '7 days'
GROUP BY u.model ORDER BY cost_usd DESC;
```

Cost of each upload, with what the full-text extraction added:

```sql
SELECT j.requested_at, j.file_name, j.status,
       round(sum(u.cost_usd), 5) AS cost_usd,
       round(sum(u.cost_usd) FILTER (WHERE u.stage = 'FULL_TEXT'), 5) AS full_text_usd,
       count(*) AS calls, count(*) FILTER (WHERE u.error IS NOT NULL) AS failed
FROM ingestion_job j JOIN llm_usage u ON u.job_id = j.job_id
GROUP BY j.job_id ORDER BY j.requested_at DESC LIMIT 20;
```

Tests: `IngestionUsageRecorderTest` (an OpenAI-compatible server on localhost answers the calls: stage, the
model asked - DeepSeek and Haiku -, tokens and the reported cost; a call without a cost is estimated and
flagged; a failed attempt and its retry are both recorded; a row that cannot be written does not fail the
ingestion), `IngestionJobCostTest` (the sums per job and per stage and model, against Postgres).
