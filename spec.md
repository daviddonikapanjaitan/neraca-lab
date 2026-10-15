# Claude Code Prompt — Technical Analysis Chart Page for IDX (agent-ready indicators, no agents yet)

> How to use: open Claude Code in your project root, switch to **plan mode**, and paste everything below the line.
> Work **one phase per session**, review the diff, run tests, then continue with the next phase.

---

## Context

You are working in my existing application:
- **Backend:** Spring Boot + Spring AI (Java), PostgreSQL for all data
- **Frontend:** Next.js (App Router) based on the **shadcn-fintech** template (Next.js 16, React 19, Tailwind CSS v4, shadcn/ui, Recharts 3, `cmdk`, `date-fns`, `next-themes`, TypeScript)
- Existing "Companies" tab and "Screening" tab (fundamental investor agents)
- Existing report and PDF infrastructure

Market: **Indonesia Stock Exchange (IDX)** only, for now.

**First, explore the codebase** (packages, entities, existing modules, frontend stack, migration tool) and produce a short implementation plan before writing code. Reuse existing patterns where possible. Ask me before adding new external dependencies or creating migrations that change existing tables.

## Goal

Add a new **"Technical" tab**: an interactive page to **view technical analysis charts** for IDX stocks.

**Scope for now:**
- Data ingestion, a deterministic **indicator engine**, REST APIs, and the chart page.
- **No LLM calls, no AI agents, no scoring, no buy/sell signals.** This page only displays data and indicators.

**Future-ready requirement:**
Later I will add technical analysis agents inspired by **John J. Murphy, Keith Gill, Paul Tudor Jones, and Stanley Druckenmiller**, plus a risk overlay. The indicator engine built now must already compute and store **every indicator those agents will need** (listed in Phase 2), exposed through a stable, documented JSON structure, so the agents can be added later without changing the data layer.

## Tech stack (use these unless the codebase already has an equivalent; ask before deviating)

### Backend (Spring Boot)

