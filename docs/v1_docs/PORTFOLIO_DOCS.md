# Portfolio (Position, intraday prices, Performance, Analysis)

Sidebar › **Portfolio** (between Companies and Macro, permission `COMPANIES`) has three pages:

| Route | Page |
|---|---|
| `/portfolio` | **Position**: your stocks with the Fundamentals of Companies, the metrics, the intraday prices and the allocation by sector |
| `/portfolio/performance` | **Performance**: the portfolio against IHSG and LQ45 from 2022 to the end of 2026, the years of the sheet tracking_performance, the daily snapshots saved at 17:00 WIB ([Performance](#performance)) |
| `/portfolio/analysis` | **Analysis**: AI recommendations to sell, decrease, hold, increase or buy and the cash to keep, fundamental only or fundamental then technical ([Analysis](#analysis)) |

Each user has their own portfolio: the API answers the signed-in user's rows only, and another user's position cannot
be read, changed or deleted (404).

## Position

The page follows the sheet **Position** of `instruction/Position Portfolio.xlsx`. The user keeps four things per
stock and two for the portfolio; everything else is read from Companies › Fundamentals on every request (nothing is
copied or stored twice).

| Typed by the user | Where |
|---|---|
| average price (Rp per share), shares (whole shares, not lots), date in (first buy, not in the future) | **Add position** / the pencil of a row (`portfolio_position`) |
| total cash, total investment (the capital put in) | **Cash & investment** (`portfolio`) |

### Columns

Grouped as in the sheet; hovering a header shows its formula. The Stock column (ticker, company name) stays pinned on
the left; the table slides sideways with the scrollbar above it, the one below it, the wheel or by pressing the left
mouse button and dragging (`DragScroll`; links and buttons keep their click, only the table moves).

| Group | Column | Source / formula |
|---|---|---|
| Price | Last | the intraday price during the IDX session (green dot) when its session is newer than the stored daily close, else the last daily close (`price_daily`; a USD reporter at its IDX quote) |
| | Change, Change % | last − the close of the session before; ÷ that close |
| | Today | the day from the open to the close of the session: 5-minute prices on 09:00 - 16:15 WIB, the previous close dashed, the lunch break as a gap; hover reads a time and price |
| | Day G/L | change × shares |
| Weight | Port. % | avg value ÷ (average total + cash) |
| | Port. mkt % | market value ÷ (market total + cash) |
| Position | Avg price, Shares | typed |
| | Market value, Avg value | last × shares; avg price × shares |
| | G/L, % G/L | market value − avg value; ÷ avg value |
| Valuation | BVPS | equity attributable to the parent ÷ shares, latest filing (`v_key_metrics.book_value_per_share`) |
| | EPS fwd | the latest filing's profit to the parent made a year (Q1 × 4, H1 × 2, 9M × 4/3, FY × 1) ÷ shares: the Valuation tab's forward EPS |
| | D/E | interest-bearing debt ÷ equity (Metrics tab) |
| | EV/EBITDA, PER, PBV | EV ÷ EBITDA fwd; last ÷ EPS fwd; last ÷ BVPS |
| Dividend | Rp / share, Yield | cash dividends per share with an ex date this year (Asia/Jakarta), else last year, from the Profile tab (IDX and Yahoo, today's share basis); ÷ last |
| Company · Rp bn | Last filing | `9M 2026` |
| | Sector | the company's sector (coloured as in the allocation chart) |
| | EBITDA fwd, OP fwd | the latest filing's EBITDA / operating income made a year |
| | Shares out | latest market snapshot (Market & shares), else the filing's |
| | Market cap | last × shares out |
| | Total debt | **total liabilities** of the latest filing, as in the sheet |
| | Cash | cash and equivalents of the latest filing |
| | EV, EV/OP | total debt + market cap − cash (the sheet's formula); ÷ OP fwd |
| | Date in | typed |

A company reporting in USD (SMDR, INDY) is shown in rupiah as the Fundamentals' USD | IDR switch does it: the
balance sheet and BVPS at the period-end USD/IDR close, EBITDA, OP and EPS at the period's average rate (badge `USD`).
When IDX's amount of a dividend Yahoo knows too is more than 3 times off Yahoo's (INDY 2026-06-03: IDX "USD 10", i.e.
Rp 10, became Rp 178,350), Yahoo's amount is taken and the row says so (amber mark).

### Metrics

| Card | Metric | Formula |
|---|---|---|
| Portfolio | Total cash, Total investment | typed |
| | Total profit, Percentage profit | market total + cash − total investment; ÷ total investment |
| Stocks | Average total, Market total | Σ avg value; Σ market value |
| | Profit market, Percentage profit market | market total − average total; ÷ average total |
| Live | Today's change | Σ day G/L; ÷ the stocks' value at the closes of the session before |

With the sheet's 14 positions, cash Rp 4,852,232 and investment Rp 150,000,000 the page gives the sheet's totals to
the rupiah (average total 187,959,947, market total 269,340,500, total profit 124,192,732 = 82.80 %).

### Allocation by sector

A donut of each sector's market value and the cash as a share of market total + cash, below the table. Beside it every
slice with **all** its stocks (each ticker with its own weight), its value and a share bar. Colours: the validated
categorical palette (`--series-1` … `--series-8`, light and dark), a sector keeps its colour whatever its size; past
seven sectors the smallest fold into "Other"; the cash is neutral grey.

## Intraday prices

The stocks held in any portfolio get their intraday prices from **Yahoo Finance** (`/v8/finance/chart/HRTA.JK?range=1d&interval=5m`):
the latest session's last price and time, the previous close, day open / high / low / volume and the 5-minute bars.

| Piece | What it does |
|---|---|
| `IntradaySchedule` | every 5 minutes on weekdays (`0 */5 9-16 * * MON-FRI`, Asia/Jakarta); fetches only between 09:00 and 16:20 WIB (open to after post-trading) |
| `IntradayService` | reads each held company once per round (a company read less than 60 s ago is skipped), one round at a time; an HTTP 429 stops the round, the next one tries again; bars older than 10 days are deleted |
| `YahooIntradayClient` | through `PacedHttpClient` (the same pace as the daily prices, requests made ahead of a running "Update all") |
| `IdxSession` | the phase now: Pre-opening 08:45, Session 1 09:00 - 12:00 (Fri 11:30), Lunch break to 13:30 (Fri 14:00), Session 2 to 15:50, Pre-closing to 16:01, Post-trading to 16:15, else Closed; weekends closed (holidays: Yahoo has no bar, the page shows the latest session) |
| page | the Live card (phase, today's change, when the prices were read, **Refresh**); the page reads the portfolio again every minute while the IDX day is on and the tab is visible |

**Safe for the portfolios:** the intraday code writes `intraday_quote` and `intraday_bar` only. The positions, the
cash / investment and `price_daily` (read by Fundamentals and Technicals) are never written; `IntradayTest` proves the
position row and the daily prices are identical after a refresh. Once the evening price ingestion stores the day's
official close, the page shows it again (an intraday session is used only when it is newer than the last daily close).

| Setting | Default | |
|---|---|---|
| `neracalab.intraday.schedule.enabled` (`INTRADAY_SCHEDULE_ENABLED`) | `true` | the schedule on / off |
| `neracalab.intraday.schedule.cron` (`INTRADAY_SCHEDULE_CRON`) | `0 */5 9-16 * * MON-FRI` | every 3 minutes: `0 */3 9-16 * * MON-FRI` |
| `neracalab.intraday.session-start` / `session-end` | `09:00` / `16:20` | the hours the schedule fetches |
| `neracalab.intraday.interval` | `5m` | Yahoo bar size |
| `neracalab.intraday.keep-days` | `10` | bars kept |

## Performance

Portfolio › Performance (`/portfolio/performance`) is the sheet `tracking_performance` of
`instruction/Position Portfolio.xlsx`.

**Years** (`portfolio_performance_year`, typed on the page): one row per year, as the sheet's "2022-2023" ... "2026-2027"
rows.

| Field | Sheet | Rule |
|---|---|---|
| start capital | Total Invest &lt;year before&gt; | empty: the market value + cash at the end of the year before |
| new investment | Total Invest &lt;year&gt; | money put in during the year (negative: taken out); empty = 0 |
| cash, market value, average value | Cash, Market Value, Avg Value | the year end; the year's last daily snapshot takes precedence, the live portfolio for the current year |
| dividend | Deviden | |
| IHSG / LQ45 YTD | IHSG YTD, LQ45 YTD | kept as recorded; the page computes both from `index_daily` |

POT G/L = market value + cash - start capital - new investment; POT G/L % = POT G/L / start capital;
Deviden % = dividend / (market value + cash). IHSG / LQ45 of a year = the close at the year end (to date) / the last
close of the year before - 1. The sheet's rows give 2022 +48.47%, 2023 -7.99%, 2024 -1.28%, 2025 +114.42%.

**Daily snapshots** (`portfolio_performance_daily`): `PerformanceSchedule` runs every 15 minutes from 17:00 to 23:45
WIB, Monday - Friday, and saves each portfolio once a day: first today's IHSG / LQ45 bars (the index ingestion; final
after the 17:00 session close cutoff), then - if IDX traded today (an IHSG bar of today, or an intraday session of
today) - the stocks' latest prices (the intraday refresh), then the portfolio as Position shows it. Stored: positions,
priced, priced today, cash, market value, average value, profit / loss (market - average) and its %, the day's gain,
total investment, the year's start capital and new investment, the year's POT G/L and POT G/L % so far, the IHSG and
LQ45 close with their change of the day and of the year. An exchange holiday saves nothing; a backend down at 17:00
saves the day at its next round that evening. **Save today** (`POST .../snapshots`) saves at once from 17:00 WIB on a
weekday (409 before, on a weekend or a holiday) and replaces the day's snapshot.

**Chart**: the change since the last close of the year before the range (`ALL` = 2022 - 2026, or one year) of IHSG and
LQ45 (daily closes) and of the portfolio: each year's POT G/L % chained, a point each 31 December from the year row,
the daily snapshots within a year and today's live value. The axis runs to 31 December of the range's last year (2026
at least). A second chart puts the years side by side.

| Setting | Default | |
|---|---|---|
| `neracalab.performance.schedule.enabled` (`PERFORMANCE_SCHEDULE_ENABLED`) | `true` | the schedule on / off |
| `neracalab.performance.schedule.cron` | `0 0/15 17-23 * * MON-FRI` | Asia/Jakarta |
| `neracalab.performance.save-from` | `17:00` | the earliest time of a day's snapshot (WIB) |
| `neracalab.performance.first-year` / `last-year` | `2022` / `2026` | the chart's `ALL` range (later years follow the calendar) |

## Analysis

Portfolio › Analysis (`/portfolio/analysis`, instruction/INSTRUCTION.md) recommends, for the signed-in user's
portfolio, which stocks to **sell** or **decrease**, which to **hold** or **increase**, which new stocks to **buy** with
the capital, and how much **cash** to keep. Each run is a background job (`ingestion_job` type `PORTFOLIO_ANALYSIS`, one
at a time); its report is saved (`/portfolio/analysis/{id}`) and listed on the page. Reading needs `COMPANIES`; starting
a run `SCREENING` too (it calls the AI models, as the Screening features do). Each user sees only their own runs.

**The form**: the **analysis steps** - **1. Fundamental, then 2. Technical** (the default, below) or **Fundamental
only** -, the most stocks to hold (default 25, 1 - 50), the stocks to choose from - **all companies (bulk)** of
Companies › Fundamentals, or **selected stocks** (searchable multi-select with each company's Fundamental and Technical
Score, at most 100) - and capital to add (optional). The capital = market value + cash + the added capital.

**Fundamental only** (`steps` `FUNDAMENTAL`; `portfolio_analysis_run.steps`, V1.0.61) runs step 1 with the same limits,
the fundamental agent and the synthesis - no Technical (Score) is read and the technical agent is never called. A new
stock is bought, and a holding increased, with a fundamental PASS alone; half-Kelly assumes an even payoff (b = 1: f* =
2p - 1, at most 15% at a final fundamental score of 100); the price is the latest stored close (as Portfolio › Position
reads it); the synthesis has its own prompt (no timing); the fallback sells a holding with a FAIL, keeps or raises one
with a PASS and buys new PASS stocks best fundamental score first. Every other limit is the same. The report shows only
the steps the run used.

**The method**, in order - fundamental first, technical after, the two scores never combined:

| Step | What | Model |
|---|---|---|
| 1. Fundamental (rules) | the Fundamental (Score) of every stock of the universe (the company list's column, `QuantScoreBoard`): at least `min-fundamental-score` (50), the best `max stocks x 3` (at most 120) pass | none |
| 2. Technical (rules) | of step 1's stocks only, the Technical (Score) (`TechnicalScoreBoard`): the best `max stocks x 2` (at most 60) pass | none |
| 3. Fundamental agent | each stock of step 2 and every holding: score 0 - 100 and PASS / FAIL from the six scorecards (Buffett, Munger, Lynch, Fisher, Keith Gill, Risk) and their metrics, in Buffett's spirit (a durable business at a sensible price; no PASS with a Risk scorecard below 30) | DeepSeek |
| 4. Technical agent | only the stocks the fundamental agent passed, and the holdings: score, stance (BUY / ACCUMULATE / WAIT / AVOID), entry, stop loss and target from the four presets (Murphy, Keith Gill, Paul Tudor Jones, Druckenmiller) | DeepSeek |
| 5. Sizing | half-Kelly per idea: f* = p - (1 - p) / b, p from the final fundamental score (0.35 at 0 to 0.65 at 100), b the technical plan's reward / risk; x 0.5, at most 33% | none |
| 6. Synthesis | the portfolio: an action and a target weight per holding and idea, the cash target, an executive summary and risk notes | Claude Opus 5.5 |
| 7. Limits | enforced in Java on the synthesis' proposal (below) | none |

The holdings always go through steps 3 - 7 (they need a recommendation); a holding steps 1 or 2 would have left out is
flagged. The agentic patterns of the screening: independent agents (one call per stock and agent), a retry of an
unusable reply, **Reflection** (a validator without a model: more than 35 points from the rules score, a verdict or a
stance outside its score band, a PASS with a Risk scorecard below 30, an invalid trade plan; a critic reviews the flagged
answers), **Reflexion** (an issue kind an agent repeats in a run becomes a lesson in `portfolio_analysis_lesson`, added to
its later prompts). An agent's final score = 0.6 x the rules score + 0.4 x its own (`llm-weight`).

**The limits** (`PortfolioAllocator`), whatever the synthesis proposes:

- a new stock is bought only with a fundamental PASS and a technical BUY or ACCUMULATE; a holding is increased only with
  both;
- no stock above **33%** of the capital (`max-position-weight`), no sector above **33%** (`max-sector-weight`; the
  sector's stocks are scaled down together - holdings too, which then become DECREASE);
- at most **max stocks** stocks (the smallest new buys are left out first, then the smallest holdings);
- no new position below 1% (`min-position-weight`); the stocks and the cash within the capital (buys scaled down first;
  the cash target of the synthesis kept; the cash never negative);
- shares in whole lots of 100 at the last close (`lot-size`): a buy rounds down, a decrease sells whole lots (enough to
  reach the target or below), an increase buys whole lots on top of an odd lot, a HOLD keeps the exact shares, a SELL
  sells everything.

Without the synthesis model (budget, failure) the fallback proposes by rules: a holding with a fundamental FAIL and a
technical AVOID is sold, one with either halved, one with both good raised to its half-Kelly weight, the rest held; new
stocks that passed both agents are bought at their half-Kelly weight in step 2's order. The report says so.

**The report**: the summary and risk notes, the recommendations (action, now and target weight, the trade in shares,
lots and rupiah at the last close, the fundamental verdict, the technical stance and plan, the half-Kelly weight, why,
what the limits changed; a row opens the agents' reasoning), the sectors and cash before and after, the limits checked,
how the stocks were chosen (the five steps with their counts), the stocks analysed but not chosen, those the rules steps
left out (step 2's, and step 1's nearest misses of a bulk run), notes and lessons, the model usage and cost.

| Setting (`neracalab.portfolio-analysis`) | Default | |
|---|---|---|
| `budget-usd` (`PORTFOLIO_ANALYSIS_BUDGET_USD`) | `0.75` | hard cost cap of one run (optional calls are skipped instead) |
| `synthesis-reserve-usd` | `0.35` | kept free for the synthesis while the agents run |
| `default-max-stocks`, `max-stocks-limit`, `max-selected` | `25`, `50`, `100` | the form's limits |
| `min-fundamental-score`, `fundamental-multiplier`, `max-fundamental-shortlist` | `50`, `3`, `120` | step 1 |
| `technical-multiplier`, `max-technical-shortlist` | `2`, `60` | step 2 |
| `llm-weight` | `0.4` | an agent's final score |
| `max-position-weight`, `max-sector-weight` | `0.33`, `0.33` | the 33% limits |
| `kelly-fraction`, `min-position-weight`, `lot-size` | `0.5`, `0.01`, `100` | sizing |
| `agent-max-tokens`, `synthesis-max-tokens` | `700`, `20000` | output limits |

The models and their prices are those of `neracalab.screening.llm`. The recommendations come from AI models and fixed
rules on stored data: a decision aid, not investment advice; check prices and lots before trading.

## API

All `COMPANIES`; every call answers the whole portfolio as it now stands.

```bash
curl -H "$AUTH" http://localhost:8080/api/v1/portfolio
curl -H "$AUTH" -X PUT  -H 'Content-Type: application/json' -d '{"cash":4852232,"totalInvestment":150000000}' \
     http://localhost:8080/api/v1/portfolio/settings
curl -H "$AUTH" -X POST -H 'Content-Type: application/json' \
     -d '{"exchange":"IDX","ticker":"HRTA","averagePrice":317.11,"shares":80600,"dateIn":"2022-05-01"}' \
     http://localhost:8080/api/v1/portfolio/positions
curl -H "$AUTH" -X PUT  -H 'Content-Type: application/json' -d '{"averagePrice":320,"shares":80600,"dateIn":"2022-05-01"}' \
     http://localhost:8080/api/v1/portfolio/positions/7
curl -H "$AUTH" -X DELETE http://localhost:8080/api/v1/portfolio/positions/7
curl -H "$AUTH" -X POST   http://localhost:8080/api/v1/portfolio/intraday/refresh    # the user's stocks, now
curl -H "$AUTH" "http://localhost:8080/api/v1/portfolio/performance?range=ALL"           # or range=2025
curl -H "$AUTH" -X PUT  -H 'Content-Type: application/json' \
     -d '{"startCapital":148975379,"newInvestment":0,"cash":27474186,"marketValue":291955200,"averageValue":145126892,"dividend":6029988,"ihsgYtd":0.2192,"lq45Ytd":0.0219}' \
     http://localhost:8080/api/v1/portfolio/performance/years/2025
curl -H "$AUTH" -X DELETE http://localhost:8080/api/v1/portfolio/performance/years/2025
curl -H "$AUTH" -X POST   http://localhost:8080/api/v1/portfolio/performance/snapshots   # save today (from 17:00 WIB)
```

| Endpoint | Notes |
|---|---|
| `GET /api/v1/portfolio` | `summary` (the metrics, `dayGain`, `dayGainPct`), `positions` (every column above, `priceSource` INTRADAY / DAILY, `priceTime`, `intraday` {sessionDate, previousClose, open, high, low, volume, marketTime, fetchedAt, points [[epoch s, close]]}, `notes`), `allocation`, `live` {phase, phaseLabel, trading, sessionDate, updatedAt, everyMinutes, scheduled}, `notes` |
| `PUT /settings` | 400 on a negative amount |
| `POST /positions` | 201; 404 unknown company, 409 already held, 400 price ≤ 0, shares not whole / < 1, date in the future |
| `PUT / DELETE /positions/{id}` | 404 for a position that is not the user's |
| `POST /intraday/refresh` | reads the user's stocks from Yahoo now (any time: the latest session Yahoo has) |
| `GET /performance?range=` | `ranges` (ALL, 2022 ... 2026), `chart` {range, from, to, base, points [{date, portfolio, ihsg, lq45, kind BASE / YEAR_END / SNAPSHOT / LIVE}], portfolio, ihsg, lq45, latest, notes}, `years` [{year, status CLOSED / CURRENT / FUTURE, startCapital, startFrom, newInvestment, cash, marketValue, averageValue, endFrom LIVE / SNAPSHOT / TYPED, endDate, profit, profitPct, dividend, dividendPct, ihsg, lq45, ihsgRecorded, lq45Recorded, portfolioVsIhsg, row}], `snapshots` (latest 60), `live`, `saving` {scheduled, saveFrom, canSaveNow, savedToday, next, lastSaved, saved, reason}; 400 unknown range |
| `PUT / DELETE /performance/years/{year}` | 400 negative amounts, a YTD below -100%, a year outside 2000 - next year; 404 deleting a year without a row |
| `POST /performance/snapshots` | `{snapshot, message, performance}`; 409 before 17:00 WIB, on a weekend or an exchange holiday |
| `GET /analyses/options` | the limits, models, budget, the portfolio (cash, market value, held tickers) and every company with its Fundamental and Technical Score |
| `POST /analyses` | `{"mode":"BULK"\|"SELECTED","steps":"FUNDAMENTAL_TECHNICAL"\|"FUNDAMENTAL","maxStocks":25,"tickers":[...],"addedCapital":0}` (steps default FUNDAMENTAL_TECHNICAL): 202 with the queued run; 400 an unknown mode or steps, maxStocks outside 1 - 50, SELECTED without tickers or with unknown ones, tickers with BULK, a negative capital; 403 without SCREENING |
| `GET /analyses?limit=` | the user's runs, most recent first |
| `GET /analyses/{id}` | `run`, `capital`, `funnel`, `synthesis`, `allocation` (cash and sectors before / after, buy and sell value, limits), `checks`, `notes`, `candidates` (position, both scores and ranks, stage, the two agents' answers and reflection, Kelly, action, recommendation), `usage`, `disclaimer`; 404 another user's |

Frontend route handlers: `/api/portfolio`, `/api/portfolio/settings`, `/api/portfolio/positions[/{id}]`,
`/api/portfolio/intraday/refresh`, `/api/portfolio/performance`, `/api/portfolio/performance/years/{year}`,
`/api/portfolio/performance/snapshots`, `/api/portfolio/analyses[/options|/{id}]`.

## Tables (V1.0.53, V1.0.54, V1.0.59, V1.0.60)

| Table | Rows |
|---|---|
| `portfolio` | one per user: `cash`, `total_investment` (≥ 0) |
| `portfolio_position` | `user_id`, `company_id` (unique together), `average_price` (> 0), `shares` (whole, > 0), `date_in` |
| `intraday_quote` | one per company: `session_date`, `price`, `previous_close`, `day_open / high / low`, `volume`, `market_time`, `session_start / end`, `fetched_at` |
| `intraday_bar` | `(company_id, bar_time)`: `session_date`, OHLC, `volume` |
| `portfolio_performance_year` | `(user_id, year)`: `start_capital`, `new_investment`, `dividend`, `cash`, `market_value`, `average_value`, `ihsg_ytd`, `lq45_ytd`, `note` |
| `portfolio_analysis_run` | one run (`run_id` = the job): user, mode, `steps` (V1.0.61), tickers, max stocks, added capital, budget, `capital`, `funnel`, `synthesis`, `allocation`, `checks`, `notes`, tokens and cost |
| `portfolio_analysis_candidate` | `(run_id, ticker)`: position, close, both scores and ranks, `stage`, `fundamental_ai`, `technical_ai`, `kelly`, `action`, `recommendation`, conviction, thesis, red flags |
| `portfolio_analysis_lesson` | Reflexion memory of the two agents (`agent`, `issue_kind`, `lesson`, `occurrences`, `active`); `llm_usage.portfolio_analysis_id` the model calls |
| `portfolio_performance_daily` | `(user_id, snapshot_date)`: `captured_at`, `source` SCHEDULE / MANUAL, `positions`, `priced`, `priced_today`, `cash`, `market_value`, `average_value`, `profit_loss(_pct)`, `day_gain(_pct)`, `total_investment`, `year_start_capital`, `year_new_investment`, `ytd_profit(_pct)`, `ihsg_close / date / change_pct / ytd_pct`, `lq45_*` |

## Code

| Backend (`portfolio/`) | |
|---|---|
| `PortfolioController`, `PortfolioService`, `PortfolioRepository`, `PortfolioViews` | the page's API and columns |
| `intraday/IntradayService`, `IntradaySchedule`, `IntradayRepository`, `YahooIntradayClient`, `IdxSession` | intraday prices |
| `performance/PerformanceController`, `PerformanceService`, `PerformanceSchedule`, `PerformanceRepository`, `PerformanceViews` | Performance: years, daily snapshots, chart |
| `analysis/PortfolioAnalysisController`, `PortfolioAnalysisQueue`, `PortfolioAnalysisService`, `AnalysisSteps`, `PortfolioAgents`, `PortfolioLessons`, `PortfolioSynthesis`, `PortfolioAllocator`, `PortfolioPrompts`, `PortfolioAnalysisRepository` | Analysis: the steps, the agents, the synthesis, the limits |

| Frontend | |
|---|---|
| `app/(dashboard)/portfolio/page.tsx`, `portfolio/performance/page.tsx` | pages |
| `components/portfolio/portfolio-position-client.tsx` | metrics, Live card, positions table |
| `components/portfolio/allocation-chart.tsx`, `intraday-sparkline.tsx`, `drag-scroll.tsx`, `position-sheet.tsx` | allocation, the day's chart, sideways sliding, add / edit / cash forms |
| `components/portfolio/performance-client.tsx`, `performance-year-sheet.tsx` | Performance: range, charts, years, snapshots, the year form |
| `components/portfolio/analysis-form.tsx`, `analysis-runs-table.tsx`, `analysis-report.tsx`; `lib/portfolio-analysis.ts` | Analysis: the form, the runs, the report; types |
| `lib/portfolio.ts`, `lib/portfolio-performance.ts` | types, sections, sector colours; Performance types and series colours |

## Tests

| Test | Covers |
|---|---|
| `PortfolioTest` | every column against `v_key_metrics` and the balance sheet (HRTA), the sheet's formulas and metrics, SMDR in IDR, the allocation adding up, one portfolio per user; the API's 201 / 400 / 403 / 404 / 409 |
| `PortfolioDividendTest` | this year's dividends else last year's, today's share basis after a split, IDX vs Yahoo mismatch, the allocation |
| `IdxSessionAndYahooParseTest` | the IDX phases (Monday - Thursday, Friday, weekend), Yahoo's intraday chart (session date, bars of other days and without a close left out) |
| `IntradayTest` | a refresh shows the session as the last price with the day's change, a fresh quote is not fetched again, an older session leaves the daily close, the schedule does nothing outside the session, the API - and the position and the daily prices unchanged (Yahoo stubbed) |
| `PerformanceTest` | the sheet's POT G/L, POT G/L % and Deviden % (2022 - 2026 rows), the start capital from the year before, IHSG / LQ45 of the calendar year from `index_daily`, the chart (base, daily index, chained year ends, to 31 December 2026, one year), the snapshot (not before 17:00, not on a weekend or a holiday, once a day by the schedule, replaced by Save today, its figures against Position and `index_daily`, the live year to date), the API's 400 / 403 / 404 (Yahoo stubbed) |
| `PortfolioAnalysisRulesTest` | the steps in order (a stock with the best chart but outside step 1 is out; step 2 ranks only step 1's), holdings always analysed and flagged, half-Kelly, the limits whatever is proposed (33% per stock and sector, max stocks, too small, not eligible, whole lots, odd lots, decreases, increases, the cash), the fallback, the validators, the synthesis' normalization; fundamental only: step 1's stocks to the agents, no technical rank or flag, even-payoff Kelly, a PASS buys, the fallback |
| `PortfolioAnalysisControllerTest` | a whole run of selected stocks with a scripted model (every call metered): the steps, a flagged fundamental answer revised by the critic, every bought stock PASS and BUY / ACCUMULATE, a synthesis asking 40% capped at 33%, at most the stocks asked, whole lots, the cash target, the checks, the holdings held at their exact shares, the list and another user's 404; a fundamental-only run (no technical call, the fundamental-only synthesis, every buy a PASS with an even payoff); the API's 400 / 403 / 404 |
