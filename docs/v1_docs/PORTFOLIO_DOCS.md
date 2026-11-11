# Portfolio (Position, intraday prices)

Sidebar › **Portfolio** (between Companies and Macro, permission `COMPANIES`) has two pages:

| Route | Page |
|---|---|
| `/portfolio` | **Position**: your stocks with the Fundamentals of Companies, the metrics, the intraday prices and the allocation by sector |
| `/portfolio/performance` | **Performance**: planned for a later step (placeholder) |

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
```

| Endpoint | Notes |
|---|---|
| `GET /api/v1/portfolio` | `summary` (the metrics, `dayGain`, `dayGainPct`), `positions` (every column above, `priceSource` INTRADAY / DAILY, `priceTime`, `intraday` {sessionDate, previousClose, open, high, low, volume, marketTime, fetchedAt, points [[epoch s, close]]}, `notes`), `allocation`, `live` {phase, phaseLabel, trading, sessionDate, updatedAt, everyMinutes, scheduled}, `notes` |
| `PUT /settings` | 400 on a negative amount |
| `POST /positions` | 201; 404 unknown company, 409 already held, 400 price ≤ 0, shares not whole / < 1, date in the future |
| `PUT / DELETE /positions/{id}` | 404 for a position that is not the user's |
| `POST /intraday/refresh` | reads the user's stocks from Yahoo now (any time: the latest session Yahoo has) |

Frontend route handlers: `/api/portfolio`, `/api/portfolio/settings`, `/api/portfolio/positions[/{id}]`,
`/api/portfolio/intraday/refresh`.

## Tables (V1.0.53, V1.0.54)

| Table | Rows |
|---|---|
| `portfolio` | one per user: `cash`, `total_investment` (≥ 0) |
| `portfolio_position` | `user_id`, `company_id` (unique together), `average_price` (> 0), `shares` (whole, > 0), `date_in` |
| `intraday_quote` | one per company: `session_date`, `price`, `previous_close`, `day_open / high / low`, `volume`, `market_time`, `session_start / end`, `fetched_at` |
| `intraday_bar` | `(company_id, bar_time)`: `session_date`, OHLC, `volume` |

## Code

| Backend (`portfolio/`) | |
|---|---|
| `PortfolioController`, `PortfolioService`, `PortfolioRepository`, `PortfolioViews` | the page's API and columns |
| `intraday/IntradayService`, `IntradaySchedule`, `IntradayRepository`, `YahooIntradayClient`, `IdxSession` | intraday prices |

| Frontend | |
|---|---|
| `app/(dashboard)/portfolio/page.tsx`, `portfolio/performance/page.tsx` | pages |
| `components/portfolio/portfolio-position-client.tsx` | metrics, Live card, positions table |
| `components/portfolio/allocation-chart.tsx`, `intraday-sparkline.tsx`, `drag-scroll.tsx`, `position-sheet.tsx` | allocation, the day's chart, sideways sliding, add / edit / cash forms |
| `lib/portfolio.ts` | types, sections, sector colours |

## Tests

| Test | Covers |
|---|---|
| `PortfolioTest` | every column against `v_key_metrics` and the balance sheet (HRTA), the sheet's formulas and metrics, SMDR in IDR, the allocation adding up, one portfolio per user; the API's 201 / 400 / 403 / 404 / 409 |
| `PortfolioDividendTest` | this year's dividends else last year's, today's share basis after a split, IDX vs Yahoo mismatch, the allocation |
| `IdxSessionAndYahooParseTest` | the IDX phases (Monday - Thursday, Friday, weekend), Yahoo's intraday chart (session date, bars of other days and without a close left out) |
| `IntradayTest` | a refresh shows the session as the last price with the day's change, a fresh quote is not fetched again, an older session leaves the daily close, the schedule does nothing outside the session, the API - and the position and the daily prices unchanged (Yahoo stubbed) |
