# AI technical screening

**Screening > Technicals (All)** and **Screening > Technicals (Selected)** screen stocks through their charts only -
price movements, moving averages and indicators, no fundamentals and no news - with four technical agents: **Keith
Gill**, **John Murphy**, **Paul Tudor Jones** and **Stanley Druckenmiller**. Each run ends with a ranking of the top N
and, for every stock, **where to buy, where to sell (the target) and where to stop the loss**, as the preset summaries
of Companies > Technicals give them.

- **Technicals (All)** (`/screening/technicals`) screens a market-cap tier of the exchange (Large cap, Mid cap or
  Small cap; the same tiers, price, trading and liquidity minimums as Fundamental (All), `neracalab.screening`). Its
  price movements are the daily bars of every listing of the screening universe, `listing_price_daily`, loaded on
  **Ingestion > Screening Data IDX** ("Update price history") and by the run itself for the listings of its tier that
  are behind.
- **Technicals (Selected)** (`/screening/technicals/selected`) screens stocks chosen from the companies table, read
  from the daily prices already stored in the database (`price_daily`, Ingestion > Price Ingestion) exactly as
  Companies > Technicals reads them; it loads nothing from the internet.

Permission `SCREENING`. Each run is a background job (`ingestion_job` type `TECHNICAL_SCREENING`); its report is saved
(`/screening/technicals/{id}`, `/screening/technicals/selected/{id}`) and listed on its page.

## 1. Models

The models are those of the stock screening (`neracalab.screening.llm`):

| Step | Model | Calls |
|------|-------|-------|
| The four technical agents and the reflection critic | `deepseek/deepseek-v4.1-flash-1017` (`SCREENING_AGENT_MODEL`), reasoning off, cheapest provider, JSON replies | one per agent and stock, one more per flagged answer |
| The synthesis | `anthropic/claude-opus-5.5` (`SCREENING_SYNTHESIS_MODEL`), reasoning effort low | one per run |

Every call is metered in `llm_usage` (column `technical_screening_id`); a run never exceeds `budget-usd`
(`TECHNICAL_SCREENING_BUDGET_USD`, default $0.50): an agent without a call keeps its rules summary and its preset's
levels, a synthesis without the model is written deterministically.

Measured (2026-10-25, first runs): a large-cap top 30 with all four agents read 102 eligible stocks, shortlisted 90
(434 model calls: 360 answers, 73 revised after the reflection, one synthesis) for $0.39 in 6 minutes; 25 of the 360
agent answers had no valid trade plan after the reflection and show their preset's levels. Five selected stocks: 24
calls, $0.06, 27 seconds. Every final plan kept stop loss < buy < sell.

## 2. A run

1. **Data.** A tier: today's market data of every listing (the Yahoo screener, as Fundamental (All)) unless stored,
   then the daily bars of the listings passing the tradability filters whose bars do not reach the last completed
   trading day (`price-history.refresh-before-run`; about 1.5 s per listing behind, none when up to date). Selected
   stocks: the stored daily prices.
2. **Stage 1, the rules (no AI).** For every stock the indicator snapshot ([../indicator-snapshot.md](../indicator-snapshot.md))
   and the four preset summaries of Companies > Technicals (`SnapshotCalculator`, `PresetSummaries`): stance, score,
   buy / sell / stop and the evidence. An agent's **prior** is its preset's score as 0 - 100
   (`50 + 50 x score / max`); the stock's prior overall the average of the selected agents'. A tier keeps the stocks
   passing the filters below and shortlists the best `top N x shortlist-multiplier` (at most `max-shortlist`); a
   selection shortlists every eligible stock.
3. **Stage 2, the agents.** One DeepSeek call per agent and stock: the system prompt, the chart dossier (the snapshot
   without the swing lists, the three nearest support and resistance zones each side, the tradability flags) and the
   persona with its rules summary (stance, score, the rules trade plan, the evidence) and its Reflexion lessons. The
   agent answers a score, a stance (BUY, ACCUMULATE, WAIT, AVOID), a thesis, supports and risks, and a trade plan
   (buy price and type - LIMIT for a pullback at or below the close, STOP for a breakout above it - sell, stop loss).
