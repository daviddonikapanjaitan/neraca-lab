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
| 422  | not an .xlsx / not an IDX XBRL workbook, or a filing in another currency than the company's stored data (ProblemDetail body; logged in `ingestion_error`). An IDX workbook the standard extraction cannot map is accepted (202) and stored by the full-text extraction, section 6 |
| 413  | file larger than 50 MB                                                               |

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
OPENAI_MODEL=deepseek/deepseek-v4.1-flash-1017
```

`application.yaml` imports it (`spring.config.import: optional:file:.env[.properties]`) and points
the Spring AI OpenAI client at OpenRouter; real environment variables take precedence. Docker
compose reads the same file and passes the values to the backend container at runtime.

**OpenCode Go** (`OpenCodeGoRouting`, `neracalab.llm.opencode-go.*`): with `OPENCODE_GO_API_KEY` set, a call whose
model is listed in `OPENCODE_GO_MODELS` (`openrouter-id=go-id`, default
`deepseek/deepseek-v4.1-flash-1017=deepseek-v4.1-flash,anthropic/claude-haiku-5.5=claude-haiku-5-5`) goes to
OpenCode Go (`https://opencode.ai/inference/go/openai/v1`, a monthly subscription) with its own OpenAI client; every
other call stays on OpenRouter. `IngestionAgent` and `LlmGateway` wrap the Spring AI `ChatModel` with it, so the
standard extraction, the full-text extraction and the screening / analysis agents are all routed by model. The request
is translated: `provider` and `usage` (OpenRouter only) are left out, `"reasoning": {"enabled": false}` becomes
`"thinking": {"type": "disabled"}`, `"reasoning": {"effort": e}` the `reasoning_effort` e, and the header
`x-opencode-session` (without it: "Request is missing x-opencode-session") is added. Go answers `usage.cost` 0 (billed
to the plan), so `llm_usage.cost_usd` is 0 for its calls. Its DeepSeek models answer only a workspace whose Privacy
settings allow Global regions ("This Go model requires Global regions"); Claude Haiku 5.5 answers without. Until the
setting is changed `backend/.env` routes Haiku alone (`OPENCODE_GO_MODELS=anthropic/claude-haiku-5.5=claude-haiku-5-5`).
Only the OpenRouter client (`OpenAiChatModel`) is routed: a test's scripted or mocked `ChatModel` is used as it is (with
the key in `backend/.env` the Analysis, Macro and Technical screening controller tests had sent their DeepSeek calls to Go
instead of their script). Test: `OpenCodeGoRoutingTest`.

```properties
OPENCODE_GO_API_KEY=oc_sk_...
OPENCODE_GO_MODELS=anthropic/claude-haiku-5.5=claude-haiku-5-5
```

**Every request names `OPENAI_MODEL`.** Spring AI 2.0's `OpenAiChatOptions` fills a missing model
with its own default, `gpt-5-mini`, and per-request options override the configured default. The
agent's planner, executor and reviewer built their options without a model, so until this was fixed
every ingestion call went to `openai/gpt-5-mini` on OpenRouter, whatever `OPENAI_MODEL` said (the
stock screening was not affected: `LlmGateway` always sets its model). `IngestionAgent.options()` now
sets the configured model on every request (`IngestionAgentModelTest` pins both the Spring AI default
and the fix), and the job result lists the models the responses report (`metrics.models`, e.g.
`["deepseek/deepseek-v4.1-flash-1017"]`), so a substitution is visible in the job.

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
| `neracalab.jobs.upload-workers`         | 50      | uploads stored at the same time (env `INGESTION_UPLOAD_WORKERS`; 0 = as `neracalab.jobs.workers`, 5, which the other queues keep) ([INGESTION_JOBS_DOCS.md](INGESTION_JOBS_DOCS.md), section 4) |
| `neracalab.ingestion.fast-path`         | true    | a filing whose every column maps without a failed check or an unclassified line is stored without the agent; the deterministic verification decides, anything open goes to the agent as before (env `INGESTION_FAST_PATH`), section 6 |
| `neracalab.ingestion.same-company-in-order` | true | filings of one company are stored one after the other in upload order, so a filing in another currency is checked against those stored before it; false: at the same time (env `INGESTION_SAME_COMPANY_IN_ORDER`) |
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
| `refreshDerivedData`     | write | recalculates the company's derived data: the share scripts, then `ValuationRepository.refreshAfterFiling` (V1.0.6 for this company: market / valuation snapshots, cleared trailing ratios, fundamental and valuation metrics, stale valuation metrics deleted); about 0.4 s, beside other companies' refreshes (until 2026-10-31 the whole script for every company, one run at a time) |
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

**One currency per company.** `company.currency` is the presentation currency of the filings (sheet `1000000`):
statements, prices (`price_daily` is converted into it) and every ratio are in it, so a filing in another currency
than the company's stored statements is refused with 422 before anything is stored (`IngestionService.currencyConflict`,
also when a stored file is run again). CUAN presented in IDR up to FY2023 and in USD from FY2024 (its FY2024 filing
restates FY2023 in USD): its FY2023 filing stored FY2022 and FY2023 in rupiah beside the dollar years (revenue
1,494,125,816,458 next to 801,723,000) and switched `company.currency` to IDR until the next USD upload. An older
filing in a former currency is refused; its periods come from the comparative columns of the later filings (CUAN
FY2023 from FY2024's; FY2022 exists only in rupiah). A newer filing in another currency - a change of presentation
currency - is refused too: stored statements and prices are not converted automatically. A company without stored
periods takes the currency of its first filing.

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
  **A split after the filing.** Yahoo's counts are split-adjusted to today, so a filing of before a split
  states its EPS on a count k times smaller (CUAN FY2024: 160,785,000 / 0.014 = 11.5 billion shares; Yahoo
  112,418,900,000 after the 1:10 split of 2025-07-15). `WebShareCounts` also reads Yahoo's split events
  (`YahooChartClient.events`, `/v8/finance/chart?events=split`); a count that misses the EPS as published is
  checked on the count of before the latest split after its date, the latest two, ... (a filing reflects the
  splits before it was issued, not those after) and used when exactly one of these fits. It is stored as
  published - split-adjusted, like the prices - and each split it needed is recorded in `corporate_action` with
  its real date (`IngestionRepository.recordPublishedSplit`, read like a seeded split; a split of the same ratio
  detected from the filings within a year takes that date), so `applyDetectedSplits` brings the EPS of before it
  onto the count after it. A row is on the count of before a split when it is nearer to it than to the count
  after it (within a factor sqrt(k)): DSSA's counts moved 25% by a buyback between its splits.
  **A buyback in the period.** EPS is per weighted share; with treasury shares bought during the period the
  end count is below it (BUKA FY2025: 92,970,828,660 at the end, 103,121,636,167 at the start, EPS 31.91 =
  98.5 billion weighted shares). A period-end count that misses the EPS is accepted when the EPS lies between
  profit / the published start count and profit / the end count, both consistent, with the shares issued
  unchanged within 1% (only treasury shares moved; a rights issue - BKSL 2023 - gives no range) and no split in
  the period.
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
  rejection to the EPS the model names. A par value inferred from one column must also fit the other column's
  EPS when that column's share capital did not change in its period (`FilingMapper.parMisfit`): a coarse EPS fits
  several counts. BKSL's FY2025 workbook files FY2024's EPS as 0.10 (17,355 million profit: 116 .. 347 billion
  shares), which par 50 fits, while FY2025's 4.95 (830,295 million profit: 167.7 billion shares) fits no standard
  par value - BKSL's shares have several par values. Before, par 50 gave 251,243,773,287 shares at 2023-12-31 ..
  2025-12-31, 50% too many (market cap and every per-share figure); now the filing gives no par value and Yahoo
  Finance's 167,708,902,705, which fits both EPS, is used. A column whose capital changed is no evidence (OMED's
  FY2021: 4,500 -> 575,000 million, its EPS restated beyond that range). (2) Within a column, with
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
- **A section total that leaves out a line.** WIFI's FY2025 financing total, 10,909,102,818,568, is not the sum
  of its lines, 10,904,384,108,332: it leaves out the dividends paid (4,718,710,236). With the sum of the lines,
  operating + investing + financing = the net change as filed (6,146,203,492,352), and beginning cash + net change
  = ending cash. When exactly one section (investing or financing) explains the gap this way and the roll-forward
  holds, that section is counted at the sum of its lines (warning `financing_total` / `investing_total`). Before,
  the dividends were taken for a line "outside the total" and the column failed `net_change`.
- **Amounts entered twice in a section.** WIFI's H1 2026 filing enters three H1 2025 financing cash flows twice:
  bank loans 783,412,016,329 also as unsecured loans, the repayment of other borrowings 15,495,302,170 also as
  negative non-bank proceeds, consumer financing 674,885,668 as proceeds and as a negative payment. Lines of a
  section with the same signed amount (exactly two per amount) are one cash flow; when dropping one of each pair
  gives the filed section total exactly, they are counted once - in the section and in the fields (debt issued
  784,086,901,997, debt repaid -15,495,302,170) - dropping the line whose filed sign contradicts its label, else
  the later one (warning `financing_section`). Tried after the single line outside the total.
- **A pair of lines outside a restated comparative's total.** BEER's FY2025 filing restates FY2024: the advances
  and loans to other parties (made 98,006,019, repaid 77,432,853) moved into operating (-21,402,282,339, was
  -21,381,709,173), but the comparative still lists both lines under investing; its total -3,273,103,330 is the
  sum of the other lines. SMGA's H1 2025 comparative does the same with the amounts due to related parties
  (58,410,141,892 in, 4,671,737,586 out) under financing. When the filed operating + investing + financing give
  the net change in cash, its roll-forward holds, and exactly one pair - an outflow and an inflow - explains the
  gap, the pair is not counted (warning `investing_section` / `financing_section`; the fields are read from their
  own rows as before). Tried last; errors 958 and 961.