| Need | Library | Notes |
|---|---|---|
| Indicator engine | `org.ta4j:ta4j-core` (MIT) | Pin an exact version from the latest 0.25.x line after checking release notes; it is pre-1.0 and changes often. Use for SMA/EMA, RSI, MACD, Stochastic, ADX/±DI, ATR, Bollinger, OBV, ROC |
| REST API | `spring-boot-starter-web` | Chart endpoints |
| Database | PostgreSQL + `spring-boot-starter-data-jpa` for entities; `JdbcTemplate` (or jOOQ if already used) for bulk OHLCV reads/upserts with batching | JPA is too slow for bulk time-series writes |
| Migrations | `flyway-core` + `flyway-database-postgresql` (or the project's existing tool) | |
| Caching | `spring-boot-starter-cache` + `com.github.ben-manes.caffeine:caffeine` | Cache indicator series per ticker/interval/range; evict after nightly ETL |
| HTTP clients | Spring `RestClient` | Sectors API, Yahoo fallback |
| Retry / rate limiting | Resilience4j (Spring Boot starter matching the project's Boot version) | Retry with backoff and per-provider rate limiters |
| Scheduled ETL lock | `shedlock-spring` + `shedlock-provider-jdbc-template` | Prevent double runs on multiple instances |
| Statistics | `org.apache.commons:commons-math3` (or Apache Commons Statistics) | Percentiles, regression slopes; later Monte Carlo tests |
| API docs | `springdoc-openapi-starter-webmvc-ui` (version matching the Boot major version) | Source for frontend TypeScript types |
| Validation / monitoring | `spring-boot-starter-validation`, `spring-boot-starter-actuator` | |
| Testing | JUnit 5, AssertJ, Testcontainers (PostgreSQL) | Real-database integration tests |
| Optional | TimescaleDB extension | Only if history grows large; plain PostgreSQL is enough for ~960 stocks of daily bars |

Write custom code (no library) for: pivot/swing detection and zone clustering, volume-by-price profile, retracements, base detection, relative-strength percentiles, market breadth, IDX tick-size rounding, ARA/ARB detection.

### Frontend (Next.js, shadcn-fintech)

| Need | Library | Notes |
|---|---|---|
| Candlestick & indicator chart | `lightweight-charts` v5 (TradingView, Apache-2.0) | Use v5 native panes for RSI/MACD/volume, series markers for events and ARA/ARB days, price lines for levels, plugin primitives (or official `@tradingview/lwc-plugin-*` packages) for shaded zones, `takeScreenshot()` for PNG export. **Keep the `attributionLogo` option enabled** and add the TradingView NOTICE/attribution to the app's credits page, as the license requires |
| Side panels | `recharts` (already installed) | Volume-by-price histogram, macro mini-charts, breadth, snapshot cards |
| Data fetching | `@tanstack/react-query` | Query keys per ticker/interval/range/indicator set |
| API types | `openapi-typescript` (dev dependency) | Generate types from the springdoc OpenAPI spec into `src/lib/api/types.ts` |
| Runtime validation | `zod` | Validate API responses before charting |
| URL state | `nuqs` (optional) | Shareable chart settings, e.g. `?ticker=HRTA&interval=D&preset=murphy` |
| Ticker search | `cmdk` (already installed) via shadcn `Command` | |
| Theme / dates | `next-themes`, `date-fns` (already installed) | Re-apply chart colors on theme change |

shadcn/ui components to use: `Command`, `ToggleGroup`, `Popover`, `Checkbox`, `Tabs`, `Sheet` (mobile settings), `Card`, `Table`, `Skeleton`, `Tooltip`.

Frontend rules:
- The chart is a **client component** (`"use client"`), loaded with `next/dynamic` and `ssr: false`.
- Write a **small custom React wrapper** around Lightweight Charts (`useRef` + `useEffect`, `ResizeObserver`, cleanup with `chart.remove()`); do not add third-party React wrappers.
- **Do not compute indicators in the browser.** The backend is the single source of truth; the frontend only renders.
- Map chart colors to the template's CSS variables so light/dark mode matches the rest of the app.

Install commands (confirm with me first):
```bash
pnpm add lightweight-charts @tanstack/react-query zod nuqs
pnpm add -D openapi-typescript
```

## Phase 1 — Data layer (IDX prices, index, macro, events)

Tables (use the project's migration tool):
- `price_daily(ticker, trade_date, open, high, low, close, adj_close, volume, value_traded)` — unique (ticker, trade_date)
- `index_daily(index_code, trade_date, open, high, low, close, volume)` — at minimum IHSG (Yahoo symbol `^JKSE`)
- `macro_series(series_code, obs_date, value)` — BI rate, SBN 10Y yield, USD/IDR
- `corporate_event(ticker, event_type, event_date, description, source_url)` — earnings dates, RUPS/RUPSLB, dividends, rights issues, private placements, stock splits

Data sources (each behind a pluggable `PriceDataProvider` / `MacroDataProvider` interface):
- Primary: Sectors API (sectors.app) daily prices (API key from environment variable)
- Fallback: Yahoo Finance `.JK` tickers via a small adapter (unofficial; handle failures gracefully)
- Nightly scheduled ETL; idempotent upserts; retry with backoff; respect rate limits
- An admin endpoint to trigger a backfill for one ticker or all tickers

IDX-specific rules:
- Use **split/rights-adjusted** prices for indicators; keep raw prices for display toggle
- Lot size = 100 shares
- Implement the IDX **tick size (fraksi harga) table** as a configurable utility; round all computed levels (support/resistance, retracements) to valid tick sizes
- Detect auto-rejection hits (ARA/ARB) from daily price changes (thresholds configurable by price band)
- Flag special monitoring board (Papan Pemantauan Khusus) and suspended stocks
- Show a "limited history" warning when a stock has fewer than 250 trading days

## Phase 2 — Indicator engine (pure Java, unit-tested, agent-ready)

Use the **ta4j** library for standard indicators (check the latest version and ask me before adding it). Compute everything deterministically in Java.

Create an `IndicatorSnapshot` record per stock per date with these groups. Mark which future agent uses each group (for documentation only):

| Group | Indicators | Future users |
|---|---|---|
| **Trend** | SMA 20/50/100/200, EMA 20/50, SMA200 slope (20-day % change), price vs each SMA (%), golden/death cross state + days since last cross, ADX(14) with +DI/−DI | Murphy, PTJ, Druckenmiller |
| **Momentum** | RSI(14), MACD(12,26,9) line/signal/histogram, Stochastic(14,3,3), rate of change 21/63/126/252 days, 12-1 month momentum | Murphy, Druckenmiller |
| **Volatility** | ATR(14), ATR% of price, ATR% percentile (252 days), Bollinger Bands (20,2) and band width | PTJ, Gill, Risk |
| **Volume** | 20/50-day average volume and value traded, OBV + 20-day slope, up/down volume ratio (50 days), volume spikes (≥ 2× 50-day avg) on up vs down days (last 60 days) | Murphy, Gill, Risk |
| **Structure** | Swing highs/lows (pivot detection, e.g. 5-bar fractals); support/resistance zones (clustered, default 2% tolerance); volume-by-price profile (last 120 days) with top 3 high-volume nodes; retracement levels (33%, 38.2%, 50%, 61.8%, 66%) of the last major swing (major = configurable ATR multiple); 52-week high/low and distance; all-time high and drawdown; base detection (60–120 day range with contracting ATR%) | Murphy, Gill, PTJ |
| **Relative strength** | Stock/IHSG ratio and 50-day slope; percentile rank of 6- and 12-month returns across the IDX universe; sector relative strength if sector data exists | Murphy, Druckenmiller |
| **Market regime** | IHSG vs its 200-day SMA; market breadth (% of IDX stocks above their 200-day SMA) | PTJ, Druckenmiller |
| **Macro** | BI rate 6-month change, SBN 10Y yield 3-month change, USD/IDR 3-month change | Druckenmiller |
| **Events** | Upcoming corporate events (next 60 days); last earnings-day gap % and 5-day follow-through | Gill, Druckenmiller |
| **Risk flags** | 20-day average value traded, ARA/ARB frequency (60 days), special-monitoring/suspension flags, gap frequency | Risk |

Storage and API:
- Nightly job computes and stores the **latest snapshot per ticker** in `indicator_snapshot(ticker, as_of_date, snapshot_json, input_hash)`; optionally keep history.
- Time-series indicators for charts are computed on demand (with caching), not stored for every date.
- Document the `snapshot_json` schema in `docs/indicator-snapshot.md` and version it (`schemaVersion` field), so future agents can depend on it.

Write unit tests comparing indicator outputs with known reference values, plus tests for pivot detection, zone clustering, retracement math, tick-size rounding, base detection, and volume profile bucketing.

## Phase 3 — REST API

- `GET /api/technical/tickers?query=` — ticker search (code + company name)
- `GET /api/technical/{ticker}/ohlcv?interval=D|W|M&from=&to=&adjusted=true` — candles + volume (weekly/monthly aggregated from daily)
- `GET /api/technical/{ticker}/indicators?interval=&from=&to=&set=sma50,sma200,rsi14,...` — time-series for selected indicators
- `GET /api/technical/{ticker}/levels` — support/resistance zones, retracements, pivots, volume profile, 52-week/ATH levels (tick-rounded)
- `GET /api/technical/{ticker}/snapshot` — latest `IndicatorSnapshot` JSON (agent-ready)
- `GET /api/technical/{ticker}/events` — corporate events for chart markers
- `GET /api/technical/market` — IHSG regime, breadth, macro series

Responses should be compact (arrays of `[timestamp, value]`), cached, and fast enough for interactive charting.

## Phase 4 — Frontend ("Technical" tab, chart page)

Build the page in the Next.js app as a new route (e.g., `src/app/technical/page.tsx`) and add a "Technical" item to the existing sidebar navigation, following the template's layout and styling. Use the libraries from the Tech stack section: Lightweight Charts v5 for the main chart and indicator panes, Recharts for side panels.

**Controls**
- Exchange dropdown (IDX only for now)
- Ticker search with autocomplete
- Interval: Daily / Weekly / Monthly
- Range: 3M / 6M / 1Y / 3Y / 5Y / Max
- Adjusted vs raw prices toggle
- Indicator picker (checkbox groups), with **presets** that only toggle indicators (no scoring):
  - *Murphy preset*: SMA50, SMA200, support/resistance zones, retracements, RSI, MACD, volume, OBV, relative strength vs IHSG
  - *Keith Gill preset*: SMA200, volume, volume-by-price profile, base range highlight, 52-week range, event markers
  - *Paul Tudor Jones preset*: SMA200, 20/50-day high/low channels, ADX, ATR bands
  - *Druckenmiller preset*: relative strength vs IHSG, 12-1 month momentum, IHSG regime panel, macro panel (BI rate, SBN 10Y, USD/IDR)
- Save the user's last chart settings (server-side per user if accounts exist; otherwise local storage with try/catch)

**Main chart (price pane)**
- Candlesticks with overlays: SMAs/EMAs, Bollinger Bands, support/resistance zones as shaded bands, retracement lines, 52-week high/low and ATH lines, base-range highlight
- Corporate event markers (earnings, RUPS/RUPSLB, dividends, rights issues, private placements, splits)
- ARA/ARB day markers
- Optional comparison line: IHSG rebased to the stock's starting price

**Lower panes (toggleable)**
- Volume (colored by up/down day) with 50-day average volume line
- RSI, MACD, Stochastic, OBV, ADX(+DI/−DI), ATR%, relative strength vs IHSG

**Side panels**
- Volume-by-price profile (horizontal histogram aligned with the price axis via a Lightweight Charts plugin primitive if feasible; otherwise a separate Recharts panel)
- "Key levels" table: support/resistance zones, retracements, 52-week high/low, ATH, distance from current price (%)
- "Snapshot" panel showing the latest `IndicatorSnapshot` values in readable form (trend, momentum, volatility, volume, relative strength, regime, risk flags), clearly labeled as data, not signals

**Other**
- Export current chart as PNG; export the key-levels table and snapshot as CSV
- Responsive layout (usable on a phone width), light/dark theme consistent with the app
- Disclaimer on the page: "For information only. Not investment advice."

## Phase 5 — Tests and acceptance criteria

- Unit tests: all indicators, pivots, zones, retracements, tick rounding, weekly/monthly aggregation, volume profile
- Integration test with fixtures of ~300 days of real IDX data for 3 tickers (e.g., HRTA, SMSM, MYOR) under `src/test/resources`
- API tests for all endpoints, including invalid tickers and short histories
- Acceptance:
  - Chart loads for a liquid ticker in under ~2 seconds (cached)
  - All displayed levels are valid IDX tick sizes
  - Indicator presets toggle correctly, and settings persist
  - `/snapshot` returns the full documented, versioned schema for every ticker with ≥ 250 days of history
  - No LLM calls anywhere in this feature

## Out of scope (for a later phase)

- Technical analysis agents (Murphy, Gill-style, PTJ-style, Druckenmiller-style), risk overlay scoring, LLM interpretation
- Back-testing / evidence validation (Aronson-style)
- Screening and ranking by technical scores

Design the code so these can be added later as consumers of `IndicatorSnapshot` and the `/levels` and `/market` APIs, without changing the data or indicator layers.

## Working style

- Start with the plan, then implement Phase 1 only and stop for my review.
- Keep classes small and testable; no business logic in controllers.
- Make every threshold configurable in `application.yml` with sensible defaults.
- Do not commit secrets; read API keys from environment variables.