4. **Reflection.** A validator checks each answer without a model; a flagged answer goes to the critic, which keeps or
   revises it:

   | Code | When |
   |------|------|
   | `DIVERGENCE` | the score is more than 35 points from the agent's prior |
   | `STANCE` | the stance is outside its score band (BUY 65-100, ACCUMULATE 50-79, WAIT 35-64, AVOID 0-49, 5 points tolerance) |
   | `RULE` | the trader's own rule: Tudor Jones buying below the 200-day average; Murphy buying below both the SMA 50 and the SMA 200; Druckenmiller scoring 70+ a laggard (6-month percentile below 50) while IHSG is below its 200-day average; Gill buying in the top 15% of the 52-week range without a base |
   | `LEVELS` | the trade plan is incomplete, not stop loss < buy < sell, a LIMIT buy above the close (+2%) or a STOP buy below it (-2%), the buy beyond -40% / +30% of the close, more than 40% risk below the buy, or a target above 3 times the buy |
   | `UNKNOWN_SIGNAL` | `signalsUsed` cites keys that are not in the dossier |
   | `EMPTY` | no score or no thesis |

   An issue kind an agent repeats at least twice in a run becomes a lesson in `technical_screening_lesson`, added to
   that agent's prompt in later runs (set `active = false` to drop one).
5. **Scores and plans.** An agent's final score = `0.6 x prior + 0.4 x AI score` (`llm-weight`), the prior alone
   without a model answer (`PRIOR_ONLY`). Its trade plan is its own levels, rounded to valid IDX prices (`TradePlan`:
   a LIMIT buy and the target to the nearest tick, a STOP buy up, the stop loss down) and checked again; without a
   valid plan, its preset's rules levels. Overall = the average of the selected agents.
6. **Synthesis (Opus)** of the top N and 5 alternates, with the market (IHSG against its 200-day average, breadth):
   an executive summary, market notes, and per stock an action, a conviction, a thesis, **one trade plan** built from
   the agents' and an optional adjustment of -5 to +5 points with a reason. A synthesis plan that fails the same checks
   is replaced by the best agent's (of the agents whose stance is the stock's action, the highest final score).
7. **Report** saved; the final ranking is sorted by overall + adjustment.

### Stage 1 filters of a tier

| Step | Kept |
|------|------|
| tier | market cap in the tier (`large-cap-min` Rp 10T, `mid-cap-min` Rp 1T) |
| price | share price at least `min-price` (Rp 50) |
| traded | a trade in the last `max-trade-age` (10 days) |
| liquidity | average traded value per day at least the tier's minimum (Rp 5B / 1B / 200M) |
| board | not on the watchlist board, not in `excluded-tickers` |
| history | at least `min-bars` (120) daily bars stored, the last one within `max-bar-age` (7 days) of the as-of day |

The as-of day is the latest IHSG bar or listing bar. Breadth and the relative-strength percentiles of a tier compare
a stock with every listing of the exchange with stored bars (the whole universe, not only the companies table).

A selected stock without daily prices, or with fewer than `min-bars`, is left out and named in the notes; one with a
price below Rp 50, traded value below its tier's minimum, stale prices, thin liquidity or auto-rejection days is kept
and shows the reason among its red flags (as Fundamental (Selected) does).

## 3. Configuration (`neracalab.technical-screening`)

| Property | Default | Meaning |
|----------|---------|---------|
| `budget-usd` (`TECHNICAL_SCREENING_BUDGET_USD`) | 0.50 | hard cost cap of one run |
| `synthesis-reserve-usd` | 0.20 | kept free for the synthesis while the agents run |
| `shortlist-multiplier` | 3 | Stage 1 shortlist of a tier = top N x 3 |
| `max-shortlist` | 100 | largest shortlist; most stocks one selection takes |
| `max-top-n` | 50 | largest top N |
| `llm-weight` | 0.4 | weight of the AI score in an agent's final score |
| `min-bars` | 120 | fewest stored daily bars |
| `max-bar-age` | 7d | a last bar older than this before the as-of day is stale |
| `agent-max-tokens` | 700 | output limit of one agent answer |
| `synthesis-max-tokens` | 16000 | output limit of the synthesis (thinking included) |
| `price-history.years` | 5 | how far back the first load of a listing reaches |
| `price-history.refresh-before-run` | true | a tier screening first loads the bars of its listings that are behind |

## 4. Price history of the screening universe

