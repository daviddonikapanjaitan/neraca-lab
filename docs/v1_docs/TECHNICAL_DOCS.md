# Neraca Lab - Technical Analysis (v1)

**Companies › Technicals** (`/companies/technicals`): interactive technical analysis charts of the listings stored in
the database, and an agent-ready snapshot of every indicator per listing. Read-only and deterministic: **no model is
called, no score, no buy / sell signal** - the page shows data and indicators. The sidebar's Companies entry is a
dropdown: **Fundamentals** (the company list and company pages, as before) and **Technicals**.

Future technical agents (Murphy, Keith Gill, Paul Tudor Jones, Druckenmiller, a risk overlay) are meant to be
consumers of the snapshot ([../indicator-snapshot.md](../indicator-snapshot.md)) and of the `/levels` and `/market`
APIs, without changes to the data or indicator layers.

## 1. Data

| Data | Source | Table |
|---|---|---|
| Listings | the companies of an exchange (filter by exchange; IDX only today, other exchanges via `Exchange`) | `company` |
| Daily bars | the existing price ingestion ([PRICE_INGESTION_DOCS.md](PRICE_INGESTION_DOCS.md)); split-adjusted OHLC, dividend-adjusted close, volume | `price_daily` |
| IHSG | Yahoo Finance `^JKSE`, daily from 1990 (8,890 bars on 2026-10-14), fetched by the technical refresh | `index_daily` (new) |
| Corporate events | Yahoo Finance dividends and splits (one monthly-interval request per listing, refreshed after 7 days), and the splits recorded from the filings or by data scripts | `corporate_event` (new), `corporate_event_fetch` (new), `corporate_action` |
| USD/IDR | ECB reference rates of the price ingestion | `fx_rate_daily` |
| BI rate, SBN 10Y | no source connected; rows `BI_RATE` / `SBN_10Y` can be inserted by hand | `macro_series` (new) |
| Snapshots | computed here | `indicator_snapshot` (new) |
| Chart settings | the user's last settings on the page | `chart_setting` (new) |

Schema: `V1.0.23__schema_technical.sql` (new tables only; no existing table changes).

**Where the data comes from.** The chart page and every `/api/v1/technical` read API read **only the database**; they
never call an outside service. The tables are filled beforehand by two jobs:

| Job | Fetches | Into | How to run |
|---|---|---|---|
| Price ingestion (existing) | daily prices of a listing from the Yahoo Finance chart API (`/v8/finance/chart/HRTA.JK`; EODHD as an alternative provider) and USD/IDR from the ECB (Frankfurter API) | `price_daily`, `fx_rate_daily` | Ingestion › Prices, automatically after a filing upload, nightly when `PRICE_SCHEDULE_ENABLED=true` |
| Technical refresh (new) | IHSG (`^JKSE`) and the dividends / splits of every listing from the same Yahoo Finance chart API | `index_daily`, `corporate_event` | nightly 19:00 WIB, or `POST /api/v1/technical/refresh` (INGESTION) |

Both call Yahoo Finance's web API directly from Java (the endpoint the Python `yfinance` package also uses; that
package is not used) through the app's paced HTTP client: one request at a time, 1 - 2 s apart, backoff on HTTP 429,
no API key. The API is unofficial: when it fails, the job reports the error and the stored data stays as it is. A
chart is therefore as recent as the listing's stored prices (run the price ingestion to update it).

TradingView provides only the open-source chart library that draws the chart in the browser (section 5); no data
comes from TradingView.

