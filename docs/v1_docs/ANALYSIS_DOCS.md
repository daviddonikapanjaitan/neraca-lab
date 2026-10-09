# Neraca Lab - AI Analysis of One Stock (v1)

Analyses one stock of the `company` table in depth through the eyes of the same six investor agents
as the screening (Buffett, Munger, Lynch, Fisher, Keith Gill, Risk). Unlike the screening, it works
from what the database holds for that company: its stored financial statements and metrics, prices
and valuations, and its PDF documents and news in the RAG vector store. DeepSeek V4 Flash researches
and analyses, Claude Opus 5.5 writes the synthesis (the screening's models and prices). Every
analysis is a background job; the report and its cost are saved, shown on **Screening > Analysis**
and downloadable as PDF.

```
[Company data, no AI]  company, reporting_period + statements + financial_metric, price_daily,
                       valuation_snapshot -> fact sheet (FactSheet)
                       Yahoo Finance snapshot (fundamentals refreshed when stale) -> metrics and the
                       quantitative scorecard of every agent (QuantScorer, as in the screening)
      |
[Research agent]  DeepSeek, ReAct + tool calling over the company's own documents:
                  searchFilings (PDF chunks), searchNews (news chunks), getStatement (one period)
                  -> research brief with references (F1 = filing excerpt, N1 = news)
      |
[Investor agents] one independent call each, shared dossier (fact sheet + market data + brief)
                  -> Reflection: validator checks every answer, a critic reviews the flagged ones
                  -> Reflexion: lessons of earlier runs in the prompts, new lessons stored
      |
[Score]  agent = 0.6 x quantitative + 0.4 x AI (AI alone without market data);
         overall = investor average blended 20% with Risk
      |
[Synthesis] Opus 5.5: executive summary, conviction, thesis, bull / bear case, risks, monitor,
            data gaps, adjustment of at most +-5 points
      |
[Save report] -> Screening > Analysis -> PDF
```

## 1. Using it

Frontend: the **Screening** entry of the sidebar is a dropdown (like Ingestion and the Admin Center)
with **Screening Stocks** (`/screening`, the exchange screening), **Selected Stocks**
(`/screening/selected`, a screening of stocks chosen from the companies table) and **Analysis**
(`/screening/analysis`). Permission `SCREENING`.

1. Choose a stock (ticker) of the companies table. The form shows what the database holds for it:
   reporting periods, Yahoo Finance market data, PDF documents and news articles, with the Ingestion
   page that adds what is missing.
2. Choose the investor agents (all six by default, at least one).
3. **Start analysis**: the analysis is queued and the report page follows it (stage, cost so far).
   Starting a stock that is already being analysed opens that analysis.
4. The report: overall score and verdict, AI cost and tokens, executive summary (conviction, thesis,
   bull and bear case, key risks, what to monitor, data gaps), every agent's view (scores, verdict,
   thesis, strengths, concerns, reflection, quantitative scorecard), the research brief with the
   excerpts it read and the ReAct steps, key figures of the stored periods, the data used, notes and
   Reflexion lessons, token usage per stage and model. **Download PDF**.
5. Saved analyses are listed below the form, 10 per page (5 / 10 / 20 / 50).

The more the database holds, the deeper the analysis: upload the IDX XBRL filings (statements),
run Screening Data IDX (market data and scorecards), upload the PDF filings and ingest the news
(Ingestion > PDF Documents (RAG), News (RAG)). Without documents the research agent is skipped (no
model call) and the agents judge from the statements alone; without market data they have no
quantitative prior. The report notes every such gap.

The Ingestion jobs table lists analyses under the tab "Analyses" (job type `ANALYSIS`).

## 2. API

Every endpoint needs a login and the `SCREENING` permission.

| Endpoint | Result |
|----------|--------|
| `GET /api/v1/analyses/options` | companies with their stored data (periods, latest period, PDF / news documents, market data date, latest price date), agents, budget, models |
| `POST /api/v1/analyses` | `{"ticker":"HRTA"[,"exchange":"IDX","agents":["BUFFETT",...]]}`: 202 with the queued analysis (`Location`), 200 with the company's active one |
| `GET /api/v1/analyses[?ticker&limit=10&offset=0]` | `{ total, limit, offset, analyses }`, most recent first (limit 1-100) |
| `GET /api/v1/analyses/{id}` | the report (also while it runs) |
| `GET /api/v1/analyses/{id}/pdf` | PDF (409 while it runs) |

```bash
curl -H "$AUTH" -H "Content-Type: application/json" http://localhost:8080/api/v1/analyses -d '{"ticker":"HRTA"}'
curl -H "$AUTH" http://localhost:8080/api/v1/analyses/{id}            # report
curl -H "$AUTH" -OJ http://localhost:8080/api/v1/analyses/{id}/pdf    # PDF
```