`listing_price_daily` (listing, trading date, open, high, low, close, adjusted close, volume; split-adjusted as traded,
IDR) holds the daily bars of every listing of `stock_listing`. `ListingPriceService` loads them through the configured
price provider (Yahoo Finance chart API by default), one paced request per listing:

- a listing without bars gets `price-history.years` of history, a stored one the days from 10 days before its last
  bar; a listing up to the last completed trading day (after the 17:00 session close cutoff) needs no request;
- the bars are cleaned like the company prices (no unfinished bar of today, no holiday placeholders);
- when a fetched close of a stored day differs by more than 0.5% (a split or other re-adjustment), the whole window is
  fetched again and replaces the stored bars;
- HTTP 429 stops the update (the rest waits for the next run); a symbol the provider does not know is counted.

**Ingestion > Screening Data IDX** shows what is stored and starts a run (job type `LISTING_PRICES`, one active per
exchange; "Load every listing again" replaces every listing's bars). The first load of every IDX listing takes about
25 minutes.

## 5. API

| Method | Path | Body / query | Answer |
|--------|------|--------------|--------|
| GET | `/api/v1/technical-screenings/options` | - | exchanges, market-cap tiers (with their ranges), the four agents, top N limits, budget, models, `minBars`, stored data (listings, with bars, latest bar, market data day, active price history job) |
| POST | `/api/v1/technical-screenings` | `{"exchange":"IDX","marketCapTier":"LARGE","topN":25,"agents":["GILL","MURPHY","TUDOR_JONES","DRUCKENMILLER"]}` or `{"exchange":"IDX","tickers":["BBCA","TLKM"],"topN":2,"agents":[...]}` | 202 with the queued run |
| GET | `/api/v1/technical-screenings/companies?exchange=IDX` | - | the companies of the companies table with their stored daily bars and market cap, `maxSelected`, `minBars` |
| GET | `/api/v1/technical-screenings?limit=50&scope=TIER\|SELECTION\|ALL` | - | runs, most recent first |
| GET | `/api/v1/technical-screenings/{id}` | - | the report: run, funnel, market, synthesis, notes, candidates (technicals, the four rules summaries, scores, action, conviction, trade plan, red flags, every agent's score, stance, plan, reasoning and reflection), token usage |
| POST | `/api/v1/listing-prices/ingestions?exchange=IDX[&full=true]` | - | 202 with the new `LISTING_PRICES` job (200 with the active one) - permission `INGESTION` |
| GET | `/api/v1/listing-prices/status?exchange=IDX` | - | listings, with bars, latest bar, rows, history years, active job - `INGESTION` or `SCREENING` |

Agents are accepted by name, preset or label (`ptj`, `Paul Tudor Jones`, `keith gill`), kept in the fixed order Gill,
Murphy, Tudor Jones, Druckenmiller. Selected tickers must be in the companies table (at most `max-shortlist`); the top N
of a selection is at most the number selected.

## 6. Tables

| Table | Holds |
|-------|-------|
| `technical_screening_run` | one run (PK = `ingestion_job.job_id`): exchange, tier or tickers (one of the two), top N, agents, as-of day, funnel counts and steps, the market, synthesis, notes, budget, tokens and cost |
| `technical_screening_candidate` | the shortlist: close, as-of day, market cap, key technicals (`metrics`), the four rules summaries (`rules`), prior overall and rank, overall score, adjustment, final rank, selected, action, conviction, the final trade plan (`plan`), thesis, red flags |
| `technical_screening_agent_score` | per candidate and agent: prior, AI and final score, stance, thesis, supports, risks, trade plan (its source AI or RULES), reflection, status `ASSESSED` / `REVISED` / `PRIOR_ONLY` |
| `technical_screening_lesson` | Reflexion memory of the technical agents |
| `listing_price_daily` | daily bars of the screening universe |

Schema: `V1.0.30__schema_technical_screening.sql`. Code: `technical/screening` (run, rules, agents, synthesis,
controller), `screening/data/ListingPrice*` (the price history). Tests: `TechnicalScreeningRulesTest` (trade plans, the
validator, the synthesis' normalization, Stage 1 of a tier on the IDX fixtures, the dossier),
`TechnicalScreeningControllerTest` (the API and a whole run of three companies with a scripted chat model (every call metered): rules,
agents, a flagged answer revised by the critic, the synthesis' trade plans), `ListingPriceServiceTest` (first load,
incremental update, re-adjustment, rate limit).