- **A slip of a few rupiah in a full-amount filing.** Full-amount filings must still reconcile, up to a difference
  of under a billionth of the amount, which is a warning, not an error: WIFI's FY2022 ending cash is filed
  20,928,775,242, beginning cash + net change is 20,928,775,245. The reported figures are stored. Filings in
  thousands or millions keep their tolerance of one reported unit.
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
- **One line outside a restated comparative's profit before tax.** The H1 2025 columns of the H1 2026 filings of
  PTRO ("Selling expenses" 3,514 thousand USD, profit before tax 7,846) and BNBR ("Other expenses" 2,784 million,
  profit before tax 72,088), PTRO's FY2024 column of its FY2025 filing ("Other gains (losses)" 3,804, the 4,361 of
  the FY2024 filing net of the associates' -557, which the column lists again; profit before tax 7,868) and MOLI's
  H1 2025 column ("Other income" -1,985,667 thousand) each have one line the filed profit before tax leaves out.
  In a comparative column only, when no other rule reconciles it and ignoring exactly one classified line makes
  every check pass, that line is ignored (warning `left_out_line`); not when the agent has classified lines of the
  column, and never in the period's own column. Tried last; errors 1019 - 1021 (and MOLI's 355, 369 - 371, which
  the full-text extraction stored before). A sweep of the 864 readable workbooks: only these four columns.
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
| `UploadJobTimeoutTest` (Docker Postgres) | an upload job over a 3 s limit (mock agent working 4 s) ends `FAILED` with "Stopped after the 3 seconds limit on HRTA 2026 H1" within seconds of the limit, and its `ingestion_error` row is written before the job is finished (so its clean-up finds it) |
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
| `ShareSplitTest` (Docker Postgres, rolled back) | a 2:1 split restated by a comparative (`SPLIT_ADJUSTED`): the period and an earlier period on the old basis adjusted, a period on another basis not, nothing adjusted twice; a restated (non-split) EPS never gets the comparative's share count; the other upload order (comparative first, then the period's own filing: MAPA 1:10) ends the same, with balance sheet shares and share snapshots adjusted, the split recorded once and the own filing uploaded again changing nothing; a restatement that is no split is replaced by the own filing; split factors; published splits (DSSA's two) recorded with their dates adjust the EPS of before them once, also one 25% off the count before the split; the old basis is the nearer of the counts before and after a split |
| `FilingMapperTinsTest` (unit) | the TINS filings: FY2025's comparative counts its residual profit or loss lines once and its cash flow's residual investing line not at all, the current year still counts every line, every statement of FY2025 is storable; FY2024 as published has no current-year figures; FY2023's loss per share is scaled and gets its sign (-60) |
| `FullTextExtractorTest` (unit, scripted model) | TINS FY2024: nothing is invented for the empty 2024 column, the model decides all three statements of the comparative 2023 and every amount is the cell x the rounding unit, the rule-based statement is passed as a reference only; a mapping that contradicts itself is shown to the model, which corrects it; its last answer is stored even when it does not add up, and that is reported; amounts and unknown rows in the answer are never used; a field made up of other rows in one column (`overrides`); the model may adopt a statement that passed every check, never one that failed; BFIN (Financing Industry): every sheet of the file is in the text and the model's rows are stored, nothing of the rules is shown; a sign that contradicts the field is pointed out once with the rows concerned; MSIN FY2025 (by nature): profit from operations left empty is asked for again and stored from the rows that compute it, and is an open item of the upload when the last answer leaves it empty too; ISAT FY2023 / FY2022 and PGEO FY2023 (by nature): operating expenses other than the rows operating income subtracts, a non-operating row in operating income, and an operating income no choice of the model's rows reconciles are shown to the model and corrected from its rows or the template's when it keeps them |
| `ProviderIgnoreTest` (unit) | the excluded providers are sent with every request (`provider.ignore`); without exclusions nothing is sent |
| `FilingMapperWifiTpiaTest` (unit) | WIFI FY2025: the financing total that leaves out the dividends is counted at the sum of its lines (10,904,384,108,332), the FY2024 comparative unchanged; WIFI H1 2025 (in the H1 2026 filing): three amounts entered twice counted once, debt issued 784,086,901,997 and repaid -15,495,302,170, which line of a pair is dropped; WIFI FY2022: a 3 rupiah slip is a warning; TPIA's self-contradicting H1 2025 income statement and FY2024 breakdown are still refused |
| `FilingMapperBeerIndoTest` (unit) | BEER's FY2024 comparative (in the FY2025 filing): the advances to other parties made and repaid, outside the restated investing total, are not counted (investing -3,273,103,330, operating -21,402,282,339), every statement of the filing storable; BEER's own FY2024 filing still counts them; INDO FY2022's roll-forward (20,000,000 off as filed) stays an error; a pair is taken only when it is the only one |
| `FilingMapperPtroBnbrTest` (unit) | One line outside a comparative's profit before tax: PTRO FY2024 (in the FY2025 filing, the associates' -557), PTRO H1 2025 (selling expenses 3,514) and BNBR H1 2025 (other expenses 2,784) in their H1 2026 filings are ignored (`left_out_line`), every statement of the three filings storable; PTRO's own FY2024 column is not touched |
| `FilingMapperRiseSmbrTest` (unit) | RISE H1 2025 (in the H1 2026 filing): an FX effect of 398,002 the filed roll-forward leaves out is not counted, ending cash 224,776,635,800, every check passes; FY2025's FX effect still counted; SMBR's FY2024 comparative (investing flows counted twice) still refused, its own filing reconciles |
| `IngestionVerifierSegmentsTest` (unit) | TPIA FY2025: a refused comparative breakdown of a period whose stored breakdown adds up to its revenue is a note, not an open item; without a stored breakdown, or with one that does not add up, it stays open |
| `FilingMapperBbriTest` (unit) | BBRI: the comparative cash flows of FY2025 and H1 2026 are storable (a line outside the filed section total), the current year's check is unchanged; the two unclassified insurance lines of H1 2025 are ignored because the filing's totals reconcile exactly without them, and every statement of the H1 2026 filing is storable; BBCA's insurance line, which its totals include, is not ignored |
| `IngestionServiceFallbackTest` (unit, scripted stages) | when the full-text extraction takes over: unsupported workbook (agent not started), INCOMPLETE, past the 3 minutes, failed; never after a clean result; its own failure leaves what the standard extraction reached, its own timeout fails the upload; switched off; the upload endpoint accepts an unsupported workbook and refuses an unreadable one |
| `FullTextExtractionDatabaseTest` (Docker Postgres, scripted model, rolled back) | TINS FY2024 is stored from its text with the standard steps: the comparative 2023 FY in the database, nothing for 2024, the verification says why; the error log keeps what went wrong and starts unfixed |
| `FilingMapperMikaTest` (unit) | MIKA: the comparative breakdowns of FY2025 and H1 2026 reconcile once the one line outside the filed total is left out (warning `segment_line`); the current columns are stored as filed |
| `FilingMapperAuditedTickersTest` (unit) | ULTJ: no weighted shares for FY2022 (treasury stock), kept for years without; MAPA: FY2022 as EPS 412 / 2,850,400,000 shares in its own filing and 41 / 28,504,000,000 in the FY2023 comparative; BBCA: lines filed as 0 need no classification, "Other insurance expenses" with an amount is left to the agent and reconciles as an operating expense |
| `FilingMapperAsgrTest` (unit) | all five ASGR filings map without errors; the H1 2025 loss reported twice in the H1 2026 filing is counted once (profit before tax 139,690, operating income 116,372), not in the current column, never against an agent classification; the FY2023 workbook's full amounts under an "In Million" label are read as full amounts (total assets 2,682,813 million, as in the FY2022 workbook); SIMP: FY2023 full amounts without revenue (gross profit 3,358,216 million, revenue from the FY2024 comparative), H1 2026 EPS filed in millions (56.35), every SIMP filing without errors |
| `FilingMapperWebSharesTest` (unit) | web share counts against the real filings: BNGA FY2025 gets the three audited counts, H1 2026 rejects the inconsistent 2026-06-30 point, counts five times too high are rejected, SMDR's FY2022 filing rejects the split-adjusted count and its FY2025 filing accepts it, a filing with its own counts is kept, NSSS (profit or loss by nature) gets the count that fits its EPS and refuses one that does not; BBCA FY2022: the count accepted at 2022-12-31 is carried to 2021-12-31 and 2020-12-31 (share capital unchanged, no treasury stock, the FY2021 EPS confirms it), never to a date the website publishes itself, never with treasury stock (BFIN), never where the EPS contradicts it |
| `FilingMapperWebSplitsTest` (unit) | the share counts of the error log of 2026-10-16: BKSL FY2025 uses no par value (par 50 fits only FY2024's EPS) and gets Yahoo's 167,708,902,705; CUAN FY2024 accepts Yahoo's split-adjusted count only with the 1:10 split of 2025-07-15 (11,241,890,000 before it), CUAN H1 2026 has dates on both sides of it; DSSA FY2023 predates both splits and its end count fits between the start and end counts (buyback), FY2024 needs only the 2026 split, H1 2026's EPS fits no count; BUKA FY2025's end count 92,970,828,660 with 10,177,877,407 treasury shares (buyback), H1 2026's EPS outside the counts refused; BKSL FY2023's rights issue gives no range |
| `FilingMapperAdroTest` (unit) | ADRO FY2023 tags only gross profit: revenue from the revenue breakdown (6,517,556 thousand USD), cost = revenue - gross profit; without a breakdown both stay empty |
| `FilingMapperCashRoundingTest` (unit) | the review of 2026-10-31: a full-amount roll-forward one rupiah off is the filing's rounding (ALMI FY2024 27,338 / 27,337, ARTI FY2023, ASHA FY2025: a warning, the filed ending cash stored); AMMS H1 2026's investing section whose only line is 0 counts as 0 |
| `FilingMapperAmortizationTest` (unit) | no amortization from the intangibles roll when the goodwill halved (PTRO H1 2026: 134,250 + 4,050 - 60,088 = 78,212 against the note's 6,075; CUAN H1 2026); ARKO, without goodwill, keeps the roll |
| `IngestionVerifierInsurerDepositsTest` (unit) | AMAG FY2025, an insurer: ending cash 485,425,155 thousand = cash 64,021,213 + 421,403,942 within time deposits 651,473,868 is a note; beyond the deposits, or stored figures not the filing's, a problem |
| `IngestionVerifierRoundingTest` (unit) | UNTR FY2022: ending cash filed with decimals (38,281,513.468848 million) differs from the balance sheet by less than the filing's unit - a note, not an error; one unit in a full-amount filing too (BRAM H1 2026 24,367,847 / 24,367,846 USD, BMBL FY2022), two units (BMBL FY2023) a problem; MGLV FY2024: ending cash 3 rupiah above the balance sheet's 7,783,983,677, under a billionth of it - a note; INDO FY2022's 20,000,000 stays an error |
| `IngestionToolsSegmentsTest` (unit, mocked repository) | a comparative breakdown of a restated revenue is kept out (BMHS FY2024's 1,488,197,483,371 for FY2023's 1,287,690,839,110), one of the same revenue in millions is saved (INPP FY2025's 1,309,662 million for 1,309,661,867,481); the period's own filing removes a stored breakdown of another revenue (BHIT FY2024) and keeps one of its revenue |
| `IngestionAgentFastPathTest` (Docker Postgres, rolled back) | the fast path: HRTA H1 2026 maps cleanly and is stored and verified without a model call; a company without segments gets them named by one call (stage `SEGMENT_NAMES`) and nothing else is asked; BUDI FY2025 and BMBL FY2023 (failed checks) and BTEL FY2024 (by nature) are not for it; the model's names apply to new segments only, OTHER only in the residual slot |
| `DerivedDataRefreshTest.theRefreshOfOneCompanyLeavesWhatTheScriptComputes` (Docker Postgres) | the per-company refresh after an upload leaves every derived row of all 361 companies as V1.0.6 computes it (and the script changes nothing it left), under a second per company |
| `PacedHttpClientAheadTest` (local HTTP server) | a request made `ahead` (an upload's share counts) is sent right after the one in flight, before three waiting background requests |
| `WebShareCountsTest.aCompanysAnswerIsReusedForItsNextFilingAndAFailureIsNot` (unit) | Yahoo's share counts are fetched once per company (a 429 is not kept) |
| `JobWorkersConfigurationTest.theUploadQueueHasItsOwnNumberOfWorkers` (unit) | `upload-workers` 50 beside `workers` 5; unset: as `workers` |
| `MarketInstrumentSequenceTest` (Docker Postgres) | running V1.0.26 again draws no `instrument_id` (the SMALLSERIAL ran out at 32,767 and no start succeeded) |
| `BmblBoatSharesTest` (Docker Postgres) | V1.0.43: BMBL 1,030,080,995 (share capital at par Rp 40, H1 2026 EPS -1.05), BOAT 3,501,680,000 from its IPO of 2024-11-12; market cap = price x count |
| `ShareCountCorrectionsTest` (Docker Postgres) | V1.0.44: MSIN 60,676,178,205 and its H1 2026 EPS 4.69, SMGA 7,000,000,000 / 8,750,000,000, RMKE 21,875,000,000 with its split of 2026-07-17 recorded, WIFI, INET and PACK after their share issues |
| `FilingMapperAdmrArtoBrmsTest` (unit) | the error log of 2026-10-18: ADMR lines filed with decimals outside the investing total (-322,409,000); ARTO's syirkah funds from their lines (526,059 / 110,361 million); BRMS's EPS filed per 1,000 shares (0.00035 per share with Yahoo's 141,784,040,338 shares; counts that fit neither way change nothing) |
| `IngestionServiceUnitTest` (unit) | IMJS FY2024 ("Full Amount" over millions) is read in millions when FY2023's stored total assets prove it, keeps its unit without such a figure; IPCC 9M 2026's par-derived counts contradicting the stored FY2025 count are dropped (kept after a recorded split), ATIC's share issue kept |
| `KoinHillSharesTest` (Docker Postgres) | V1.0.45: KOIN 980,843,732 at every date and no reverse split at 2023-01-01; HILL 14,741,500,000 at 2024-12-31 / 2025-12-31; FLMC 781,250,000, no count of 1 |
| `IngestionVerifierOverdraftTest` (unit) | GGRM: overdrafts netted against cash are a note, other stored figures a problem; LPPF FY2023 within the stored short-term debt; LAJU FY2022: an ending cash cell with a binary fraction beyond the stored scale is the stored amount |
| `FilingMapperEpsCellTest` (unit) | FLMC FY2025's EPS cell holding the profit gives no share count; CBPE FY2021's 35,000 shares before its IPO stay |
| `FilingMapperTabarruTest` (unit) | CASA FY2025's tabarru funds balance the balance sheet with the syirkah funds; ARTO FY2022's syirkah total on that row is not counted twice |
| `IngestionServiceCurrencyTest` (unit) | CUAN's IDR FY2023 filing is refused (422) when the company's stored data are in USD, older or newer than the latest stored period; the same currency, a new company or one without stored periods are accepted |
| `FilingMapperErrorLogTest` (unit) | the filings behind the error rows of 2026-10-11: IMPC FY2024's comparative with 'Proforma equity' (non-controlling 352,479,399,434 = 214,434,701,176 + 138,044,698,258, total 2,625,694,521,530, no failed check), WIIM H1 2026's comparative with 5,797,003,720 on two lines (counted once, profit before tax 196,550,613,747), MSIN's EPS unit per filing (1 for FY2022, 1,000,000 from FY2023) and a published count that fits it, by nature = decided by the model by design, and every filing of AUTO, DKFT, DMAS, IMPC, PTBA, RAJA, SMSM and WIIM without a failed check or an unclassified line |
| `FilingMapperTuguMoliCnmaTest` (unit) | the filings behind the error rows of 2026-10-14: TUGU read in the Insurance Industry taxonomy with 3,555,575,600 shares at par 50 (EPS in millions in FY2024, an unrestated split in FY2023), MOLI's EPS in thousands (H1 2026) and its contradicting FY2025 columns (no par 1), CNMA's depreciation with its balance sheet by order of liquidity |
| `FilingMapperKetrLppfTest` (unit) | the filings behind the error rows of 2026-10-22: KETR's FY2024 comparative in its FY2025 filing reconciles with 'Finance income' 3,820,523,591 counted once, 'Other income' without it (11,241,254,994, operating income 181,136,399,270 as in the FY2024 filing); LPPF FY2023, rows missing on the face of its balance sheet: inventory, short-term debt, goodwill and intangibles are left empty, not 0, while the FY2024 comparative has inventory 792,781 and bank loans 550,003 million |
| `FilingMapperItmgMdkaMedcHrumTest` (unit) | the filings behind the error rows of 2026-10-21: ITMG FY2023 read as full amounts (total assets 2,187,847,000 USD; FY2024 stays in thousands); ITMG and MDKA H1 2025 reconcile with 'Other gains (losses)' counted once (operating income 123,254 thousand / 83,182,117); with Yahoo Finance's counts ITMG's H1 2025 EPS 0.00008 is read as 0.08 and MDKA's H1 2026 EPS 0.00042 as 0.0042, and no column is corrected without published evidence or on counts of another basis; MEDC's and HRUM's discontinued EPS rows that are no such EPS are left out (MEDC FY2022 0.021503, FY2025 0.00408), UNVR's, EMTK's, MPMX's and KRAS's are kept |
| `FilingMapperTotlMidiTest` (unit) | the filings behind the error rows of 2026-10-13: TOTL's breakdowns with "Jasa Konstruksi" on several lines of one kind are one segment equal to the line its FY2024 filing shows (lines of different kinds stay apart); MIDI's H1 2025 cash roll-forward holds without the deconsolidation line its ending cash excludes |
| `DerivedDataRefreshTest` (Docker Postgres) | the refresh of the derived data: a second run changes nothing and takes seconds, the market snapshots equal the lookup per trading day they replaced, six requests at once cost one or two runs, a company with prices, shares and a balance sheet but no trailing twelve months (BACH) is valued on its balance sheet (market cap, EV, P/B; no TTM ratios), the same rows from the per-company refresh |
| `WebShareCountsTest` (unit) | the fallback with a mocked Yahoo client: completes BNGA, a failed fetch leaves the filing unchanged with a note, no fetch when disabled or when the filing has counts |
| `IdxWorkbookReaderTest` (unit) | sheet names: General kept, pre-2023 `1 CurrentYear` / `2 PriorYear`, Infrastructure `3xxxxxx` and Financial `4xxxxxx` -> `1xxxxxx`; taxonomy of a sheet code |
| `IngestionRepositorySegmentsTest` | a current-period breakdown removes a segment that only another filing stored for the period (rolled back)                                                                                                                                          |
| `BackendApplicationTests` (unit) | the application context starts (SQL init, Spring AI client, agent beans)                                                                                                                                                                             |
| `FinancialStatementUploadTest`   | the asynchronous upload with a mocked agent: 202 job, background processing, stored once per checksum, wrong files rejected, file download; see [INGESTION_JOBS_DOCS.md](INGESTION_JOBS_DOCS.md)                                                     |
| End-to-end (manual)              | uploading all six filings through the endpoint into an empty database reproduces the seed data                                                                                                                                                       |

Run the unit tests with `cd backend && ./mvnw test` (needs the Postgres on localhost:5432: the full
stack from the start scripts, or `docker compose up -d postgres redis` in `backend/`). The workbooks
and PDFs the tests read are in `data/`, which is not in the repository (`.gitignore`): a test whose file
is missing is skipped (JUnit assumption), not failed.

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
`ebitda` are derived from the model's values: `ebitda` is `ebit` plus the "Depreciation and amortisation expenses"
the profit or loss states when it is by nature (`FilingMapper.statedDepreciationAndAmortization`, every column alike,
the interim comparative too), else `ebit + depreciation + amortization`. An EPS gets the sign of the profit
attributable to the parent.

For a workbook of a taxonomy the rules do not map, nothing of the rule-based extraction is shown to the
model and nothing can be adopted.

Measured with DeepSeek V4 Flash, the model of the full-text extraction until it got its own
(`neracalab.ingestion.fallback.model`, Claude Haiku 5.5 when these were measured, Claude Sonnet 5.5 since 2026-10-23; the NSSS row below):

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
`gross_profit` are never taken from the model's answer (MSIN: 'Program and broadcasting
expenses' named as cost of revenue, no gross profit beside it) - they are cleared before the checks. They come only
from the filing's own cost of sales note (1670000, 'Cost of sales and revenue'; `FilingMapper.costOfSales`), when it
states a positive cost for the column and the operating expenses by nature hold at least that cost: cost of revenue =
the note, gross profit = revenue - cost, the operating expenses without it, the operating income as filed (BIPI
FY2025: 200,443,257 of revenue 235,089,682, gross profit 34,646,425, operating expenses 212,368,437 -> 11,925,180;
MNCN H1 2026: the note's 2,133,396 million are the program and broadcasting expenses). Without the note both stay empty.
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
| `model` (`INGESTION_FALLBACK_MODEL`)        | `anthropic/claude-haiku-5.5` | its own model (OpenRouter id), stronger than the standard extraction's DeepSeek flash; empty = the standard extraction's model. Since 2026-10-30 Claude Haiku 5.5 through OpenCode Go (section 2; BUKA FY2024: three answers in 47 s, cost 0 against USD 0.05 - 0.20 a filing on Sonnet 5.5 through OpenRouter, the default from 2026-10-23). Recorded as `details.fullTextExtraction.model` in `ingestion_error` |
| `reasoning-effort` (`INGESTION_FALLBACK_REASONING_EFFORT`) | `low` | OpenRouter reasoning effort of its calls, sent as `"reasoning": {"effort": "low"}` in place of the standard extraction's `"enabled": false` (`model-reasoning`): Claude Sonnet 5.5 refuses reasoning switched off with HTTP 400 "Reasoning is mandatory for this endpoint and cannot be disabled" (error 878, every full-text extraction failed on its first call); empty = the standard extraction's setting |
| `after` (`INGESTION_FALLBACK_AFTER`)        | `3m`    | longest the standard extraction runs before the full-text extraction takes over |
| `timeout` (`INGESTION_FALLBACK_TIMEOUT`)    | `5m`    | longest the full-text extraction runs                      |
| `model-call-timeout`                        | `150s`  | one of its model calls (one long JSON answer)              |
| `max-chars`                                 | `120000`| most workbook text sent to the model; the statement sheets always fit, notes sheets are left out first |
| `rounds`                                    | `3`     | answers of the model: the first, then its own corrections; the last one is stored |

### Speed and 50 uploads at once (2026-10-31)

Measured on the 991 uploads of 2026-10-29 - 31 (5 workers): an upload that the standard extraction stored ran about
60 s - 11.7 model calls took 41 s, the rest mostly the derived-data refresh: `V1.0.6` over every company (10.8 s with
361 companies and 1.06 million prices, one run at a time for all uploads) - and waited 615 s on average in the queue;
30% went to the full-text extraction (by design for the by-nature, Financing and Insurance templates; the rest
filings that fail a check, mostly the workbooks' own errors - `ingestion_error`).

| Change | Where | Effect |
|---|---|---|
| **Fast path**: a filing whose every column maps without a failed check or an unclassified income line, in a template the rule-based extraction reads, is stored without the agent - the default plan's steps through the agent's own tools (`DeterministicFinisher`), one model call for the English names of the company's new revenue segments (`Prompts.SEGMENT_NAMER`, stage `SEGMENT_NAMES`). The deterministic verification decides; anything open goes to the agent, then the full-text extraction, as before | `IngestionAgent.fastPath` / `mapsCleanly`, `neracalab.ingestion.fast-path` | 85% of the 1,766 workbooks qualify; such an upload takes about 2 s alone (16.7 s median with 29 at once) and no planner / executor / reviewer call |
| **Derived data of the uploaded company only**: `IngestionRepository.refreshDerivedData(companyId)` = the share scripts, then `ValuationRepository.refreshAfterFiling` (V1.0.6 for one company: market and valuation snapshots, cleared trailing ratios, fundamental and valuation metrics); the whole script still runs at start | `IngestionTools`, `IngestionAgent`, `FullTextExtractor`, `IngestionService` | 0.36 s per company instead of 10.8 s, beside other companies' refreshes. `DerivedDataRefreshTest.theRefreshOfOneCompanyLeavesWhatTheScriptComputes` compares every derived row of all 361 companies with the script's |
| **History revalued**: V1.0.6 also revalues the existing valuation dates (each day's latest-trading-day row stays) | `V1.0.6__data_metrics_valuation.sql` | the test above found MSIN's valuations of 2026-10-09 .. 23 still on the 6,067,620,000 shares V1.0.44 corrected, GOTO's and DYAN's on an EBITDA since cleared |
| **Upload workers of their own**: `neracalab.jobs.upload-workers` 50 (the price, RAG and screening queues keep `workers` 5); connection pool 40 (5 idle), PostgreSQL `max_connections` 200, heap 4 GB | `JobProperties.uploads()`, `application.yaml`, `docker-compose.yaml` | no queue wait |
| **Yahoo Finance share counts**: kept per company for 6 h, and asked `PacedHttpClient.ahead` of the waiting price requests (still one request at a time with the 1 - 2 s pause) | `WebShareCounts`, `PacedHttpClient` | 40% of the uploads asked Yahoo (three filings per company); in the first 50-upload test they waited up to 53 s behind the price jobs the uploads queue |

Load tests on the live backend (FY2025 workbooks of 50 different companies, every one already stored, so the stored
data must not change): 50 of 50 SUCCEEDED in 105 s (40 fast path, median 2.0 s; 9 full-text by design, 1 agent);
then 49 of 49 in 129 s (41 fast path, 8 full-text; 29 running at once, 18 database connections; 67 model calls, none
failed) with ANJT's USD filing refused (422) as intended. The fast path's current-period statements were written
with no difference from the stored ones (120 of 120); the comparative columns kept the stored figures as the rules
require. With 5 workers and the agent the same 50 would have taken about 10 minutes plus the queue.

`ingest.py upload --parallel` (skill `fix-xlsx-ingestion-api`) submits with 16 threads, one company's files in order
by one thread, reports a refused file and goes on, and prints a summary (wall time, run median / max, fast path /
full-text, model calls).

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

### The review of 2026-10-31 (3) (errors 1553 - 1627): units and share counts proven by the stored data, comparative breakdowns

Hermes' batch of 2026-10-31 (1,343 files SUCCEEDED, 26 INCOMPLETE, every period stored with an income statement): 75
open rows. The fixes below were uploaded again on the new backend (48 files), the rest is the filings' (dev_context 189 - 201).

| Cause | Rows | Fix |
|---|---|---|
| **Amounts in millions under a "Full Amount" declaration.** IMJS FY2024: total assets 29,410,622, prior year end 28,711,560 (FY2023 stored 28,711,561,013,612), profit to parent 228,477 / EPS 26.4 = 8,654 shares as filed. FY2024 was stored a million times too small - the only period in the database 1e3 / 1e6 off its neighbours. | 1607 | `IngestionService.provenUnit`: when the EPS contradicts the declared rounding but is plausible in unit x 1e3 / 1e6 / 1e9, and the filing's prior year end total assets are the stored total assets of that date (rounded to that unit, within one filed unit), the workbook is read in that unit (`FilingMapper(workbook, provenUnit)`). Test `IngestionServiceUnitTest`. |
| **A refused comparative breakdown left the upload INCOMPLETE** (BUKK, CASS, DKHH, FWCT, GEMA: lines filed twice or above the revenue). | 1553, 1555, 1557, 1562, 1575, 1589, 1590 | The user's decision: `IngestionVerifier` makes it a note also where no breakdown of the period is stored (the period's own filing stores one). `IngestionVerifierSegmentsTest`. |
| **An EPS cell holding the profit** (FLMC FY2022 - FY2025: basic EPS -22,991,911,126 = the profit) gave 1 share outstanding at every year end. | 1585 - 1588 | `FilingMapper.sharesFromEps` rejects a count of 1 (CBPE FY2021's real 35,000 before its IPO stays) and a count above the rupiah share capital (par under Rp 1: CCSI 9M 2026, EPS in thousands, 1,304,109,585,324 shares). `FilingMapperEpsCellTest`. |
| **A par value inferred from an EPS filed in another scale** (found by `valuation_check.py`): IPCC 9M 2026 par Rp 1 (181,838,482,000 at 2025-12-31 beside its FY2025's 1,818,384,820), GLVA H1 2026 par 500, KSIX and LABA 9M 2026, KAEF FY2025 par 50, JGLE FY2025 par 10; CFIN / CCSI from the EPS. Where the *stored* history was the wrong one - KOIN (980,843,732 = share capital Rp 98,084 million at par Rp 100, KSEI; stored 9,808,400,000 and a false reverse split 100:1 the split detection recorded), HILL (14,741,500,000 at par Rp 20, KSEI; stored 29,483,000,000) - and FLMC without any count: `V1.0.45__data_KOIN_HILL_shares.sql` (FLMC 781,250,000 = Rp 62,500,000,000 at par Rp 80). | - | `IngestionService.dropContradictedShareCounts`: a filing's counts are not stored when one of its comparative dates is more than 10% from the stored count of that date and no split is recorded in between; the published counts (checked against the EPS) are tried instead (HILL H1 2026: 14,741,500,000). 25 stored snapshots cleared by SQL (guarded on the exact values), the filings uploaded again; V1.0.45 in `IngestionRepository.SHARE_SCRIPTS`. Tests `IngestionServiceUnitTest`, `KoinHillSharesTest`. |
| **Cash with bank balances one filed unit off** (HDFA H1 2026: 5,013 + 56,101,280 = 56,106,293 thousand beside 56,106,292). | 1597 | `FilingMapper.cashWithBankBalances`: exact first, then within one unit. |
| **A cell's binary fraction** (LAJU FY2022: ending cash 3.7643576861000004E9, stored 3,764,357,686.1000) failed the overdraft explanation's filed = stored test. | 1622 | `IngestionVerifier.overdraftExplanation` compares at the stored scale. `IngestionVerifierOverdraftTest`. |
| **A Sharia insurer's tabarru funds** (CASA FY2025: 24,667,114 + 2,207,986 + 24,781 + 10,401,954 = 37,301,835 million) left out of the bank balance check: every CASA filing went to the full-text extraction. | 1558 - 1572 | `FilingMapper.financialBalanceSheet` stores them with the syirkah funds when the balance then holds and not without (ARTO FY2022's syirkah total on that row stays). `FilingMapperTabarruTest`. |
| **A gross profit of cost rows alone** (CASA FY2023 files no revenue rows: -5,416,776 million). | (income check) | `FullTextExtractor`: not stored when there is no revenue and every gross profit term is subtracted; a filed negative 'Total gross profit' row (GTBO H1 2026) stays. `FullTextExtractorTest`. |
| **The filings'**: workbooks without statements (CENT, GRIA FY2023, IBOS FY2024; GRPH FY2023 empty column), DNAR FY2023 without a cash flow, cash flows contradicting the balance sheet (GSMF 9M 2026, IBOS H1 2026, KIAS FY2022, KINO FY2025, IMJS), restated comparatives and slips (CITA, IDEA, LFLO, HAJJ / IKAN ending cash without its minus sign), EPS cells in another scale (DLTA, JRPT, KARW, KING), and the remaining SUCCEEDED rows the full-text extraction stored (restated comparative columns, empty profit before tax cells, no revenue in the period). | the rest | Not changed. USD filings of ESTI, KOBX, MBSS refused by design (their later filings restate them in IDR; MBSS FY2023 waits for the FY2024 workbook). LAPD's tiny FY2021 - 2022 figures are real. |

After the uploads: 47 of 48 SUCCEEDED (IMJS FY2024 INCOMPLETE on its 300 million cash gap only), all 87 rows closed;
`valuation_check.py` 170 -> 165 flags, every clean-factor share count gone (open: ELTY, GIAA, CMNP, LPPF, FORU, GMFI; HILL
2023-12-31 before its likely split). `DerivedDataRefreshTest`: the whole V1.0.6 takes 17.6 s with 625 companies (10.8 s at ~360), its limit is 30 s; the
per-company refresh needed a `VACUUM ANALYZE` after the day's uploads (2.3 s -> under 1 s a company).

### The review of 2026-10-31 (2) (errors 1468 - 1512): restated breakdowns, one unit in full amounts, share counts

Hermes' B-tickers (BHIT, BOSS, BRAM, BMBL, BKSW, BOLA, BELL, BSIM, BUDI, BMHS, BUAH): 16 rows, nine INCOMPLETE. Every
file was uploaded again on the backend of the morning's fixes first (rows 1496 - 1512), then the affected ones after
the fixes below. 34 rows closed, none open.

| Cause | Rows | Fix |
|---|---|---|
| **A later filing's restated breakdown beside the period's own revenue.** BHIT's FY2025 filing restates FY2024 revenue 15,226,314 million as 15,139,665 and splits the restated figure into ten lines; BMHS's FY2024 filing restates FY2023 1,287,690,839,110 as 1,488,197,483,371 (cost alike, gross profit unchanged). Uploaded before the earlier filing (Hermes uploads newest first), the comparative filled the empty breakdown and stayed beside the own filing's revenue (segment revenue != revenue). | 1471, 1480, 1496, 1505, 1506 | `IngestionTools.saveRevenueSegments`: a comparative breakdown that does not add up to the stored revenue (within `BREAKDOWN_SLACK` 2,000,000: a breakdown in millions beside a full-rupiah revenue, INPP FY2024 1,309,662 million vs 1,309,661,867,481, stays) is kept out (`KEPT_OUT`); `saveStatements` of a period's own filing removes a stored breakdown of another revenue (`removeBreakdownOfAnotherRevenue`, `IngestionRepository.removeSegmentRevenue`). Only these two periods in the database. Test `IngestionToolsSegmentsTest`. |
| **Ending cash one filed unit from the balance sheet in a full-amount filing** (BRAM H1 2026, full USD: 21,668,146 + 2,721,874 - 22,173 = 24,367,847 beside 24,367,846; BMBL FY2022, full rupiah: 479,564,255 beside 479,564,254). Too small a balance for the billionth of `immaterialExplanation`. | 1472, 1482, 1497, 1498 | `IngestionVerifier.roundingExplanation`: one unit is rounding in every filing (before: only thousands / millions), as the mapper's full-amount roll-forward accepts (ALMI). Two units stay a problem (BMBL FY2023 / FY2024, MGLV FY2023, WINE FY2023). |
| **The filings'** (dev_context, tag `xlsx-error`, 151 - 159): BOLA FY2022 cash flow without the 47,750,000,000 'other increase' its FY2023 filing restates (151); BELL FY2023 without -2,231,893,807 (152); BMBL's 2-rupiah carried slip and a FY2023 face row missing (153); BHIT's and BKSW's restated FY2024 investing sections (154, 155); BSIM's 9M 2025 collateral recovery counted twice (156); BUDI's FY2024 1,052 million on two rows (157); BUAH FY2023 'Other gains (losses)' with the wrong sign (158); BOLA 9M 2026 profit before tax cell empty (159). | the rest | Not changed: the full-text extraction stored the filed figures (an amount repeated on two rows is the workbook's, not bent into `doubleReportedLines`). BOSS FY2024 has no revenue (the company). |

Valuations (`valuation_check.py`, 84 flags): BMBL and BOAT had prices but no share count (`V1.0.43__data_BMBL_BOAT_shares.sql`:
BMBL 1,030,080,995 = share capital Rp 41,203,239,800 at par Rp 40, not added to `STANDARD_PAR_VALUES` because Rp 40 makes
DEFI FY2025's Rp 50 ambiguous; BOAT 3,501,680,000 from its IPO of 2024-11-12). Wrong or missing counts
(`V1.0.44__data_MSIN_RMKE_SMGA_WIFI_INET_PACK_shares.sql`, in `IngestionRepository.SHARE_SCRIPTS`): MSIN's H1 2026 filing
files its EPS ten times too large, so the par inference took Rp 100 (6,067,620,000 for 60,676,178,205; P/E 0.63 -> 6.26);
SMGA's par value Rp 20 (8,750,000,000; the inference took Rp 10 and stored twice the count); RMKE's split 1:5 of
2026-07-17 (21,875,000,000, earlier counts and EPS split-adjusted like the prices); the counts after the share issues of
WIFI (5,308,549,015), INET (22,374,111,088) and PACK (32,379,253,695, its 2026 rights issue of convertible bonds - not a
split) from their share capital at the par value of their earlier filings. Real, not errors: treasury shares (MNCN,
DSSA, UNTR, DGIK, GOTO ...), KRAS's P/E 0.39 (FY2025 profit USD 325 million), BHAT's 43,388 (FY2024 profit Rp 204 million).
Open: FORU (rights issue of September 2026, final count not published), GMFI (rights issue II, counts disagree), BIMA
(KSEI 608.2 million vs EPS 578.6 / 289.4 million), BNBR's P/E basis (a decision). EBITDA not derivable: ARKO, BINO, BMBL
(intangibles), BATA (no right-of-use depreciation), BAPI (no D&A). Income: BSML's FY2025 workbook missing; BTEL files by
nature with an empty cost of sales note.

### The review of 2026-10-31 (errors 1354 - 1423): filings of one company at once, rounding units, insurers' deposits

Hermes uploaded about 300 filings of 60 companies, several years of one company two seconds apart.

| Cause | Rows | Fix |
|---|---|---|
| **Two currencies in one company.** Filings of one company ran at the same time (`same-company-in-order` was off), so each passed the currency check (`IngestionService.currencyConflict`, when the job opens the workbook) before any of them was stored: ANJT's FY2025 filing in USD stored FY2024 / FY2025 in dollars (revenue 236,598,588) beside H1 / 9M 2026 in rupiah (2,345,605,324,970), its FY2022 - FY2024 filings were refused; DEWA's FY2022 filing in USD stored FY2021 / FY2022 beside FY2023 on in rupiah (its FY2023 filing restates FY2022 in IDR). | 1398 - 1401 | `neracalab.ingestion.same-company-in-order` defaults to `true` (application.yaml, docker-compose, `FinancialStatementQueue`): filings of one company one after the other in upload order, so a filing in another currency is checked against every filing of the company stored before it. ANJT FY2024 / FY2025 and DEWA FY2021 / FY2022 deleted; the IDR filings uploaded again (ANJT's year end 2025 from its 2026 filings, DEWA FY2022 from its FY2023 filing's comparative). ANJT FY2025's income in IDR comes with its FY2026 annual filing. |
| **Ending cash one rounding unit from the balance sheet** (BATA H1 2026: 26,809,539 thousand beside 26,809,538; BBLD FY2023, FY2024, H1 2026: one million). | 1414, 1416 - 1418 | `IngestionVerifier.roundingExplanation`: at most one unit is a note, as the mapper's checks accept (before: less than one unit). BBLD FY2022's 69 million (514,065 / 514,134) stays a problem: the filing (dev_context 107). |
| **An insurer's cash flow counts its short time deposits as cash** (AMAG FY2022 - 9M 2026: FY2025 ending cash 485,425,155 thousand = cash 64,021,213 + 421,403,942, within time deposits 651,473,868; ASDM FY2025 / 9M 2026). | 1354, 1356 - 1360, 1407, 1409 | `FilingMapper.currentFinancialAssetsOutsideCash`: an insurer's 'Investments in time deposits' (its balance sheet is not split into current and non-current); `IngestionVerifier.overdraftExplanation` checks the deposits without needing a short-term debt row. Test `IngestionVerifierInsurerDepositsTest`. |
| **A full-amount roll-forward one rupiah off** (ALMI FY2024: 507,776 - 480,438 = 27,338, ending cash filed 27,337; ARTI FY2023, ASHA FY2025). The full-text extraction stored them. | 1355, 1408, 1410 | `FilingMapper.cashFlow`: the final roll-forward check accepts one unit as the filing's rounding (a warning). Only there: in `equal` a unit apart still tells two candidate lines apart (ENRG's double-reported other income). Test `FilingMapperCashRoundingTest`. |
| **A cash flow section whose only line is filed as 0** (AMMS H1 2026: receipts from stockholders 0, no investing total, no financing section; operating -44,107,270 is the net change). | 1371 | `FilingMapper.sectionHasNoLines`: a line of 0 is no line; the section counts as 0 when the other totals give the net change. |
| **Amortization of a business that left the group** (PTRO H1 2026: intangibles 134,250 -> 60,088 thousand USD and goodwill 21,395 -> 2,408, so opening + purchases - closing = 78,212 while its cost of sales note states 6,075; CUAN H1 2026 152,773 -> 72,543). EBITDA was overstated. | - | `FilingMapper.amortization`: none when the roll is positive and the goodwill fell by half or more (goodwill is not amortized: a business left the group or was written off); a smaller fall (exchange rates, TOBA H1 2026, TLKM) leaves the roll. 14 of the 2021 - 2026 workbooks: besides PTRO and CUAN H1 2026 the roll had given ADRO FY2024 (the AADI spin-off), DGWG FY2025, DYAN FY2025, GOTO FY2023 - FY2025 (goodwill impairments) and MPMX FY2022 an amortization. An upload keeps a stored value the new mapping leaves empty, so their amortization and EBITDA were cleared by SQL and the filings uploaded again. Test `FilingMapperAmortizationTest`. |
| **Stale valuations after periods were removed.** ANJT's 2026-06-30 and 2026-09-30 valuations kept a P/E of 21.6 / 12.0 and an EV / EBITDA of -407,653 / -491,668 from the deleted USD FY2025: a period end without trailing twelve months is no valuation date of `V1.0.6` any more, so nothing refreshed the row (`DerivedDataRefreshTest` found it). | - | `V1.0.6__data_metrics_valuation.sql`: such a row's trailing ratios are cleared (market cap, EV, book value and P/B stay). It reached exactly those two rows. |
| **Prior-period breakdown pending while the period's own filing ran beside it** (BEEF FY2024). | 1422 | Uploaded again in order: SUCCEEDED. |
| **The filings'** (dev_context, tag `xlsx-error`): AYLS H1 2026 cash 9,712,274 apart (106), BBLD FY2022 (107), ASMI's restated FY2024 column (108), ATLA H1 2025 breakdown (109), AMAR FY2023 roll-forward missing 25,986 million (110), APII FY2024 / FY2025 (111), ARKO H1 2026 (112), ARTA FY2024 investing (113), BBSI one impairment twice (114), ANJT H1 2025 pretax 460,442 apart (115), BEKS FY2022 two units (116), BBHI FY2024 without a cash flow (117). Slow standard extractions of AMMS (1373, 1374): the queue was full. | the rest | Not changed: the full-text extraction stored the filed figures. |

Valuations: ALKA, AMAN, AREA, ATAP and BATR had prices but no share count (`V1.0.41__data_ALKA_AMAN_AREA_ATAP_BATR_shares.sql`: ALKA 507,665,055; AMAN 3,873,500,000 = share capital at par Rp 100; AREA 2,029,601,000 founders' shares, 2,539,601,000 from its IPO of 2024-04-01; ATAP 1,250,000,000 = share capital at par Rp 40; BATR 3,025,000,000 from its IPO of 2024-06-10 - each within KSEI's 1% lists). BBHI's H1 2026 filing stored half its count at 2025-06-30 (10,865,128,221): the bank files its interim EPS annualized and the par value inference fitted Rp 200 instead of Rp 100 (`V1.0.42__data_BBHI_shares.sql`, run after every upload; the inference itself is an open issue, dev_context 122). New companies without EV / EBITDA by the filings: ARKO (intangibles grew), BAPI and BATA FY2025 (no depreciation). Gross profit: AMOR, APIC and ARGO (from FY2025) file by nature with empty cost of sales notes - not derivable.

### Revenue, gross profit and net income of every company (2026-10-30)

A check of all 250 companies. Net income is in every period with an income statement; revenue too, except PEGE (no
revenue filed, see below). Gross profit:

| Gap | Fix |
|---|---|
| **Insurers and financing companies without gross profit in some periods** (TUGU FY2021, FY2024, H1 2025, FY2025, H1 2026; PNLF H1 2025, H1 2026; FUJI FY2023): revenue and cost of revenue stored, the model left gross profit out. | `FullTextExtractor`: an insurer's or financing company's gross profit is revenue - cost of revenue (revenue where there is no cost), as the field defines it. Test: `anInsurerIsMappedWithItsTabarruFundsAndItsShareCapital`. |
| **PGEO FY2021 - FY2023** (by nature): its cost of sales note (3670000) states the cost (FY2023 178,977 thousand), but the filings were stored before the note was used (2026-10-26). Uploaded again, FY2023 still had none: the model named 'Exploration expenses' (the note's 178,977) as cost of revenue and only 'Other expenses' 5,526 as operating expenses, which cannot hold the cost. | `costOfSalesFromTheNote`: where the rows the model names as cost of revenue - and not among the operating expenses - are the note's cost, the note gives the gross profit and the operating expenses stay as mapped (test `theModelsOwnCostRowsAreTheNotesCost`). FY2023's face has no revenue at all (406,288,000 only in its breakdown by party; the stored revenue is the FY2024 filing's comparative): the cost is still stored and taken out of the operating expenses (184,503,000 -> 5,526,000), and a gross profit another filing stored is kept instead of cleared (test `withoutRevenueTheNotesCostIsStoredAndAGrossProfitKept`). The FY2024 filing, uploaded again, filled FY2023's gross profit from its note's prior column (227,311,000) and gave FY2024 its own cost (164,885,000; it had the FY2025 comparative's 166,721,000). |
| **BUKA FY2025** (by nature): the note's cost 5,990,751,222 thousand is more than the operating expenses by nature, 4,432,364,338, because a gain of 1,988,280,296 is filed as negative 'Other expenses'. | `costOfSalesFromTheNote`: the positive expense rows must hold the cost (6,420.7 billion do); the operating expenses without it are negative, as filed. Gross profit 519,279,231 thousand. Test `theCostOfSalesIsHeldByThePositiveExpenseRows`. |
| **No gross profit in the filings**: BREN, EXCL, GMFI, ISAT, TLKM (by nature, cost of sales notes empty or 0 in every filing), MNCN FY2021 - FY2023 and BMTR FY2021 - FY2022 (notes empty). | None: not derivable without making a cost of sales of expense rows by nature. |
| **FY2025 without an income statement**: ASLC, ADES, ALII (their FY2025 annual workbooks are not in `data/IDX_XBRL`; the year end is only the balance sheet comparative of a 2026 filing), BACH and RANS (listed in 2026: no FY2025 annual filing). | ASLC, ADES, ALII: download `FinancialStatement-2025-Tahunan-<TICKER>.xlsx` from IDX and upload it (IDX refuses a script with HTTP 403). |

### Valuation gaps of 2026-10-30 (2): ACRO, AGAR, AISA, EBITDA without amortization, capex without a row

A check of every company's latest valuation. By design without a value: EV and the EV ratios of banks, insurers and
financing companies; P/E, P/S and EV / EBITDA of BACH and RANS (no trailing twelve months yet); P/S of PEGE (no revenue).
The rest:

| Gap | Fix |
|---|---|
| **ACRO, AGAR, AISA: no valuation at all.** Prices but no share count: none of their filings states one and no Yahoo Finance count fits (AISA's is 2.45 times smaller than its EPS gives; AGAR's filed EPS fits no count). | `V1.0.39__data_ACRO_AGAR_AISA_shares.sql` from KSEI's 1% lists (a holder's shares / its percentage) and the listing records: ACRO 3,469,138,591 from its IPO of 2024-01-11 (KSEI 3,469.26 - 3,469.70 million in 2026: Series I warrants exercised since, at most 0.016%), AGAR 1,000,000,000 (KSEI exactly), AISA 9,311,800,000 (KSEI 9,311.2 - 9,312.6 million; every EPS 9.28 - 9.31 billion). |
| **No EV / EBITDA for 17 non-financial companies.** The latest (H1 2026) valuation takes FY2025's EBITDA when the trailing one is not derivable, and FY2025 had none: amortization = intangibles opening + purchases - closing is not derivable where the intangibles grew by more than the cash paid for them (KETR, BNBR, CUAN, DSNG, GGRM, HATM, IMPC, IRSX, PTRO, TOBA, WIRG ...) or were sold (MIKA), and none of these workbooks states its D&A. | `FilingMapper.immaterialIntangibles`: EBITDA = EBIT + depreciation when the intangible assets, opening and closing, are under 1% of it (a year's amortization cannot exceed them): BNBR, DSNG, HATM, KETR, WINS FY2025 and AKPI, ANTM, INET, KIJA, PGAS, WIFI, WIIM, HATM, KETR in earlier filings. Where intangibles matter (GGRM 14.9 trillion, WIRG, IMPC, MIKA, IRSX, CUAN, PTRO, TOBA) EBITDA stays empty: not derivable from the filing. EMTK and JPFA FY2025 leave the right-of-use roll-forward empty with right-of-use assets of 249,586 and 216,025 million a year before, ADCP and MGLV their PP&E roll-forward: no depreciation, no EBITDA. Test `FilingMapperImmaterialIntangiblesTest`. |
| **FUJI, ADCP: no FCF yield.** The full-text extraction left capital expenditure empty where the cash flow has no such row (FUJI, a financing company, has no investing section; ADCP's 9M 2025 invests only in land for development, a joint venture and restricted funds), so the trailing free cash flow was unknown. | `FilingMapper.reportsCapitalExpenditure`: no capital expenditure row with an amount in the column -> 0, as the rule-based extraction stores it. Test `FilingMapperCapexTest`. |

### The review of 2026-10-30 (errors 1233 - 1256): financing companies' cash, PSAK 117 comparatives, PEGE, ABDA

New uploads of SMMA, PEGE, DEFI, ABDA, ADCP, ADMF, AGRS and AGRO.

| Cause | Rows | Fix |
|---|---|---|
| **A financing company's cash without its bank balances.** In the Financing Industry taxonomy (8220000) ADMF's and DEFI's row 'Cash and cash equivalents' holds the cash alone; the current accounts and placements with banks are rows of their own, and the cash flow statement counts them in cash: ADMF FY2025 77,878 + 176,714 + 566,324 = 820,916 million, the ending cash; DEFI FY2023 8,801,000 + 4,487,369,146 + 8,500,000,000 = 12,996,170,146. | 1243, 1249 - 1254 | `FilingMapper.cashWithBankBalances`: where the ending cash is exactly the row plus some of the 'Current accounts with ...' / 'Placements with ...' rows, the full-text extraction stores the cash flow's cash and cash equivalents, as for a bank. Every ADMF filing and DEFI FY2021 and FY2023 match; BFIN and FUJI (the row is the ending cash already) and SMMA do not. Test `FilingMapperFinancingCashTest`. |
| **DEFI FY2022:** its cash flow ends at 15,857,547,033, 501,250,000 below cash + current accounts + placements; the FY2023 filing restates the FY2022 cash flow to 16,358,797,033. | 1244 | Not changed (the filing): stored as filed. |
| **SMMA**, a financial conglomerate: its ending cash (FY2025 22,053,772 million) is more than cash 9,180,682 + placements 8,655,785; no combination of up to three asset rows makes up the rest in FY2025 or FY2024, so the balance sheet does not show which securities the cash flow counts as cash equivalents. | 1233 - 1237 | Not changed (the filing): the balance sheet's row is stored. |
| **Cells of the former presentation in an insurer's restated comparative.** ABDA applies PSAK 117 from 2025; its H1 2025 column (H1 2026 filing) and FY2024 column (FY2025 filing) keep 'Decrease (increase) in unearned premiums', 'premium income ceded', 'estimated claims liability' and 'Commission expenses', which the current column leaves empty and the restated profit before tax does not count (H1 2025: -2,274,276 against 86,606,060; FY2024: the four make up exactly 165,459,501). | 1246, 1247 | `FullTextExtractor.staleHint`: the model is shown that the comparative reconciles exactly without the rows filled in it alone; when it keeps them, they are left out (`without`). ABDA H1 2025 revenue 567,037,027 and cost of revenue 449,006,351 thousand. Test `rowsOfAnInsurersComparativeAloneAreLeftOutWhenOnlySoItReconciles`. FY2024 is stored from its own filing. |
| **ABDA FY2021 / FY2022: G&A tagged as interest.** The FY2022 filing tags its general and administrative expenses 'Interest and finance costs' (328,420,609 and 284,745,743 thousand); the FY2023 filing files 328,420,609 as G&A. Operating income came to 459,445,724 and 434,411,704 for profits before tax of 174,699,981 and 105,991,095. | - | `V1.0.38__data_ABDA_expenses.sql` (only while the rows hold the tag's figures): operating expenses, no interest, operating income = profit before tax. Test `AbdaExpensesTest`. |
| **PEGE: a revenue made of finance and other income.** PEGE, an investment company, files no revenue: G&A, finance income (its investment results, often negative), other income, other expenses. The model made a different revenue each year (FY2021 -83,563,969,959, FY2022 -94,297,438,110: finance + other income; FY2023 80,992,772,897 and FY2024 28,879,851,458: other income - finance income; FY2025 finance income 19,282,262,084); interim periods had none. | 1238 - 1242, 1245 | A revenue made only of 'Finance income', 'Other income', 'Other gains (losses)' and 'Dividends income' is not stored and a stored one is cleared (`NOT_REVENUE`); operating expenses and income stay (other income - G&A - other expenses in every period). `FilingMapper.reportsNoRevenue` names such a profit or loss (PEGE in every filing; ESSA, PANI FY2023 start at gross profit and PGEO FY2023 keeps revenue in a note, so a missing row alone is no proof). Test `aRevenueMadeOfFinanceAndOtherIncomeIsNotStored`. Uploaded again, the model left FY2023 and FY2024 revenue empty, which keeps a stored value: those two (80,992,772,897 and 28,879,851,458, the earlier uploads') were cleared in the database once; PEGE has no revenue in any period now. |
| **A slip of two units in thousands.** AGRO FY2022 (a bank): 2,140,267,174 - 9,894,016 = 2,130,373,158, ending cash filed 2,130,373,160. | 1256 | `FilingMapper.equal`: a filing in thousands or millions is off by up to one unit, else, like a full-amount filing, by under a billionth of the amount (a warning; the reported figure is stored). |
| **AGRS H1 2025** (a bank) files one impairment of 9,913 million twice ('Recovery of impairment loss' -9,913 and 'Allowances for impairment losses' 9,913); its profit from operation counts it once. **ADCP 9M 2025** repeats amounts on unrelated financing rows (108,056,829,048 as non-bank loans and as due to related parties); its totals are its own. | 1248, 1255 | Not changed (the filings): the full-text extraction stored the filed totals, `SUCCEEDED`. |

### The review of 2026-10-30 (errors 1177 - 1199): out of credit, two jobs of one company, MNCN's breakdown

The filings by nature of BUVA, MNCN, NICL, NSSS, BUKA and MSIN (and RAFI FY2022) were uploaded again to take their
gross profit from the cost of sales note, 31 at once. Three causes:

| Cause | Rows | Fix |
|---|---|---|
| **OpenRouter out of credit.** 402 "This request would exceed your available credits given your current in-flight requests" (USD 0.85 left, 31 full-text extractions reserving at once); nothing was extracted, the stored data unchanged. | 1177 - 1179, 1181 - 1198 | Uploaded again one at a time after the credit was topped up, the last ones on Claude Haiku 5.5 through OpenCode Go: all `SUCCEEDED`. |
| **Two filings of one company at the same time.** MNCN FY2024's own cost of sales note is empty, so its job cleared cost of revenue and gross profit; the FY2025 job, running beside it, filled FY2024's from its comparative note (3,929,468 / 4,024,344 million) before the FY2024 job verified ("stored ... but the filing says null"). | 1180 | Not a data error: the stored figures are right; uploaded again alone, `SUCCEEDED`. `INGESTION_SAME_COMPANY_IN_ORDER=true` avoids it. |
| **A breakdown the filing gets wrong.** MNCN H1 2026, the H1 2025 comparative of note 1617000: 'Service revenue 1' 4,070,760 million (the revenue) and lines 2 - 6 add up to 2,687,578; no reading gives the revenue. | 1177, 1199 | Not changed (the filing): the breakdown is left open, the statements stored. |

**A cost a fraction above the expenses** (RAFI FY2021, in its FY2022 filing with fractions of a rupiah): the note's
cost 89,901,586,207 is 0.36 above the expenses 89,901,586,206.6389 it is one of, so the cost of sales note was
refused and the year had no gross profit. `FullTextExtractor.costOfSalesFromTheNote` takes a cost less than a filed
unit above the expenses as the same amount (operating expenses then 0): gross profit 27,211,617,032. Test
`FullTextExtractorTest.aCostOfSalesAFractionAboveTheExpensesIsTheSameAmount`.

Gross profit afterwards: BUVA, MSIN, NICL, NSSS and RAFI in every period. Left empty: MNCN FY2021 - FY2023 (the
cost of sales notes of the FY2022 - FY2024 filings are empty) and BUKA FY2025, whose note's cost 5,990,751,222
thousand is above its expenses by nature (4,432,364,338: 'Other expenses' is a gain of -1,988,280,296), so the
operating expenses would turn negative.

### Valuation gaps of 2026-10-29: EBITDA of the interim comparative, POLY, RANS

Companies › Fundamentals had no EV / EBITDA in the latest valuation of 191 of 198 companies (20 financials, none by
design): the trailing twelve months of H1 2026 (H1 2026 + FY2025 - H1 2025) need H1 2025's depreciation, and an H1
2026 filing gives it only when its profit or loss by nature states it (the roll-forward notes cover the current period
only); only HRTA and INDF had their own H1 2025 filing uploaded.

| Cause | Fix |
|---|---|
| **The comparative's depreciation.** 180 of the 194 H1 2026 workbooks give H1 2025 no depreciation anywhere; 11 companies' profits or losses by nature state "Depreciation and amortisation expenses" in both columns (BACH, BIPI, BREN, BUKA, EXCL, GMFI, ISAT, MNCN, MSIN, NSSS, TLKM), while the current column took the roll-forward's additions to accumulated depreciation, which differ (TLKM H1 2026 14,370 billion beside the 17,203 stated; NSSS 32,899 million beside 80,672), so a trailing sum mixed the two. | `FilingMapper.statedDepreciationAndAmortization`: EBITDA is EBIT plus the stated figure in every column (rule-based and full-text extraction alike). Their filings must be uploaded again to store it. The 3670000 cost of sales note's depreciation (BIRD, JSMR, TOWR) is only the part in cost of sales: not used. |
| **No trailing EBITDA at all.** | `valuation_snapshot.ebitda_basis` (V1.0.36): an interim period's EV / EBITDA takes the full year inside its trailing window (FY 2025 for H1 2026) when its TTM EBITDA is not derivable, marked "(FY 2025)" on the company page; the exact TTM replaces it once the H1 2025 filing is uploaded. 163 of 198 latest valuations have EV / EBITDA now (7 TTM, 156 FY 2025); 19 financials have none by design; 15 companies' FY2025 has depreciation without amortization or the reverse (intangibles grew more than their purchases, or were sold), so no EBITDA is derivable; BACH has no full year yet. |
| **A filing under another company's name.** `data/IDX_XBRL/SGER/xlsx/FinancialStatement-2023-Tahunan-SGER.xlsx` was Asia Pacific Fibers' (POLY) Q3 2023 filing; the upload rightly stored it under POLY (the entity code), which created a company with 2022 - 9M 2023 figures and no share count. | POLY deleted; the file moved to `data/IDX_XBRL/POLY/xlsx/FinancialStatement-2023-III-POLY.xlsx`. SGER is complete without it (FY2023 from the FY2024 filing's comparative). |
| **A new listing without a share count.** RANS listed on 2026-07-10; its H1 2026 filing gives none and Yahoo Finance's fits none of its EPS. | `V1.0.37__data_RANS_shares.sql`: 10,084,250,000 founders' shares, 12,609,250,000 after the offering (IDX's listing announcement; KSEI's lists bound it to 12,608.2 - 12,610.3 million). Market cap, EV and P/B; no P/E until its first full year, as BACH. |

Tests: `FilingMapperStatedDaTest`, `FullTextExtractorDeriveTest`, `ValuationEbitdaBasisTest`, `RansSharesTest`.

### Valuation gaps of 2026-10-30: RAFI, and the companies without a full valuation

Of 222 companies, RAFI alone had no valuation at all. Three more have a latest valuation with gaps, by design:

| Ticker | Cause | Fix |
|---|---|---|
| **RAFI** | Its par value is Rp 15 (common stocks 46,921,350,000 = 3,128,090,000 shares after the IPO of 2022-07-28; FY2023 EPS 127,469,201,017 / 3,128,090,000 = 40.75), which is not in `FilingMapper.STANDARD_PAR_VALUES`, and Yahoo Finance has no count: no share counts, no market cap. | 15 added; `inferParValue` skips a par that does not divide the share capital (1 / 15 has no exact decimal quotient: `RoundingMode.UNNECESSARY` threw on every other workbook), `sharesAtPar` likewise. A sweep of all 1,048 workbooks in `data/IDX_XBRL`: only RAFI FY2023 / FY2024 (2,180,000,000 at 2021-12-31, 3,128,090,000 at 2022 - 2023, 3,128,140,475 at 2024-12-31) and IRSX's H1 2026 change (below). RAFI's FY2023 and FY2024 filings uploaded again (2026-10-30, full-text extraction, `SUCCEEDED`): four share counts, a market cap and valuations from FY2022 (latest on the last close, 2026-02-13: P/B 0.41, P/E -18.2 on the FY2024 loss). |
| IRSX | H1 2026 now resolves par 15 (92,925,710,655 = 6,195,047,377 shares, the count already stored; EPS 1.25 and -0.32 both fit), and carries 5,000,353,976 to 2024-12-31, where 6,892,377,912 is stored from another source. | Not uploaded again until the 2024 count is checked. |
| BACH, RANS | Listed in July 2026 with one filing (H1 2026): no trailing twelve months, so no P/E, P/S or EV / EBITDA until their FY2026 filings; market cap, EV and P/B are there. | None (by design). |
| INPS | Its latest valuation uses the close of 2026-09-17: IDX suspended its shares from 2026-09-18 (more than a year on the Special Monitoring Board). | None. |
| FORU | Not empty but wrong since 2026-09-14: the price falls from 3,720 to 238.41 with about 15 times the volume and a week of fractional (adjusted) closes, a corporate action (rights issue or split) that no source has recorded; the share count stays 465,224,000, so the market cap (Rp 40.9 billion on 2026-10-28) and P/E, P/B, EV are too low. | Open: the share count after the action, from its IDX announcement. |

Test: `FilingMapperRafiTest`.

### The review of 2026-10-30 (errors 1146 - 1154): AHAP, RAFI, BGTG, MAXI and UVCR

| Cause | Rows | Fix |
|---|---|---|
| **A cash flow section without rows.** AHAP, an insurer, has no financing activity in FY2024, FY2025, H1 2025 and H1 2026 (no lines, no total; FY2024: operating 1,356,295,483 + investing -580,969,819 = net change 775,325,664). The full-text extraction left `financing_cash_flow` empty and every upload ended `INCOMPLETE`. | 1146 - 1148 | `FullTextExtractor.emptySectionAsZero`: a section without rows is 0 when the other two add up to the change in cash (within two units), as `FilingMapper.sectionTotals` counts it for the rule-based extraction (CEKA). |
| **A revenue with a fraction of a rupiah.** RAFI FY2022 (full amounts) files revenue 375,886,948,578.9396 beside a breakdown of 375,886,948,579. `IngestionVerifier.segmentRoundingExplanation` only accepted such a gap in filings in thousands or millions. | 1149, 1150 | A gap under the rounding unit, at least 1, is a note for full amounts too (as `balanceRoundingExplanation`). |
| **An ending cash cell the filing contradicts.** UVCR 9M 2026: the sections add up to the net change 568,862,927, and 1,805,621,319 + 568,862,927 = 2,374,484,246, the balance-sheet cash. Only the ending cash cell says 2,824,484,246. | 1153 | `FilingMapper.correctEndingCash`: in a non-financial current period whose sections give the net change and whose roll-forward gives the balance-sheet cash exactly, the ending cash is the balance sheet's (a warning says so). Not where the roll-forward reaches the filed ending cash (overdrafts, deposits). |
| **Filings that contradict themselves.** BGTG's FY2025 filing restates FY2024 and still files non-operating income 617 million: it reconciles only without it (259,526 - 4,849 = 254,677). UVCR's H1 2025 comparative is 164,852,231 apart from its profit before tax; AHAP's FY2024 (2,673,553,844) and H1 2025 (14,746,914,718) comparatives too, with no line or sign to explain it. MAXI FY2023 rolls forward 180 rupiah above its ending cash, the balance-sheet cash; its FY2024 filing restates the net change 180 lower, so the slip is in one of the FY2023 lines. | 1147, 1148, 1151, 1152, 1154 | Not changed: nothing in the workbooks decides which figure is wrong. Stored by the full-text extraction with the filed totals; BGTG, MAXI and UVCR H1 2026 `SUCCEEDED`. |

Tests: `FilingMapperUvcrTest`, `FullTextExtractorTest.anEmptyCashFlowSectionIsZeroWhenTheOthersGiveTheNetChange`,
`IngestionVerifierRoundingTest.segmentRevenueWithinARupiahOfAFullAmountFilingIsANote`.

### The review of 2026-10-29 (errors 1067, 1068): MGLV and JARR

| Cause | Rows | Fix |
|---|---|---|
| **A 3 rupiah slip between the cash flow and the balance sheet.** MGLV's FY2024 filing (full rupiah) rolls its cash forward 6,824,569,377 + 959,414,303 = 7,783,983,680, every section adding up, beside a balance-sheet cash of 7,783,983,677; its FY2025 filing repeats the same pair as comparatives. The rules stored the filing's figures; the database verification compared exactly and ended the upload `INCOMPLETE`, and the full-text extraction took over. | 1067 | `IngestionVerifier.immaterialExplanation`: a gap under a billionth of the cash (`FilingMapper.FULL_AMOUNT_SLIP`, the slip the mapper already accepts in a roll-forward, WIFI FY2022) is a note, stored as filed. INDO FY2022's 20,000,000 (959) stays a problem. |
| **A workbook without statements.** JARR's FY2023 file (25,655 bytes) has only its general information (1000000): no balance sheet, profit or loss or cash flow. | 1068 | None: rightly reported. FY2023 is stored complete from the FY2024 filing's comparative. |

Test: `IngestionVerifierRoundingTest.anImmaterialCashGapOfTheFilingIsANote`.

### The review of 2026-10-23 (errors 877 - 880): WINE

| What was wrong | Rows | Fix |
|----------------|------|-----|
| **The full-text extraction's model refused every call.** Since the switch to Claude Sonnet 5.5 its requests carried the standard extraction's `"reasoning": {"enabled": false}` (`model-reasoning: false`), and Sonnet 5.5 refuses that: HTTP 400 "Reasoning is mandatory for this endpoint and cannot be disabled" (80 ms, no answer). WINE's FY2023 upload ended `INCOMPLETE` with nothing of FY2023 stored. The only such call in `llm_usage`. | 878 | `fallback.reasoning-effort` (`INGESTION_FALLBACK_REASONING_EFFORT`, default `low`): `FullTextExtractor.options` sends `"reasoning": {"effort": "low"}` in its place, the provider exclusions and the cost request kept; the standard extraction's DeepSeek calls still switch reasoning off. Uploaded again: three Sonnet calls (USD 0.0897), FY2023 stored (error 880). |
| **A filing that contradicts itself.** WINE's FY2023 cash flow (direct method) files no investing row and no investing total in the FY2023 column (FY2022: -3,267,185,857), and its financing lines add up to 29,103,486,541 beside the filed total 29,500,370,253 (difference 396,883,712). Operating -14,044,367,603 + financing 29,500,370,253 is not the net change 7,750,478,932 (beginning 9,166,930,763, ending 16,917,409,695), so investing would be -7,705,523,718 - but the filing does not say it. | 880 | Not changed: nothing in the workbook decides it. Stored by the full-text extraction as filed - operating, financing (the filed total), its lines, the net change and the ending cash - with investing and capital expenditure left empty; `INCOMPLETE`. WINE's FY2024 workbook would fill them from its comparative (a comparative fills an empty field). |
| **A broken download.** WINE's FY2024 xlsx is a 165-byte Excel lock file ('Administrator'), not a workbook. | 877 | Rightly refused (422). Download it from IDX again in place of the file in `data/IDX_XBRL/WINE/xlsx`: its FY2023 comparative fills FY2023's investing. |

The negative cash of WINE's FY2024 (-6,347,846,792), FY2025 (-13,842,609,224) and H1 2025 (-2,998,446,986) cash
flows is as filed: WINE reports cash net of bank overdrafts there (the balance sheet: 8,652,153,208 and 2,856,625,986).
ASLC, BBRM, GZCO, IKBI, MHKI, NAIK, SRSN and UNIQ, uploaded with WINE: every filing `SUCCEEDED`.

Tests: `FullTextExtractorTest` (its own reasoning effort in place of the standard extraction's reasoning off); 707
passing.

### The review of 2026-10-22 (errors 802 - 809): KETR, CYBR, IRSX, MNCN, RMKE and LPPF

| What was wrong | Rows | Fix |
|----------------|------|-----|
| **A line filed again within a residual line.** KETR's FY2025 workbook restates FY2024 with 'Other income' 15,061,778,585 - the FY2024 filing's 11,241,254,994 plus its finance income - and still files 'Finance income' 3,820,523,591: profit before tax 101,667,066,908 counts it once. The full-text extraction took over the comparative. | 802 | `FilingMapper.partsOfResidualLines`: an income (expense) line that is no residual line, smaller than a residual 'Other ...' line of its direction, may be filed again within it; when profit before tax then reconciles and every check passes, the line keeps its amount and the residual line is counted without it (`incomeStatementAsFiled(..., less, ...)`), as the period's own filing presents them (operating income 181,136,399,270 in both filings). Tried after the repeats of `doubleReportedLines`. A sweep of every workbook in `data/IDX_XBRL`: it applies to this one column only. |
| **Rows left off the face of the balance sheet.** 18 of the 137 FY2023 workbooks (ADMR, ADRO, ARNA, ASGR, ASII, AUTO, ELSA, ESSA, EXCL, ISAT, ITMG, JPFA, LPPF, MYOR, PGEO, SIMP, UNTR, UNVR) leave rows such as inventories, property, plant and equipment, right-of-use assets and bank loans off the balance sheet that their totals and notes include (LPPF: 'Total current assets' 1,448,030 million beside rows of 655,249 - its inventories of 792,781 are in the note; 'Total current liabilities' 2,952,977 beside 2,402,974, without its bank loans of 550,003). A field with no row was stored as 0 - the sum of no rows - and, as the current period replaces, over the FY2024 comparative's figures: FY2023 inventory 0 for 15 of them (ASII, UNVR, MYOR, ...), short-term debt 0 for ten and long-term debt 0 for nine. LPPF's upload ended `INCOMPLETE`: cash flow ending cash 457,628 is the balance sheet's 507,631 less overdrafts of 50,003, but the filing gave no short-term debt to hold them. No workbook of another year has such a gap. | 809 | `FilingMapper.withoutRowsMissingOnTheFace`: where the rows of a section (current / non-current assets, current / non-current liabilities) fall short of its filed total by more than the rounding (one unit per row), its fields none of whose rows is filed (cash, receivables, inventory, payables, deferred revenue, debt, leases, goodwill, intangibles) are left empty with a warning, so a value another filing stored stays; `fieldsMissingOnTheFace` keeps the full-text extraction from storing 0 debt there. `IngestionVerifier.overdraftExplanation` takes the period's stored short-term debt when the filing gives none. The stored zeros are cleared and filled from the FY2024 comparatives (data below). |
| **An expense by nature in no field.** MNCN files its profit or loss by nature (no gross profit, by design). For H1 2026 and H1 2025 the model named 'Program and broadcasting expenses' (2,133,396 / 2,239,777 million) as cost of revenue, which a filing by nature does not have and is cleared, and only 'Other expenses' as operating expenses: operating expenses 864,240 beside revenue 3,517,600 and an operating income of 519,964 that deducts both. | 806, 807 | `FullTextExtractor.costAsOperatingExpenses`: in a profit or loss by nature, rows named as cost of revenue that operating income subtracts are counted in operating expenses. |
| **Filings that contradict themselves.** CYBR's FY2024 cash flow ends 31,000 above its balance sheet cash (49,913,901,136 - 38,750,102,771 + 21,108,892 = 11,184,907,257 beside 11,184,876,257), and its FY2025 cash flow starts from that figure; the H1 2026 filing starts from the balance sheet's. IRSX's H1 2025 comparative in its H1 2026 filing files a profit before tax of -1,384,348,324, which is gross profit - G&A - finance costs + other income without its finance income 332,451,244 (the interest received in its cash flow) and other expenses 252,471,000. | 803, 804, 805 | Not changed: nothing in the workbooks decides them. CYBR stays `INCOMPLETE` with the difference; IRSX H1 2025 is stored by the full-text extraction as filed. |
| **A broken download.** RMKE's FY2024 xlsx is a 165-byte Excel lock file ('Administrator'), not a workbook. | 808 | Rightly refused; download it from IDX again. |

**MNCN's revenue breakdown of the comparatives.** The first line of MNCN's breakdown by type carries the total in the
prior columns of its FY2025 and H1 2026 workbooks (FY2024: 7,953,812 million beside the other five lines of 5,291,126;
its own FY2024 filing has 'Digital' 2,662,686 there), and the lines have no names. They do not reconcile and are not
stored; FY2024 keeps the breakdown of its own filing, H1 2025 has none.

Data, 2026-10-22: the FY2023 zeros of the 18 companies cleared where the FY2024 comparative differs (inventory 15,
short-term debt 10, long-term debt 9, ESSA's leases) and MNCN's H1 2025 operating expenses; uploaded again, all
`SUCCEEDED`: the FY2024 filings of the 16 affected companies (FY2023 now e.g. ASII inventory 39,138,000, MYOR long-term
debt 3,533,332,615,996, LPPF inventory 792,781 and short-term debt 550,003 million), LPPF 2023 (overdrafts within the
stored debt), KETR 2025 (standard extraction, no fallback). MNCN 2026-II: operating expenses H1 2026 2,997,636 and H1
2025 3,209,795 million, `INCOMPLETE` only for the comparative breakdown above (error 821).

Tests: `FilingMapperKetrLppfTest`, `FullTextExtractorTest` (an expense by nature named as cost of revenue),
`IngestionVerifierOverdraftTest` (overdrafts within the stored short-term debt); 701 passing.

### The review of 2026-10-21 (errors 707 - 713): ITMG, MDKA, MEDC, PGAS, PGEO, ISAT and HRUM

| What was wrong | Rows | Fix |
|----------------|------|-----|
| **Full amounts the EPS does not tell apart.** ITMG's FY2023 workbook declares 'Ribuan / In Thousand' and files full amounts (total assets 2,187,847,000 USD). Its EPS 0.44 gives 1.1 billion shares as filed or 1.1 trillion in thousands, and 1.1 trillion is no impossible count (GOTO has 1.2 trillion), so the declared unit was kept: the filing overwrote the FY2024 comparative with every amount 1,000 times too large (revenue 2,374,315,000,000). | - | `FilingMapper.resolveUnits`: where both counts are possible, the amounts are full amounts when every one of at least 100 is a whole multiple of the declared unit. Of the 419 workbooks that declare thousands, millions or billions, the 20 that are all multiples are FY2023 workbooks with full amounts; ITMG's is the only one the EPS does not decide. |
| **'Other gains (losses)' inside a residual line.** The H1 2025 columns of two H1 2026 filings reconcile only without it: MDKA 3,434,017 within 'Other income' 5,849,538, ITMG -144 within 'Other expenses' 1,644 thousand. The full-text extraction took over, and the model counted FX, other gains and final tax in MDKA's operating income (89,346,886). | 707, 710 | `FilingMapper.doubleReportedLines`: the DEWA rule the other way round (the signed residual line a part of a residual income or expense line of its direction); the reconciliation decides. Both are now read by the standard extraction (MDKA operating income 83,182,117). |
| **One column's EPS a power of ten off.** ITMG's H1 2026 workbook files the H1 2025 EPS 0.08 in its rounding unit (0.00008) beside 0.1; MDKA's files the H1 2026 EPS 0.00042 for 0.0042 (101,558,808 / 24.4 billion shares). Neither has a rupiah share capital, so no par value tells the columns apart: every published count at that column's dates was refused and the EPS stored as filed. | 707, 710 | `FilingMapper.columnEpsOffByPowerOfTen`: the column's per-share figures are multiplied by the power of ten only when the other column's EPS fits a published count as filed, every count at this column's dates misses it by that same power, and with it every count fits (`epsScale(column)`, also in the full-text extraction). |
| **A total EPS on the discontinued row.** Basic EPS is continuing + discontinued. MEDC files its total EPS again on the discontinued row (FY2022 0.021503 + 0.021503 for a discontinued profit of 2.5% of the total; FY2021, FY2025 alike), HRUM its continuing EPS without any discontinued operations (FY2021 - FY2025): the EPS was stored twice too large and the Yahoo Finance counts were refused. | 711, 712 | `FilingMapper.epsWithDiscontinued`: the discontinued EPS is left out when the column has no discontinued profit, or when continuing EPS x discontinued / continuing profit is of the other sign or more than a factor of 2 from it (beyond its filed rounding). Kept for EMTK, INDY, KRAS, MEDC FY2023 / FY2024, MPMX and UNVR, whose discontinued EPS fit their profits. |
| **A balance sheet 0.30 USD apart.** MEDC's FY2022 workbook (full USD) files common stocks 121,798,945.72: liabilities + equity 6,931,905,825.7 beside total assets 6,931,905,826. The read-back check is exact, so the FY2022 and FY2023 uploads ended `INCOMPLETE`. | 711, 712 | `IngestionVerifier.balanceRoundingExplanation`: a difference smaller than the filing's rounding unit (1 for full amounts) is a note, as for the ending cash and the revenue breakdown. |
| **Operating income and expenses by nature.** The model applied the field definitions unevenly: ISAT FY2021 / FY2022 counted 'Other gains (losses)' (the tower sale, 6.6 and 5.0 trillion) in operating income, FY2023 stored 19,150,447 million of 41,911,981 expenses by nature; PGEO FY2023 (no revenue on the face, no breakdown) stored 5,526 thousand expenses and, uploaded again, an operating income of -163,283. Nothing checked it. | - | `FullTextExtractor.operatingIssue` (non-financial profit or loss, operating income built from rows): rows below operating profit in operating income, and by nature operating expenses other than the rows operating income subtracts, are shown to the model when operating income + the rows below it then give the filed profit before tax. By nature, where no choice of the model's rows reconciles, the template's own figures are shown: profit before tax less the rows below operating profit, and every expense row by nature (only when revenue, where filed, less those rows gives the same). Kept in the model's last answer, the correction is used (a warning and a note say so), as the insurer sign reversal is. |
| **Broken downloads.** MDKA's and PGAS's FY2024 xlsx are 165-byte Excel lock files ('Administrator'), not workbooks. | 708, 709, 713 | Rightly refused; download them from IDX again. FY2024 is stored from the FY2025 comparatives. |

**Gross profit of PGEO and ISAT.** ISAT files its profit or loss by nature in every year (3312000; 3311000 is empty), PGEO
up to FY2024 (FY2025 and H1 2026 by function, 3311000). By nature there is no cost of sales and no gross profit row, so
both stay empty, by design (MSIN, BUVA). PGEO FY2024 has a gross profit (240,399 thousand) from the FY2025 filing's by
function comparative, which filled the field its own filing by nature leaves empty; its operating expenses (196,392)
and operating income stay those of its own filing, so gross profit - operating expenses is not its operating income.

**Not changed (the filings).** ITMG H1 2025: profit before tax reconciles only without the -144 (above). Other filings by
nature have operating expenses that do not match their operating income, from earlier uploads: BUKA (FY2025 -2,418,173
million), BUVA, GMFI FY2025, MSIN and NICL. They are corrected when uploaded again.

Data: uploaded again on 2026-10-21, all `SUCCEEDED` with no new row: ITMG 2023 and 2026-II, MDKA 2026-II, MEDC 2022,
2023, 2025 and 2026-II, HRUM 2022 - 2025, ISAT 2022 and 2023, PGEO 2023 (twice; the second after the template rule).
ITMG FY2023 total assets 2,187,847,000, H1 2025 EPS 0.08; MDKA H1 2026 EPS 0.0042; MEDC EPS FY2021 0.001857, FY2022
0.021503 (with a share count), FY2025 0.00408; HRUM FY2021 - FY2025 EPS 0.00582 .. 0.00284 (no longer twice the
profit attributable to the parent / its 13.1 - 13.3 billion shares); ISAT operating income FY2021 3,681,525, FY2022 5,765,522 and expenses FY2023 41,911,981 million; PGEO FY2023
operating income 221,785 and expenses 184,503 thousand.

Tests: `FilingMapperItmgMdkaMedcHrumTest`, `FullTextExtractorTest` (operating expenses by nature, a non-operating row
left out, the template's figures by nature with and without revenue on the face), `IngestionVerifierRoundingTest`.

### The uploads of 2026-10-19 (errors 667 - 673): a final tax twice, a tax with the wrong sign, revenue with other income

Seven rows from COIN, DEWA, ENRG, ESSA, EXCL and HEAL, and BUVA's valuation. Six are fixed in code. While checking
them, the model-mapped filings by nature turned out to carry wrong revenue (EXCL, BUVA, TLKM), and the USD filers
GMFI, KRAS and TPIA wrong EPS and share counts. A sweep of all workbooks in `data/IDX_XBRL` before and after the
change touches only the columns below. Every other income statement is the same, and the only new share counts
are BUVA FY2021 and FY2022 (a filing by nature still takes a published count first).

| Cause | Rows | Fix |
|---|---|---|
| **A final tax deducted twice.** COIN H1 2025 (in its H1 2026 filing): 'Final tax expenses' 48,187,537 above profit before tax and 'Tax benefit (expenses)' -48,187,537 below it; profit before tax 25,573,185,374 is the other lines without the final tax. | 667 | `FilingMapper.doubleReportedLines`: a final tax line equal to the tax below profit before tax is counted once, as that tax, when profit before tax then reconciles exactly. |
| **Other income filed again inside other gains.** DEWA H1 2025: 'Other income' 1,543,226 thousand and 'Other gains (losses)' 2,327,443 = 1,543,226 + 784,217 (the gain on the fixed assets sold for 784,217 in investing). Profit before tax 246,402,270 counts the other income once, in the gains. | 668 | A residual unsigned line is left out when a residual signed line of the same direction is larger and profit before tax then reconciles exactly. Operating income 367,780,970 thousand. |
| **One amount on two lines a unit apart.** ENRG H1 2025 (full USD): 'Other income' 1,903,917 and 'Other gains (losses)' 1,903,918. Only without the gains line is profit before tax 58,365,683 exact. | 669 | Two residual lines a filed unit apart are tried both ways, and the reconciliation decides. |
| **A tax expense filed as a benefit.** HEAL FY2023: 'Tax benefit (expenses)' 151,109 million (an expense filed positive, beside the prior year's -101,315); profit before tax 710,393 - 151,109 = total profit 559,284. | 673 | `FilingMapper.taxSign`: the tax is read with the other sign when only that gives the total profit (warning `tax_sign`). |
| **Revenue with other income; revenue not tagged.** Filings by nature are mapped by the model. It added 'Other income' to 'Sales and revenue' in EXCL FY2024 (34,391,597 + 415,641 million), H1 2025 and H1 2026 (70,613), BUVA FY2024 (355,259,333,593 + 15,260,830,841) and TLKM FY2023 (149,216 + 252 billion), H1 2025 and H1 2026 (-75). EXCL's FY2023 workbook leaves the row empty in both columns (the revenue, 32,322,651,000,000, is only in its breakdown by type, 3617000), and the model stored 'Other income' 424,062,000,000 as revenue with an operating loss of 27.6 trillion. That left the breakdown unsaved. None of these uploads ended in the log: by design a stored filing by nature is not an error. | 672 | `FullTextExtractor.revenueAsFiled`: revenue must equal the profit or loss's own 'Sales and revenue' row (`FilingMapper.filedRevenue`), shown to the model when it does not, and an open item when its last answer still differs. A row empty on the face is shown in the workbook text with the breakdown's total and read back like a filed cell (`WorkbookText.Fill`), so the model can count it in revenue and operating income: FY2023 operating income 4,722,916 million, which reconciles to profit before tax 1,704,517. |
| **A breakdown finer than its unit.** EXCL FY2022 (millions) files three breakdown lines with decimals: 29,141,993.900667 against revenue 29,141,994. | 671 | `IngestionVerifier.segmentRoundingExplanation`: a difference under the filing's declared rounding is a note. The breakdown is stored as filed. |
| **BUVA: no share count before 2023.** BUVA files by nature and its loss EPS without the sign (FY2022: 26 and 51). The par value was judged only on a profit or loss by function, and Yahoo Finance publishes no count before 2023, so FY2021 and FY2022 had no market cap and no valuation. | - | `FilingMapper.parValueFallback`: when no published count fits a filing by nature, its share capital and par value give the counts, as for a filing by function: 340,563,460,000 / par 50 = 6,811,269,200 shares, -177,323,077,262 / 6,811,269,200 = -26.03. The par value is matched on the size of the EPS. Published counts still come first, because they count the treasury shares a par value cannot (BUKA FY2025). |
| **A rupiah par value against a share capital in dollars.** GMFI (USD 219,015,655 / par 5 = 43,803,131 "shares") reproduced its EPS 0.0001 x 1,000, so GMFI's EPS was stored 1,000 times too large (FY2021 -4.5, FY2022 0.1, FY2024 1.0). KRAS (19,751,460 shares, EPS 1 for 0.001) and TPIA FY2024 (86,863,000 shares, EPS -1) were hit the same way. | - | `FilingMapper.rupiahShareCapital`: par values divide only a share capital in rupiah. Those filings take Yahoo Finance's counts, or none. |

Not fixed (the filing): ESSA H1 2026 (670). Every cash flow section adds up to its total, the net change is O + I + F
and the ending cash is the balance sheet's 189,225,543, but beginning cash 125,760,092 + 56,515,451 = 182,275,543,
6,950,000 short. No line of the workbook accounts for it (the bonds moved by -5,300,000, as the balance sheet
shows), so the rule-based extraction rightly refuses the column. The full-text extraction stores it as filed.

BUVA had no gross profit in any period: a profit or loss by nature (1312000) has no cost of sales and no gross
profit row, only revenue and expenses by type ('Hotel operation expenses', 'Other expenses'), and neither field is
built from expense rows by nature (MSIN). Since 2026-10-26 the two come from the filing's own cost of sales note
where it has one (BUVA FY2025: 112,555,493,460 of revenue 375,584,185,441), see above. BUVA's other valuation fields are all filled. H1
2025 has no P/B and no EV ratios because an interim filing has no balance sheet at its comparative date.

Data: the KRAS and TPIA counts of 19,751,460 and 86,863,000 were cleared first (an upload never replaces a stored
count with nothing). Then 22 filings were uploaded again on 2026-10-19, all `SUCCEEDED` with no new row: HEAL 2023,
COIN, DEWA and ENRG 2026-II, EXCL 2022, 2023, 2024 and 2026-II, BUVA 2022 and 2024, TLKM 2023 and 2026-II, GMFI 2022
and 2024, KRAS 2022 to 2026-II, and TPIA 2022 to 2024. Every revenue now equals the filed 'Sales and revenue', and
EXCL FY2023 has its breakdown. BUVA FY2021 and FY2022 have 6,811,269,200 shares and a valuation. KRAS has Yahoo
Finance's 19,346,396,900 shares at every date, with its EPS in USD (FY2022 0.001). TPIA FY2021 to FY2023 have no
count: Yahoo Finance's do not fit their EPS, which is better than one 1,000 times too small.

Tests: `FilingMapperCoinDewaEnrgHealTest`, `FullTextExtractorTest` (revenue with other income shown to the model and
an open item when kept; EXCL's untagged revenue shown with the breakdown total), `IngestionVerifierRoundingTest`
(EXCL's breakdown), `FilingMapperErrorLogTest` / `FilingMapperWebSharesTest` (a refused published count of a
filing by nature falls back to its own count).

### The review of 2026-10-16 (errors 510, 511, 523): share counts and currencies of BKSL, CUAN, DSSA and BUKA

Two open rows - 510 (BBNI FY2025, the full-text extraction took over and stored it: `SUCCEEDED`) and 511 (BKSL's
2024-Tahunan file is a ratio-analysis export, not an XBRL workbook: rightly `REJECTED`; FY2024 is stored from the
FY2025 filing's comparative) - and four companies whose data looked wrong. All fixed in code; the wrong rows were
deleted and the filings uploaded again (all `SUCCEEDED`; row 523 is CUAN's IDR filing, now refused).

| Cause | Fix |
|---|---|
| **BKSL: a par value only one column fits.** FY2024's EPS 0.10 fits par 50, FY2025's 4.95 no standard par value (shares of several par values): 251,243,773,287 shares at 2023-12-31 .. 2025-12-31, 50% too many - market cap and every per-share figure. | `FilingMapper.parMisfit`: a par value must fit the other column's EPS when that column's capital is unchanged. A sweep of all 434 workbooks changes only BKSL FY2025. The wrong counts cleared; Yahoo Finance's 167,708,902,705 (fits both EPS) from 2023-12-31: FY2025 market cap Rp 23.3 trillion, P/E 28.1. |
| **CUAN, DSSA: no share counts.** Yahoo's counts are split-adjusted to today; the filings' EPS predate CUAN's 1:10 split of 2025-07-15 and DSSA's 1:10 of 2024-07-18 and 1:25 of 2026-04-09, so every count was refused. DSSA had no valuation at all, CUAN none before FY2025. | `WebShareCounts` reads Yahoo's split events; a count is also checked on the count of before the splits after its date (exactly one choice must fit), stored split-adjusted, and the split recorded with its date (`recordPublishedSplit`) so earlier EPS are adjusted. A row is on the old basis when nearer to the count before the split than after it (DSSA's buyback moved its count 25%). A split detected from the filings merges into the published one up to two years later. |
| **BUKA, DSSA: buybacks.** A period-end count after a buyback misses the weighted EPS by more than 2% (BUKA FY2025: 92,970,828,660 vs 98.5 billion weighted), so BUKA kept 103.1 billion shares from mid-2025: market cap about 10% too high. | The end count is accepted when the EPS lies between profit / the start count and profit / the end count, shares issued unchanged within 1% and no split in the period. |
| **CUAN: two currencies.** CUAN presents in IDR up to FY2023 and in USD from FY2024; its FY2023 filing stored FY2022 and FY2023 in rupiah beside the dollar years (revenue 1,494,125,816,458 next to 801,723,000) and switched `company.currency` to IDR until the next upload. | `IngestionService.currencyConflict`: a filing in another currency than the company's stored data is refused (422, `UPLOAD_REJECTED`). The rupiah periods deleted; FY2023 stored in USD from FY2024's comparative (revenue 97,943,000); FY2022 exists only in rupiah. |
| **CUAN: the price shown in USD.** The stored prices are right (every close = Yahoo's IDR close / the rate of the day before), but the company page showed 0.03009362 as the price and Technicals 539.9999. | The company API returns the IDX quote beside the stored price (`latestPrice.quote`, `quotePrice`), rounded to 2 decimals; the page shows Rp 540 with the USD figure and the rate; Technicals rounds converted bars to 2 decimals. |

Not fixed (the filings): DSSA's H1 2026 EPS 0.04 fits no share count (stored as filed; its period uses the count of
2025-12-31, unchanged treasury stock); BUKA's H1 2026 EPS -8.53 lies outside its start and end counts (it uses
2025-12-31's 92,970,828,660; Yahoo 91,833,653,260 at 2026-06-30); BUKA FY2021 has no count (Yahoo publishes none).

Tests: `FilingMapperWebSplitsTest`, `IngestionServiceCurrencyTest`, `ShareSplitTest`, `CompanyControllerTest`,
`TechnicalControllerTest`.

### The uploads of 2026-10-18 (errors 605 - 607): revenue only in the breakdown, a cell finer than its unit

Three rows from ADRO and UNTR, both fixed in code and uploaded again on 2026-10-18 (`SUCCEEDED`, no row).

| Cause | Rows | Fix |
|---|---|---|
| **Revenue only in the breakdown.** ADRO's FY2023 workbook tags only 'Total gross profit' (2,537,284,000 USD) on the face of its profit or loss; the revenue, 6,517,556,000, is in the breakdown by type (1617000) and the cost of sales in the notes. Revenue and cost were left empty, so the FY2024 filing's comparative - restated for the discontinued coal business, 2,135,412,000 - stayed beside the FY2023 gross profit and breakdown: gross profit above revenue, segments != revenue in both uploads. | 605, 606 | `FilingMapper.incomeStatement`: a column with only the gross profit takes its revenue from its own revenue breakdown (the reported total, else the sum of its lines; only a breakdown without a failed check) and cost = revenue - gross profit; without a breakdown it stays empty as before. Nine FY2023 workbooks tag only the gross profit (ADRO, ARNA, ASII, AUTO, ELSA, JPFA, MYOR, SIMP, UNTR); only ADRO's stored data changes. |
| **A cell finer than its unit.** UNTR's FY2022 workbook (millions) files the ending cash as 38281513.468848 beside a balance-sheet cash of 38281513: 468,848 rupiah apart. The mapper's check accepts it (under one unit); the database verification compared exactly and ended the upload `INCOMPLETE`. | 607 | `IngestionVerifier.roundingExplanation`: a difference under the filing's rounding unit is a note, the figures stored as filed. |

Tests: `FilingMapperAdroTest`, `FilingMapperAsgrTest.simpFilingQuirks`, `IngestionVerifierRoundingTest`.

Later the same day (errors 619 - 622, and BRMS without a valuation):

| Cause | Rows | Fix |
|---|---|---|
| **Broken files.** TRUK's 2024-Tahunan workbook is a cut-off download (no ZIP directory); ARTO's 2024-Tahunan is a 165-byte Excel lock file saved under the workbook's name. | 619, 621 | Rightly refused (422); download the two workbooks again. |
| **Lines with decimals outside the total.** ADMR's H1 2026 filing, H1 2025 column (USD thousand): exploration and evaluation assets 124.222 and intangible assets 90.109, the only cells with decimals; the other investing lines give the filed total -322,409 exactly. | 620 | `FilingMapper.section`: in a filing rounded to thousands or millions, lines filed with decimals are not counted (in the fields either) when the other lines then give the filed total exactly. |
| **A syirkah total left empty.** ARTO FY2022 (bank): 'Total temporary syirkah funds' empty, its lines filed (269,558 + 256,501 = 526,059 million), the total entered on 'Total accumulated tabarru's funds'. | 622 | `FilingMapper.financialBalanceSheet`: the sum of the lines under 'Temporary syirkah funds' when the total is not reported and the balance sheet then balances exactly. |
| **An EPS per 1,000 shares.** BRMS (full USD amounts) files 0.35 for USD 0.00035 (50,009,777 / 141.8 billion shares), so no Yahoo Finance count fit its EPS: no share counts, no market cap, no valuation. | - | `FilingMapper.withWebShareCounts`: when no published count fits the EPS as filed and every one fits it divided by 1,000, the EPS is read per 1,000 shares (warning `eps_unit`) and stored per share. All five BRMS filings uploaded again: 141,784,040,338 shares, valuation from FY2022 (P/E 92.5 on 2026-10-16). |

Test: `FilingMapperAdmrArtoBrmsTest`.

### The uploads of 2026-10-17 (errors 579 - 582): an FX line outside the roll-forward, a sign the model kept

Four rows from SMBR, RISE and PNLF (an insurer). Two fixed in code and uploaded again on 2026-10-17 (`SUCCEEDED`, no
row); two are comparatives that contradict themselves, closed without a rule.

| Cause | Rows | Fix |
|---|---|---|
| **FX / other effects the filed roll-forward leaves out.** RISE's H1 2026 filing, H1 2025 column: beginning cash 260,777,639,668 + net change -36,001,003,868 = 224,776,635,800, the ending cash as filed, and every section adds up to its total; the effect of exchange rate changes (398,002) is not in it. The roll-forward check failed by 398,002 and the column went to the full-text extraction. | 580 | Like the deconsolidated cash of MIDI (321): when the roll-forward fails with the FX / other effects and holds exactly without them, they are not counted (warning `cash_rollforward`). Uploaded again: the standard extraction stores the column, ending cash 224,776,635,800. |
| **A sign the model was shown and kept.** PNLF FY2024: its profit before tax adds 'Decrease (increase) in unearned premiums' as filed (3,269 million in FY2024, -760 in FY2023). The model deducted it, was shown the only reversal that closes the gap (6,538 = 2 x 3,269) and answered the same: revenue 13,685,508 instead of 13,692,046, operating income 4,248,950 beside a profit before tax of 4,255,488, the upload `INCOMPLETE`. | 582 | `FullTextExtractor`: after the last round, when exactly one reversal (one row, or two) reconciles an insurer's profit before tax and the statement then has no open check, the row's sign is reversed for the model in every field that names it (warning `full_text`, a note in the report); otherwise the answer is stored as before, with the open item. Uploaded again: revenue 13,692,046, operating income 4,255,488 million. The model left gross profit empty this time, so the earlier 11,995,130 stayed (a field a filing does not report keeps its stored value); set to revenue - cost (12,001,668) and the derived data refreshed. |
| **A comparative restated in part (closed without a rule).** PNLF's FY2025 filing restates its FY2024 column only in part (net investment income, other underwriting income, G&A and profit before tax 3,986,888) beside insurance lines as filed a year before; no choice of signs of its lines gives the profit before tax. | 581 | None: FY2024 is stored from its own filing (582). |
| **Investing flows counted twice (closed without a rule).** SMBR's FY2025 filing, FY2024 column: PP&E 37,289,552 thousand already includes the other non-financial assets 20,131,637 also on their own line, and 14,581,729 is placed in both short-term investments and restricted funds: the lines give -86,664,147 for a filed total of -51,950,781. | 579 | None: rightly refused; FY2024 is stored from its own filing, which reconciles (investing -51,950,781). |

Tests: `FilingMapperRiseSmbrTest` (RISE's FX line, SMBR's comparative still refused), `FullTextExtractorTest` (PNLF: the
reversal made when the model keeps the sign, none made when no reversal reconciles).

### The uploads of 2026-10-15 (errors 415 - 420, 432): a total without its dividends, amounts entered twice, a 3 rupiah slip

Seven rows: a test artefact, three WIFI filings fixed in the rule-based extraction, and two TPIA comparatives that
contradict themselves. WIFI's three filings and TPIA's FY2025 filing were uploaded again: `SUCCEEDED` on the
standard extraction, no row, no model cost. A sweep of all 385 workbooks in `data/IDX_XBRL` changes only the WIFI
columns below.

| Cause | Rows | Fix |
|---|---|---|
| **A test's row, left behind.** `UploadJobTimeoutTest` (3 s limit) removes its job's error rows once the job is finished, but the worker finished the job before it wrote the error row; deleting the job then set the row's `job_id` to NULL. | 415 | The error row is written before the job is finished (`FinancialStatementQueue`); the test asserts it. |
| **A section total that leaves out a line.** WIFI FY2025: financing filed 10,909,102,818,568, its lines 10,904,384,108,332 (without the dividends paid, 4,718,710,236); only the sum of the lines gives the net change as filed. The full-text extraction stored the filed total, so O+I+F did not give the net change. | 418 | `correctSectionTotal` (section 4): financing 10,904,384,108,332. |
| **Amounts entered twice.** WIFI's H1 2026 filing, H1 2025 column: three financing cash flows on two lines each (783,412,016,329; 15,495,302,170; 674,885,668). The model's debt repaid was -14,820,416,502. | 419 | Counted once when that gives the filed total exactly, also in the fields: debt issued 784,086,901,997, debt repaid -15,495,302,170. |
| **A slip of 3 rupiah.** WIFI FY2022 (full amounts): ending cash filed 20,928,775,242, beginning + net change 20,928,775,245. | 420 | A difference under a billionth of the amount is a warning in a full-amount filing. |
| **A comparative income statement that contradicts itself (closed without a rule).** TPIA's H1 2026 filing, H1 2025 column: its lines give profit before tax 1,572,723 thousand USD, the filed total is 1,576,527 (profit before tax + tax = profit holds); 3,804 is no line and no reversed sign. | 416, 432 (the upload again) | None: rightly refused; the full-text extraction stores it with the filed totals (`SUCCEEDED`). |
| **A refused comparative breakdown of a period already stored.** TPIA's FY2025 filing repeats FY2024 figures under other names (product lines 2,107,089 under a subtotal of 1,695,869 thousand). Refused - but FY2024's breakdown is stored from its own filing and a comparative never overwrites it, so the open item could never close (`INCOMPLETE`). | 417 | `IngestionVerifier`: a note, not an open item, when the stored breakdown adds up to the period's revenue. |

Also on 2026-10-15, outside the error log: BACH (listed 2026-07-08) had no valuation. Its only filing is H1 2026,
so no trailing twelve months exist (no FY2025 income statement), and a valuation needed TTM revenue; its share
count was the one of before its IPO (3,469,430,000). `V1.0.24__data_BACH_shares.sql` adds the 615,000,000 IPO
shares from the listing date (4,084,430,000), and a company without TTM is valued on its latest balance sheet
(market cap Rp 1,356.0 billion, P/B 2.21 at Rp 332; no P/E until a full year is stored).

Tests: `FilingMapperWifiTpiaTest`, `IngestionVerifierSegmentsTest`, `UploadJobTimeoutTest`, `DerivedDataRefreshTest`.

On 2026-10-26, outside the error log:

- **BNBR had no market cap and no valuation.** Its filings give no share count: the common stock is five series of
  different par values (Seri A Rp 5,687, B Rp 796, C Rp 227, D Rp 99, E Rp 12), and the Yahoo Finance count, about
  201.4 billion, fits none of its EPS (FY2025: 493,852 million / 2.85 = 173.3 billion). `V1.0.31__data_BNBR_shares.sql`
  records the company's own counts: 160,057,457,509 from 2023-12-31, 173,416,832,509 from the PMTHMETD of 2024-12-11
  (13,359,375,000 Seri E shares at Rp 64, a debt conversion), 263,336,671,587 from the PMHMETD V of 2026-07-31
  (89,919,839,078 Seri E shares at Rp 53). Market cap Rp 22.0 trillion at 2025-12-31, Rp 14.75 trillion at
  Rp 56 (2026-10-22). Test `BnbrSharesTest`.
- **BIPI had no gross profit** (by nature). Its every filing states the cost of sales in its note 1670000: the
  full-text extraction now takes cost of revenue and gross profit from it (above). The stored periods get them when
  the filings are uploaded again. Test `FullTextExtractorTest.byNatureTheCostOfSalesNoteGivesTheGrossProfit`.

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
| `model` | the model the request asked: `spring.ai.openai.chat.model` (`OPENAI_MODEL`, DeepSeek V4.1 Flash) for the standard extraction, `neracalab.ingestion.fallback.model` (`INGESTION_FALLBACK_MODEL`, Claude Haiku 5.5; Sonnet 5.5 before 2026-10-30) for the full-text extraction |
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
- A call through OpenCode Go (section 2) reports `usage.cost` 0, billed to the subscription: its rows have
  `cost_usd` 0 and `cost_estimated` false.
- **Failed calls go to the error log too.** When an upload is recorded in `ingestion_error` (section 6), the
  row's `details.failedModelCalls` lists the job's failed model calls from these rows, so a full-text
  extraction whose model (Sonnet) timed out or was refused shows what the provider said:

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
model asked - DeepSeek and Sonnet -, tokens and the reported cost; a call without a cost is estimated and
flagged; a failed attempt and its retry are both recorded; a row that cannot be written does not fail the
ingestion), `IngestionJobCostTest` (the sums per job and per stage and model, against Postgres).