`agents` absent: all six; names such as "Warren Buffett" are accepted, duplicates dropped. Errors are
400 ProblemDetails (`Choose a stock (ticker)`, invalid ticker, `Choose at least one investor agent`,
`Unknown agents: ...`, `Unsupported exchange`, limit / offset out of range) and 404 (`Company not
found`, unknown analysis).

The report: `run` (status and stage of the job, ticker, agents, `overallScore`, `verdict`,
`conviction`, market data date, cost and tokens), `quantOverall`, `synthesisAdjustment`, `context`
(fact sheet, market data, stored documents, periods), `research` (brief, trace, retrieved excerpts,
`droppedRefs`, note), `synthesis`, `notes` (messages, lessons applied and learned, budget), `agents`
(per agent: quantitative score and scorecard, AI score, final score, verdict, thesis, strengths,
concerns, reflection, status `ASSESSED`, `REVISED`, `QUANT_ONLY` or `NO_SCORE`) and `usage` (per stage
and model).

Job statuses: `SUCCEEDED`; `INCOMPLETE` when the analysis degraded (an agent without usable answer
or skipped by the budget, research failed or skipped by the budget, synthesis fallback, no overall
score; the reasons are in `message`); `FAILED`.

## 3. Company data (no model)

`FactSheet` builds the overview every agent sees from `CompanyService.detail` (one consistent
read): the latest `annual-periods` (4) fiscal years and, when newer, the latest interim period with
its prior-year comparative (e.g. `2026 H1`, `2025 H1`, `2025 FY` ... `2022 FY`); per period the key
income, balance sheet and cash-flow lines and 19 metrics (margins, annualized ROE / ROA / ROIC,
leverage, liquidity, interest coverage, cash conversion, free cash flow, net debt, EPS, book value
per share); the latest valuation (price, market cap, P/E, P/B, P/S, EV/EBITDA, FCF and earnings
yield) and price. Amounts are in the reporting currency, IDR in billions, other currencies (USD) in
millions; per-share amounts unscaled; ratios as fractions.

Market data: the stock's Yahoo Finance fundamentals are refreshed when older than the screening's
`etl.fundamentals-max-age` (two requests, nothing when the stock is not listed in the screening
data), then the latest snapshot gives the metrics (`StockProfile`) and each agent's quantitative
scorecard. A scorecard with fewer than `min-fundamentals-coverage` (50%) of its metrics known is no
prior: the agent judges without it.

## 4. Agents and patterns

`analysis`: `AnalysisResearchAgent`, `ContextTools`, `AnalysisSynthesisAgent`, `AnalysisService`;
the investor agents, the Reflection validator and critic and the Reflexion memory are the
screening's (`screening/agent`: `InvestorPanel`, `ReflectionValidator`, `ReflexionMemory`).

| Pattern | Where |
|---------|-------|
| Tool calling | `ContextTools`: `searchFilings(query)` and `searchNews(query)` embed the query and search the company's own chunks in pgvector (cosine, `search-results` per search, excerpts of `excerpt-chars`); `getStatement(period)` returns every stored line and metric of one period. A search never leaves the company. |
| ReAct | `AnalysisResearchAgent`: "Thought:" -> tool calls (several per turn) -> observations, at most `research-iterations` (4) turns, then a forced final answer |
| Conditional tool calling | a search tool is offered only when documents of its kind are stored, and every tool only until its limit (`max-searches` 6 together, `max-statement-lookups` 3); a repeated query is refused |
| Grounding check | evidence citing a ref that was never retrieved is dropped (`droppedRefs`) |
| Independent agents | one call per agent; the system prompt and the dossier are identical for all six (cacheable prefix), only the persona block differs |
| Reflection | `ReflectionValidator` (divergence from the scorecard, verdict outside its band, philosophy rules, metrics not in the dossier, empty thesis; the checks needing market data are skipped without it) + the critic on flagged answers only |
| Reflexion | lessons of earlier runs (`screening_lesson`, shared with the screening) are added to each agent's prompt; an issue kind an agent shows in an analysis becomes a lesson (one answer per agent, so one occurrence suffices). Within the run, an unusable answer is retried once with the problem as feedback (cut off at the output limit, or the parse error); the unusable reply itself is not sent back, so the model writes a complete answer instead of continuing a fragment |
| Synthesis | `AnalysisSynthesisAgent` (Opus, effort low): summary, conviction, thesis, bull / bear case, risks, monitor, data gaps, -5..+5 adjustment; deterministic fallback |

## 5. Cost control

Budget: `budget-usd` (0.20) per analysis, hard cap; `synthesis-reserve-usd` (0.10) is kept for the
synthesis (its estimate at list prices is about $0.09 at most) while the research and the agents run. Before each optional call the analysis checks the
budget; when it is reached the research is skipped, an agent keeps its quantitative score, a flagged
answer is not reviewed or the synthesis falls back - and the report says so.