**Price basis.** The price provider delivers split-adjusted prices (Yahoo's close), so prices are "as traded since the
last split"; raw prices from before a split are not stored, and `adjusted=false` is refused with 400. Indicators,
levels and the snapshot use these split-adjusted prices (tradable levels). The chart can also show the
**total-return** basis (`adjustment=total`: open / high / low / close scaled by adjusted close / close); the levels are
then hidden, as they would not be tradable prices. Companies that report in USD (INDY, SMDR, PTSN, RAJA) have their
prices stored in USD (`price_daily` is in `company.currency`); the technical layer converts them back to the IDR quote
with the rate they were converted with (the last ECB day before the trading day), so charts and levels are IDX prices.

**Value traded** is close x volume (the exchange's own value is not stored).

## 2. IDX rules

| Rule | Implementation |
|---|---|
| Lot size | 100 shares (`neracalab.technical.lot-size`) |
| Tick size (fraksi harga) | `IdxTickSize`, configurable bands: Rp 1 below 200, 2 below 500, 5 below 2,000, 10 below 5,000, 25 from 5,000. Every computed level is rounded to a valid price (a zone's low down, its high up); on 2026-10-14 all 6,281 levels of the 61 stored snapshots were checked valid |
| Auto rejection (ARA / ARB) | `AutoRejection`: a close at the day's high (low) within one tick of prev close x (1 ± limit). Limits by effective date and price band, configurable: ARA 35 % up to Rp 200, 25 % up to 5,000, 20 % above; ARB symmetric until 2020-03-09, 10 % from 2020-03-10, 7 % from 2020-03-13, 15 % from 2023-06-05, symmetric from 2023-09-04, 15 % from 2025-04-08 (verify against the exchange's current rules). No ARB at the Rp 50 floor |
| Limited history | fewer than 250 trading days: a warning on the page; long-period indicators are null |
| Special monitoring board, suspensions | no source: null in the snapshot. `possiblySuspended` only flags 3+ consecutive missing IDX sessions within the last 60 bars, or no volume on the last 3 bars |

## 3. Indicator engine

`technical.engine`: pure Java, deterministic.

- **ta4j 0.22.6** (MIT) for SMA, EMA, RSI, MACD, Stochastic, ADX / ±DI, ATR, Bollinger (population standard
  deviation), OBV and ROC. The spec asked for the 0.25.x line; from 0.22.7 ta4j ships Java 25 class files, which the Java
  21 build and runtime of the backend cannot load, so 0.22.6 (the newest Java 21 release, same API) is pinned.
- Custom code: swing pivots (5-bar fractals), zone clustering, volume-by-price, the zigzag of the last major swing and
  its retracements, base detection, 52-week / all-time levels, relative strength and percentiles, breadth, tick-size
  rounding, ARA / ARB detection, weekly / monthly aggregation (ISO weeks, calendar months; a bar is dated on its first
  trading day).
- Warm-up: the bars before an indicator is defined are null (no SMA 200 of 120 bars).
- Chart series are computed on demand over the whole history (so the start of a range is never in a warm-up), then cut
  to the range; weekly / monthly indicators use bars of that interval (SMA 50 weekly = 50 weeks).
- Every threshold is configurable under `neracalab.technical.*` in `application.yaml` (zones, pivots, volume profile,
  retracements, base, spikes, tick sizes, auto rejection, events, schedule, cache).

## 4. API

Permission COMPANIES (the refresh: INGESTION). Series are compact `[time, value]` arrays; time = seconds since the epoch
at 00:00 UTC of the trading day. Errors are ProblemDetail: 400 invalid parameter / exchange / ticker, 404 unknown
company ("Company not found") or no prices ("No prices").

| Endpoint | Content |
|---|---|
| `GET /api/v1/technical/tickers?exchange=IDX&query=&limit=` | ticker search by code or company name (exact and prefix matches first): trading days, first / last date, `limitedHistory` |
| `GET /api/v1/technical/{exchange}/{ticker}/ohlcv?interval=D\|W\|M&range=3M\|6M\|1Y\|3Y\|5Y\|10Y\|YTD\|MAX&from=&to=&adjustment=split\|total` | `bars` rows `[time, open, high, low, close, volume, value]`, `autoRejection` `[time, ARA\|ARB, change %, day]`, the listing header (currency, trading days, `limitedHistory`, lot size). `range` counts back from the listing's last trading day; `from` / `to` override it |
| `GET .../indicators?interval=&range=&set=sma50,sma200,rsi14,bb,macd,...` | the requested series; a group name expands (`bb` → `bb.upper`, `bb.middle`, `bb.lower`, `bb.width`). Names: `sma20 sma50 sma100 sma200 ema20 ema50 bb.* rsi14 macd.line macd.signal macd.histogram stoch.k stoch.d adx.adx adx.plusDi adx.minusDi atr14 atrPct atrBands.upper atrBands.lower obv volSma20 volSma50 valueSma20 valueSma50 roc21 roc63 roc126 roc252 channel20.high channel20.low channel50.high channel50.low mom12_1`, plus `rs` (stock / IHSG rebased to 100) and `index` (IHSG on the bars' dates) |
| `GET .../levels` | `zones`, `retracement`, `swings`, `week52`, `allTimeHigh`, `base`, the full `volumeProfile` (24 buckets), and `keyLevels`: one table of every level with its distance from the close, ordered from the top |
| `GET .../snapshot` | the IndicatorSnapshot ([../indicator-snapshot.md](../indicator-snapshot.md)) |
| `GET .../events` | corporate events with chart time, source and URL |
| `GET /api/v1/technical/market?exchange=IDX&range=1Y` | IHSG regime (close, SMA 200, series), breadth, macro series |
| `GET`, `PUT /api/v1/technical/settings` | the user's last chart settings (a JSON object up to 20,000 characters; 204 when none) |
| `POST /api/v1/technical/refresh?exchange=IDX&ticker=&forceEvents=false` (INGESTION) | starts a refresh in the background (202): IHSG (from 7 days before its last stored day; today's bar only after the price ingestion's session-close cutoff), the events that are due, then every snapshot. `GET` the same path for its status (`indexRows`, `eventCompanies`, `eventsStored`, `snapshots`, `errors`). A Yahoo rate limit stops the event fetches of that run |

Measured on 2026-10-14 (61 listings, cached): 0.04 - 0.13 s per call; the first `/levels` of a listing after new
prices about 1 s.

**Caching.** Daily bars, indicator series, the IHSG bars and the market context are kept in a Caffeine cache
(`cache-size` 200, `cache-ttl` 12 h). Keys carry a fingerprint of the stored data (count, last date, sums of close,
adjusted close and volume), so new prices or a provider re-adjustment are used at the next request; the refresh clears
the cache.

**Nightly job.** `TechnicalSchedule` (`neracalab.technical.schedule`, on by default, 19:00 Asia/Jakarta, Monday - Friday)
starts the refresh after the evening price run. One refresh runs at a time (a single backend instance; no
distributed lock).

## 5. The page

`src/app/(dashboard)/companies/technicals`, client component `TechnicalsPageClient`; data via TanStack Query through
the Next.js route handlers `/api/technical/...`, every response validated with zod before charting.

- **Controls**: exchange (IDX), ticker search with autocomplete (code or name, keyboard navigable), interval
  Daily / Weekly / Monthly, range 3M / 6M / 1Y / 3Y / 5Y / Max, Split-adjusted vs Total return, indicator checkboxes
  (a side column; a sheet on a phone) and presets that only toggle indicators:
  Murphy (SMA 50 / 200, zones, retracements, RSI, MACD, volume, OBV, RS vs IHSG), Keith Gill (SMA 200, volume,
  volume profile, base, 52-week range, events), Paul Tudor Jones (SMA 200, 20 / 50-bar channels, ADX, ATR bands),
  Druckenmiller (RS vs IHSG, 12-1 month momentum, IHSG regime panel, macro panel).
- **Settings** persist per user on the server (`chart_setting`), with localStorage as a fallback, and in the URL
  (`?exchange=IDX&ticker=HRTA&interval=D&range=1Y&preset=murphy&ind=sma50,sma200,...`, shareable). The URL wins over the
  saved settings; a company page links to its chart (`Technicals` button in the company header).
- **Main chart**: TradingView Lightweight Charts 5.2.1 through a small wrapper (`price-chart.tsx`: `useRef` +
  `useEffect`, `autoSize`, `chart.remove()` on cleanup), loaded with `next/dynamic` (`ssr: false`). Candles with SMA /
  EMA, Bollinger, channels, ATR bands, IHSG rebased to the stock's first close; zones and the base range as shaded
  bands (a series primitive, `band-primitive.ts`); retracement, 52-week and all-time-high price lines; event markers
  (D dividend, S split, ...) and ARA / ARB arrows. Lower panes (v5 panes): volume colored by up / down day with its
  50-bar average, RSI, MACD, Stochastic, OBV, ADX ±DI, ATR %, RS vs IHSG, 12-1 month momentum. Colors follow the
  app's CSS variables and the light / dark theme. The `attributionLogo` stays on; the notice is in the page footer and
  on `/credits`.
- **Side panels**: key levels (CSV export), snapshot in readable form labeled "data, not signals" (CSV export), volume
  by price (Recharts), IHSG regime and macro (Recharts).
- **Export**: the chart as PNG (`takeScreenshot`), key levels and snapshot as CSV.
- Disclaimer on the page: "For information only. Not investment advice."

## 6. Tests

| Test | What it proves |
|---|---|
| `IdxRulesTest` | tick of every band, rounding / floor / ceil to valid prices across band boundaries (every price 1 .. 20,000), ARA / ARB by date and band, the rule history |
| `IndicatorEngineTest` | SMA, EMA, RSI, MACD, Bollinger, ATR, Stochastic, OBV, ROC, channels, momentum against reference formulas written in the test; warm-ups; weekly / monthly aggregation incl. an ISO week across the year end |
| `StructureTest` | fractal pivots, zone clustering, volume-profile bucketing, retracement math (up and down swings), base detection, extremes |
| `SnapshotCalculatorTest` | on about 300 real days of HRTA, SMSM and MYOR with IHSG (`src/test/resources/technical/*.csv`): the complete versioned schema, valid tick prices, determinism and the input hash, limited history, missing sessions vs stale prices, earnings reaction |
| `TechnicalControllerTest` | every endpoint against the Docker Postgres (rolled back): search, bars, aggregation, indicators, levels, snapshot, events, market, settings, invalid parameters, unknown ticker, a 30-day listing and one without prices |

## 7. Known limitations

- No earnings, RUPS / RUPSLB, rights-issue or private-placement dates: `reporting_period.filing_date` is not filled
  by the ingestion yet, and no event source exists for the others.
- BI rate and SBN 10Y have no source; the special monitoring board and suspensions have no source.
- Breadth and the relative-strength percentiles are over the listings stored in Neraca Lab (61 on 2026-10-14), not
  the whole exchange.
- `value` (value traded) is approximated by close x volume.
- No springdoc / OpenAPI types: the API types are hand-written zod schemas (`src/lib/technical.ts`).
