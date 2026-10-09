# Neraca Lab - AI Stock Screening (v1)

Screens an exchange (IDX for now) through the eyes of six investor agents and keeps the top N
stocks. Built with **Spring AI 2.0** on OpenRouter: DeepSeek V4 Flash for the research and
investor agents, Claude Opus 5.5 for the final synthesis. Every run is a background job; the report
is saved in the database, shown on the Screening page and downloadable as PDF.

```
[Daily ETL] Yahoo Finance -> PostgreSQL (stock_listing, fundamental_snapshot)
      |
[Stage 1: quantitative pre-screen in Java, NO LLM]
  market-cap tier, price, trading, liquidity, board, fundamentals, positive earnings, equity
  -> quantitative scorecard per selected agent -> shortlist = top N x 3 (max 100)
      |
[Stage 2: LLM agents on the shortlist only]
  research agent (ReAct + tool calling: EmitenNews, Pasardana, IDX Channel, Investor.id, Tavily)
  -> each selected investor agent scores each stock (one independent call)
  -> Reflection: validator checks every answer, a critic reviews the flagged ones
  -> Reflexion: lessons from earlier runs are part of the prompts; new lessons are stored
      |
[Ranking] agent score = 0.6 x quantitative + 0.4 x AI; overall = investor average blended with Risk
      |
[Synthesis] Opus 5.5: executive summary, conviction, thesis, adjustment of at most +-5 points
      |
[Save report to DB] -> [Screening page] -> [PDF]
```

## 1. Using it

Frontend: **Screening > Screening Stocks** in the sidebar (below Companies; the Screening entry is a
dropdown with Screening Stocks, Selected Stocks and Analysis), permission `SCREENING` (the built-in Administrator role
has it; give it to other roles in Role Management). The in-depth analysis of one stock (Screening >
Analysis) reuses the investor agents, Reflection and Reflexion of this page: [ANALYSIS_DOCS.md](ANALYSIS_DOCS.md).

1. Choose the stock exchange (IDX), the market cap (large, mid, small), how many stocks to keep
   (top N, 1-50, default 25) and the investor agents (multi-select, at least one).