| Lever | Effect |
|-------|--------|
| Data in Java | statements, metrics and scorecards are computed, not asked for |
| No documents, no research call | the research agent runs only when PDF documents or news are stored |
| Retrieval instead of whole documents | only the excerpts the agent asks for (4 x 800 characters per search) |
| DeepSeek reasoning off, cheapest provider (OpenInference excluded) | as the screening (`LlmGateway`) |
| Shared prompt prefix | system prompt + dossier identical for the six agents (cacheable) |
| Reflection only when flagged | the critic runs on flagged answers only |
| Compact synthesis input | Opus gets the agents' views, key figures and the brief, not the full dossier; one call, effort low |

Usage: every model call and every query embedding is a row of `llm_usage` (stage `RESEARCH`,
`RETRIEVAL`, `AGENT`, `REFLECTION`, `SYNTHESIS`; `analysis_id`); totals are on `analysis_run`. Model
costs are OpenRouter's `usage.cost`; the embedding API reports no cost, so retrieval is estimated
from `embedding-price-per-million` (marked `*`). Expected cost, estimated from the list prices: a
few cents per analysis, most of it the Opus synthesis.

## 6. Settings (`application.yaml`, `neracalab.analysis`)

| Property | Default | Meaning |
|----------|---------|---------|
| `budget-usd` (`ANALYSIS_BUDGET_USD`) | 0.20 | cost cap per analysis |
| `synthesis-reserve-usd` | 0.10 | kept for the synthesis |
| `research-iterations` | 4 | model turns of the research agent |
| `max-searches`, `max-statement-lookups` | 6, 3 | tool limits of the research agent |
| `search-results`, `excerpt-chars` | 4, 800 | chunks per search, excerpt length |
| `annual-periods` | 4 | fiscal years in the fact sheet |
| `embedding-price-per-million` | 0.02 | estimated cost of the query embeddings |
| `agent-max-tokens` | 1000 | output limit of an investor agent answer or review (the screening's 450 is too short for the richer dossier) |

Models, their options and prices, `llm-weight`, `risk-weight`, `min-fundamentals-coverage` and
`llm.concurrency` are the screening's (`neracalab.screening`, [SCREENING_DOCS.md](SCREENING_DOCS.md)).

## 7. Tables (`V1.0.15__schema_analysis.sql`)

`analysis_run` (`analysis_id` = the `ingestion_job` id, cascade delete; company, agents, budget,
market data date, quantitative and overall score, adjustment, verdict, conviction, context, research,
synthesis, notes, cost and tokens), `analysis_agent_score` (one row per agent), and
`llm_usage.analysis_id`. The job type `ANALYSIS` is listed in `ck_ingestion_job_type` by `V1.0.10`
and `V1.0.14`. Details in [DB_SCHEMA_DOCS.md](DB_SCHEMA_DOCS.md).

## 8. Tests

| Test | What it proves |
|------|----------------|
| `AnalysisAgentsTest` | no documents: no research call; ReAct with a `searchFilings` call: only useful tools offered, excerpt refs and pages, whitespace collapsed, invented refs dropped, retrieval metered as estimated; tool limits (repeated query, unknown period, no documents); synthesis bounds (adjustment +-5, conviction filled in, none without score) and fallback; an agent without a quantitative prior; fact-sheet period selection and currency scaling |
| `AnalysisControllerTest` | HRTA end to end against the Docker Postgres (its stored statements and PDF chunks; model answering by role, stub embeddings, no Yahoo request): report, fact sheet periods, retrieved excerpts, six agents, capped adjustment, usage per stage, Opus for the synthesis, list paging, PDF; an active analysis returned instead of queued again; validation |
| `AccessControlTest` | the analysis APIs need SCREENING |

The first HRTA analysis (2026-10-08) ended `INCOMPLETE`: with the screening's 450-token output limit,
four agents were cut off mid-JSON (their answers with statements, brief and references are about 300-500
tokens, against about 140 in a screening), and the retry, shown the fragment, continued it without a
score. Hence `agent-max-tokens` 1000 (at most about $0.001 more per agent) and the retry from scratch.

The second HRTA analysis (2026-10-08) was still `INCOMPLETE`: Munger and both reflection reviews failed with
`stream was reset: CANCEL` after exactly 60 s, and the other answers had no strengths or concerns. The
cause was the provider: `provider.sort: price` routed every DeepSeek call to OpenInference (fp4), whose
answers to this dossier ran in loops to the output limit (about 66 s, so OpenRouter reset the stream at
60 s), ignored the persona and wrote other keys. The same prompt on StreamLake, Inceptron, Sail Research,
DeepInfra and Reka answered correctly in 3-6 s for about $0.0002. Hence (shared with the screening):
`llm.provider-ignore` (default OpenInference), `llm.model-retries` 2 for transient failures, and an
answer without strengths and concerns retried as unusable.

## 9. Limits

- The analysis is as good as the stored data: without filings, documents or market data the agents
  say so rather than guess, and the report lists the gaps.
- Retrieval finds the closest excerpts to the agent's queries, not every relevant passage.
- The report is generated by AI models and is not investment advice.