2. **Start screening**: the run is queued and the report page follows its progress (stage, cost so far).
3. When it has finished: executive summary, final ranking (per-agent scores, conviction, news
   sentiment), a detail panel per stock (thesis, red flags, news brief with the research agent's
   steps and headlines, key metrics, every agent's reasoning, reflection, scorecard), the rest of
   the shortlist, the Stage 1 funnel, notes and lessons, token usage per stage and model.
4. **Download PDF**. Saved screenings stay listed on the Screening page.

**Screening > Selected Stocks** screens stocks you choose instead of a market-cap tier, with the same steps
(data refresh, Stage 1, Stage 2, ranking, synthesis, report, PDF):

1. Pick stocks from the companies table of the exchange (IDX; searchable multi-select, 1-100 stocks, i.e.
   at most `max-shortlist`), the top N (1 to the number selected, at most 50; default 25 or fewer) and the
   investor agents.
2. Stage 1 runs the same steps on the selected stocks (section 4), with these differences:
   - the tier filter becomes "market cap known";
   - the **tradability checks** (price at least Rp 50, traded within 10 days, liquidity, watchlist board /
     `excluded-tickers`) do not drop a stock the user chose: it is kept and **flagged** with the reason,
     e.g. "Low liquidity: traded value per day 73M, below the 5B minimum of large caps" (the liquidity minimum
     is that of the stock's own tier). The flag is a red flag in the report, listed in the notes ("Kept although
     they failed a tradability check: ..."), and given to the agents in the dossier (`tradabilityWarnings`);
     the Risk scorecard already rates liquidity;
   - the **data checks** (market data, fundamentals, positive earnings, positive book equity, scoring-metric
     coverage) still exclude a stock, as the scorecards need them; the notes name each one with the check it
     failed ("Filtered out in Stage 1: ...");
   - every stock that passes is shortlisted (no `top N x 3` cut), so the AI agents analyse all of them, and the
     fundamentals of flagged stocks are refreshed like the others.
3. The agents see each stock's own tier in its dossier; the synthesis is told the stocks were selected by
   the user. The run is `INCOMPLETE` when fewer stocks pass than the top N.

The page lists only screenings of selected stocks; Screening Stocks lists only tier screenings.

The **Ingestion** page has a "Screening data" card to run the ETL by hand, and its jobs table opens
on the ETL runs (`FUNDAMENTALS`); the screenings (`SCREENING`) and the other jobs are under the other tabs.

## 2. API

Every endpoint needs a login (`Authorization: Bearer <token>`).

| Endpoint                                             | Permission            | Result                                         |
|------------------------------------------------------|-----------------------|------------------------------------------------|
| `GET /api/v1/screenings/options`                     | SCREENING             | exchanges, tiers, agents, limits, stored data  |
| `POST /api/v1/screenings`                            | SCREENING             | 202 with the queued run (`Location`)           |
| `GET /api/v1/screenings/companies[?exchange=IDX]`    | SCREENING             | companies that can be selected, with their market data date, market cap, fundamentals; `maxSelected` |
| `GET /api/v1/screenings[?limit=50][&scope=ALL\|TIER\|SELECTION]` | SCREENING | runs, most recent first (1-200); `TIER` = tier screenings, `SELECTION` = of selected stocks |
| `GET /api/v1/screenings/{id}`                        | SCREENING             | the report (also while the run is active)      |
| `GET /api/v1/screenings/{id}/pdf`                    | SCREENING             | PDF (409 while the run is active)              |
| `POST /api/v1/fundamentals/ingestions?exchange=IDX[&full=true]` | INGESTION  | 202 new ETL run / 200 the active one           |
| `GET /api/v1/fundamentals/status?exchange=IDX`       | INGESTION or SCREENING | listings, latest market data, fundamentals    |

```bash
curl -H "$AUTH" -H "Content-Type: application/json" http://localhost:8080/api/v1/screenings \
     -d '{"exchange":"IDX","marketCapTier":"LARGE","topN":25,"agents":["BUFFETT","MUNGER","LYNCH","FISHER","GILL","RISK"]}'
```

A screening of selected stocks sends `tickers` instead of `marketCapTier` (never both):

```bash
curl -H "$AUTH" -H "Content-Type: application/json" http://localhost:8080/api/v1/screenings \
     -d '{"exchange":"IDX","tickers":["BBCA","BBRI","TLKM"],"topN":2,"agents":["BUFFETT","RISK"]}'
```

`tickers` are normalized (trimmed, upper case), duplicates dropped, selection order kept; errors:
`Choose at least one stock`, `Choose at most 100 stocks (n selected)`, `Not in the IDX companies table: ...`,
`Invalid ticker ...`, `Give either marketCapTier or tickers, not both`, `topN must be between 1 and <selected>`.
The run's `marketCapTier` is then `null` and `tickers` lists the stocks (`tickers` is `null` for a tier).

`marketCapTier`: `LARGE`, `MID` (or `MEDIUM`), `SMALL`. `agents`: `BUFFETT`, `MUNGER`, `LYNCH`,
`FISHER`, `GILL` (Keith Gill / Roaring Kitty), `RISK`; names such as "Warren Buffett" are accepted;
duplicates are dropped. Errors are 400 ProblemDetails (`marketCapTier must be one of ...`,
`topN must be between 1 and 50`, `Choose at least one investor agent`, `Unknown agents: ...`,
`Unsupported exchange`).

The report (`GET /api/v1/screenings/{id}`): `run` (status and stage of the job, parameters,
counts, cost and tokens), `funnel`, `synthesis`, `notes` (messages, lessons learned and applied,
budget), `candidates` (final ranking first: metrics, news, scores per agent with reflection and
scorecard, rank, selected, conviction, thesis, red flags) and `usage` (per stage and model).

Job statuses: `SUCCEEDED`; `INCOMPLETE` when the run degraded (fewer eligible stocks than the top
N, assessments without model answer, synthesis fallback, Yahoo rate limit; the reasons are in
`message`); `FAILED`.

## 3. Data: the daily ETL

`screening/data`: `YahooFundamentalsClient`, `FundamentalEtlService`, `FundamentalsQueue`,
`FundamentalsEtlSchedule`. Requests go through the shared `PacedHttpClient` (1-2 s apart, together
with the price ingestion).

| Step         | Yahoo API                                                   | Requests                   |
|--------------|-------------------------------------------------------------|----------------------------|
| Universe     | `POST /v1/finance/screener` (exchange JKT, 250 per page)    | 4 for about 840 listings   |
| Fundamentals | `GET /v10/finance/quoteSummary` (financialData, defaultKeyStatistics, summaryDetail, assetProfile) + `GET /ws/fundamentals-timeseries` (4 fiscal years) | 2 per stock |

The screener and quoteSummary need a session cookie and a crumb (fetched once, renewed on
"Invalid Crumb"). Market data is stored for every listing every day; fundamentals are refreshed
when older than `etl.fundamentals-max-age` (7 days) and carried forward to the new day's rows in
between. The schedule (`etl.schedule`, on by default) queues a run Monday-Friday 18:00 Jakarta
time. Every screening also refreshes today's market data if missing and the fundamentals of the
stocks that pass the market-data filters of its tier, so it works on an empty database (the first
run loads the universe; a large-cap run then refreshes about 100 stocks, a few minutes).

Not available from public data: the IDX **listing board** (idx.co.id blocks automated clients).
Stage 1 therefore uses proxies: price at least Rp 50 (the special-monitoring board trades below),
a trade in the last 10 days (no suspension), liquidity, and `excluded-tickers` for known
watchlist stocks. `stock_listing.board` is kept for when a source becomes available.

## 4. Stage 1 (no AI)

`screening/quant`: `StockProfile` (derived metrics: ROE, margins, debt/equity, CAGR of revenue and
earnings, profitable years, margin trend, capex intensity, FCF conversion and yield, PEG, drawdown,
52-week range, interest coverage, Altman Z, equity/assets), `QuantScorer` (scorecards),
`QuantScreener` (filters, funnel, shortlist).

Filters in order (each recorded in the funnel): tier (large >= Rp 10T, mid Rp 1-10T, small < Rp 1T)
-> price >= Rp 50 -> traded within 10 days -> average traded value per day (large Rp 5B, mid Rp 1B,
small Rp 200M) -> not watchlist / not excluded -> fundamentals available -> positive trailing EPS ->
positive book equity -> at least 50% of the scoring metrics known.

Scorecards: every criterion maps a metric linearly from a "poor" to a "good" bound (e.g. Buffett:
ROE 8% -> 20%, debt/equity 1.5 -> 0.3, P/E 25 -> 10). Unknown metrics and criteria that do not apply
(industrial ratios of banks; bank-only criteria such as equity/assets) are skipped and the weights
renormalized; the score is scaled by `0.75 + 0.25 x coverage`. `QuantScorer.criteria(agent)` lists
a scorecard; the report shows each stock's breakdown. Overall = average of the selected investor
agents; with the Risk agent: `0.8 x investors + 0.2 x Risk` (`risk-weight`). The best
`top N x 3` (max 100) form the shortlist.

## 5. Stage 2 (AI) and the agentic patterns

`screening/agent`.

| Pattern         | Where                                                                                                  |
|-----------------|--------------------------------------------------------------------------------------------------------|
| Tool calling    | `ResearchTools` (`readArticle`, `searchNews`) and the crawlers/Tavily client that gather the headlines |
| ReAct           | `ResearchAgent`: "Thought:" -> tool calls -> observations, at most 3 turns, then a forced answer; tools are withdrawn when their limit is used (conditional tool calling) |
| Reflection      | `ReflectionValidator` (divergence from the scorecard > 35 points, verdict outside its band, broken philosophy rules such as Buffett 70+ with ROE < 10%, unknown metrics) + the critic in `InvestorPanel.reflect`, which sees its answer and the issues and keeps or revises it |
| Reflexion       | `ReflexionMemory`: issue kinds an agent repeated (2+ times in a run) become lessons in `screening_lesson`; the 3 most frequent are added to the agent's prompt in later runs. Within a run, an unusable answer (no JSON, no score, or neither strengths nor concerns) is retried once with the problem as feedback (cut off at the output limit, or the parse error), without sending the unusable reply back |
| Independent agents | `InvestorPanel.assess`: one call per agent and stock, personas in `ScreeningPrompts`               |
| Synthesis       | `SynthesisAgent` (Opus 5.5): summary, notes, conviction, thesis, adjustment of -5..+5 per candidate (top N and 5 alternates); deterministic fallback |

News sources (`screening/news`), polite per host (1-2 s), allowed by each site's robots.txt:

| Source      | What is read                                                           |
|-------------|------------------------------------------------------------------------|
| EmitenNews  | `/tag/<ticker>` result cards, articles                                 |
| IDX Channel | `/tag/<ticker>`, articles                                              |
| Investor.id | `/tag/<ticker>` (its `/search/` is disallowed), articles               |
| Pasardana   | latest listings (home, `/news`, `/market-analysis`) matched by ticker or company name; its search renders in the browser only |
| Tavily      | one news search per ticker (`TAVILY_API_KEY`), 1 credit per search     |

Headlines and article texts are cached (`news_article`); a source is asked about a ticker again
after 12 h; the research brief of a ticker is made once per day (`news_brief`) and reused by later
runs that day. `readArticle` only fetches URLs from the stock's own headline list.

## 6. Cost control

Budget: `budget-usd` (0.45) per run, hard cap; `synthesis-reserve-usd` (0.15) is kept free for the
synthesis while Stage 2 runs. Before each optional call the run checks the budget; when it is
reached, a news brief lists the headlines without analysis, an assessment keeps the quantitative
score (`QUANT_ONLY`), a flagged answer is not reviewed, the synthesis falls back - and the report says so.

| Lever                          | Effect                                                                           |
|--------------------------------|----------------------------------------------------------------------------------|
| Stage 1 in Java                | the AI sees only the shortlist (e.g. 75 of 840 stocks)                           |
| DeepSeek reasoning off         | `reasoning: {enabled: false}` (its reasoning tokens would be billed as output)  |
| OpenRouter `provider.sort: price` | cheapest provider of the model, except `provider-ignore` (OpenInference)      |
| Short JSON answers             | `agent-max-tokens` 450; thesis <= 40 words                                       |
| Shared prompt prefix           | system prompt + stock data identical for the six agents of a stock (cacheable)  |
| Reflection only when flagged   | the critic runs on flagged answers only                                          |
| News cache, daily brief        | no re-crawl / no new brief for the same ticker on the same day                   |
| Opus once                      | one synthesis call, reasoning effort `low`, compact table input                 |

Usage: every call is a row of `llm_usage` (stage, agent, ticker, model, prompt / completion /
reasoning / cached tokens, cost, duration, error); totals are on `screening_run`. A call that fails
transiently (stream reset, timeout, network error, HTTP 408 / 429 / 5xx) is retried `llm.model-retries`
times after 2 s, 4 s; each attempt is a row. The cost is
OpenRouter's `usage.cost`; when a provider reports none it is estimated from
`llm.prices` (marked `*`).

## 7. Settings (`application.yaml`, `neracalab.screening`)

| Property                          | Default                          | Meaning                                       |
|-----------------------------------|----------------------------------|-----------------------------------------------|
| `budget-usd` (`SCREENING_BUDGET_USD`) | 0.45                         | cost cap per run                              |
| `synthesis-reserve-usd`           | 0.15                             | kept for the synthesis                        |
| `shortlist-multiplier`, `max-shortlist`, `max-top-n` | 3, 100, 50    | shortlist size and limits                     |
| `llm-weight`, `risk-weight`       | 0.4, 0.2                         | score blending                                |
| `large-cap-min`, `mid-cap-min`    | Rp 10T, Rp 1T                    | tiers                                         |
| `min-price`, `min-avg-daily-value-*`, `max-trade-age` | 50, 5B / 1B / 200M, 10d | Stage 1 filters                |
| `require-positive-earnings`, `excluded-tickers`, `min-fundamentals-coverage` | true, [], 0.5 | Stage 1 filters |
| `llm.research-model`, `llm.agent-model` | `deepseek/deepseek-v4-flash-0731` | OpenRouter ids                       |
| `llm.synthesis-model` (`SCREENING_SYNTHESIS_MODEL`) | `anthropic/claude-opus-5.5` | synthesis                      |
| `llm.synthesis-effort`, `llm.provider-sort`, `llm.concurrency` | low, price, 8 | model options, parallel calls     |
| `llm.provider-ignore` (`SCREENING_PROVIDER_IGNORE`) | OpenInference | OpenRouter providers never used for the DeepSeek calls (comma-separated) |
| `llm.model-retries`, `llm.retry-backoff` | 2, 2s                     | retries of a transiently failed model call    |
| `news.tavily-api-key` (`TAVILY_API_KEY`) | empty                     | Tavily search (empty: no Tavily)              |
| `news.cache-ttl`, `news.max-age`  | 12h, 120d                        | news cache and age                            |
| `etl.fundamentals-max-age`        | 7d                               | fundamentals refresh                          |
| `etl.schedule.enabled` (`SCREENING_ETL_SCHEDULE_ENABLED`), `cron`, `zone` | true, `0 0 18 * * MON-FRI`, Asia/Jakarta | daily ETL |

## 8. Tables (`V1.0.10__schema_screening.sql`)

`stock_listing`, `fundamental_snapshot` (daily snapshot, `annual` JSONB), `news_article`,
`news_article_ticker`, `news_source_fetch`, `news_brief`, `screening_run` (`run_id` = the
`ingestion_job` id, cascade delete), `screening_candidate`, `screening_agent_score`,
`screening_lesson`, `llm_usage` (also the rows of the analyses, `llm_usage.analysis_id` of
`V1.0.15__schema_analysis.sql`). The script also extends the CHECK constraints for the `SCREENING`
permission and the `FUNDAMENTALS` / `SCREENING` job types. Details in the column comments.
`V1.0.17__schema_screening_selection.sql` adds `screening_run.tickers` (the selected stocks) and makes
`market_cap_tier` NULL for those runs; `ck_screening_run_scope` keeps exactly one of both.

## 9. Tests

| Test                          | What it proves                                                                      |
|-------------------------------|-------------------------------------------------------------------------------------|
| `NewsParsersTest`             | excerpts of the four sites' pages (headline lists, `src/test/resources/screening`) give the right headlines and dates (Jakarta time); a synthetic article page gives title, date and text; relevance matching |
| `YahooFundamentalsClientTest` | screener, quoteSummary and time series responses are read correctly (banks too)     |
| `QuantScreeningTest`          | metrics, scorecards, renormalization, overall blending, the funnel in order, shortlist = 3 x top N; selected stocks: same steps, tradability failures kept and flagged (liquidity of each stock's tier), data failures excluded and named, every eligible one shortlisted |
| `ScreeningAgentsTest`         | model options (reasoning off, Opus effort low), cost metering and budget, ReAct with a tool call, Reflection critic, JSON retry (cut off at the limit, no strengths and concerns), provider routing without OpenInference, transient-failure retries and their limit, synthesis rules and fallback (scripted model) |
| `FundamentalRepositoryTest`   | daily snapshots, fundamentals carried forward, stale detection (Docker Postgres)    |
| `ScreeningControllerTest`     | options, validation, a run end to end with the pipeline mocked, report JSON and PDF, 409 for an active run; selectable companies, selection validation, a selection run listed by scope, its PDF name, the tier-or-tickers constraint |
| `AccessControlTest`           | the screening APIs need SCREENING                                                   |

Run against a separate database while a backend processes jobs:
`DB_URL=jdbc:postgresql://localhost:5432/neracalab_test ./mvnw test`.

## 10. Limits

- Yahoo Finance is unofficial (no SLA, may throttle with HTTP 429; the run then uses the stored data).
- Listing board and IDX notations are not available publicly (see section 3).
- News sites can change their layout; a parser then finds fewer headlines (never an error).
- The report is generated by AI models from public data and is not investment advice.
