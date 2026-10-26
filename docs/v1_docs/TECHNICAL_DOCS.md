# Neraca Lab - Technical Analysis (v1)

**Companies › Technicals** (`/companies/technicals`): interactive technical analysis charts of the listings stored in
the database, of the **IHSG index** and of the **Forex pairs and commodities** (section 9), an agent-ready snapshot of
every indicator per listing, and below the chart a
**summary for each preset** (Murphy, Keith Gill, Paul Tudor Jones, Druckenmiller): a stance, a buy price, a sell price,
a stop and the thesis from the indicators and price patterns (section 8). Deterministic: **no model is called** - the
summaries apply fixed rules to the snapshot, so the same data always gives the same summary. The sidebar's Companies
entry is a dropdown: **Fundamentals** (the company list and company pages, as before) and **Technicals**.

The AI technical agents (Keith Gill, Murphy, Paul Tudor Jones, Druckenmiller) of Screening › Technicals
([TECHNICAL_SCREENING_DOCS.md](TECHNICAL_SCREENING_DOCS.md)) read the snapshot
([../indicator-snapshot.md](../indicator-snapshot.md)) without changes to the data or indicator layers; the
rules-based summaries are their prior and their fallback trade plan.

## 1. Data

| Data | Source | Table |
|---|---|---|
| Listings | the companies of an exchange (filter by exchange; IDX only today, other exchanges via `Exchange`) | `company` |
| Daily bars | the existing price ingestion ([PRICE_INGESTION_DOCS.md](PRICE_INGESTION_DOCS.md)); split-adjusted OHLC, dividend-adjusted close, volume | `price_daily` |
| IHSG | Yahoo Finance `^JKSE`, daily from 1990 (8,891 bars on 2026-10-15), fetched by the IHSG ingestion (Ingestion › Price Ingestion, the "Update all companies" run, the price schedule) and the technical refresh | `index_daily` (new) |
| LQ45 | Yahoo Finance `^JKLQ45`, from 1997 (7,200 bars on 2026-10-16), stored by the same index job as IHSG (`neracalab.technical.other-indexes`); read by the Macro page (MACRO_DOCS.md, section 6a), not by the snapshots | `index_daily` |
| Corporate events | Yahoo Finance dividends and splits (one monthly-interval request per listing, refreshed after 7 days), and the splits recorded from the filings or by data scripts | `corporate_event` (new), `corporate_event_fetch` (new), `corporate_action` |
| USD/IDR | the Forex price ingestion (Yahoo Finance `IDR=X`) when stored, else the ECB reference rates of the price ingestion | `instrument_daily`, `fx_rate_daily` |
| Forex pairs, commodities | Price Ingestion with the market Forex / Commodities ([PRICE_INGESTION_DOCS.md](PRICE_INGESTION_DOCS.md), section 1b) | `market_instrument`, `instrument_daily` (V1.0.25) |
| Snapshots | computed here | `indicator_snapshot` (new) |
| Chart settings | the user's last settings on the page | `chart_setting` (new) |

Schema: `V1.0.23__schema_technical.sql` (new tables only; no existing table changes).

**Where the data comes from.** The chart page and every `/api/v1/technical` read API read **only the database**; they
never call an outside service. The tables are filled beforehand by two jobs:

| Job | Fetches | Into | How to run |
|---|---|---|---|
| Price ingestion (existing) | daily prices of a listing from the Yahoo Finance chart API (`/v8/finance/chart/HRTA.JK`; EODHD as an alternative provider) and USD/IDR from the ECB (Frankfurter API) | `price_daily`, `fx_rate_daily` | Ingestion › Price Ingestion (one company, or "Update all companies to today"), automatically after a filing upload, nightly when `PRICE_SCHEDULE_ENABLED=true` |
| IHSG ingestion (new, in the price queue) | IHSG (`^JKSE`) from the same Yahoo Finance chart API: from the latest stored day to the last completed trading day, or the whole history | `index_daily` | Ingestion › Price Ingestion (IHSG card, or with "Update all companies"), the price schedule, and the technical refresh |
| Technical refresh (new) | IHSG (through the IHSG ingestion) and the dividends / splits of every listing from the same Yahoo Finance chart API | `index_daily`, `corporate_event` | nightly 19:00 WIB, or `POST /api/v1/technical/refresh` (INGESTION) |

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
with the rate they were converted with (the last ECB day before the trading day), rounded to 2 decimals (the USD
price's 8 decimals leave a residue: CUAN 539.9999 -> 540), so charts and levels are IDX prices.

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
| `GET /api/v1/technical/markets` | the page's dropdown: the exchanges (`kind` EXCHANGE, `itemLabel` Ticker), then Forex and Commodities (`kind` MARKET, `itemLabel` Currency / Commodity) |
| `GET /api/v1/technical/tickers?exchange=IDX&query=&limit=` | ticker search by code or company name (exact and prefix matches first): `type` (`STOCK`, or `INDEX` for IHSG, listed first when it matches: "IHSG", "JKSE", "index", "composite"), trading days, first / last date, `limitedHistory`. `exchange=FOREX` / `COMMODITY`: the instruments (`type` FOREX / COMMODITY, `unit`, `frequency`) |
| `GET /api/v1/technical/{exchange}/{ticker}/ohlcv?interval=D\|W\|M&range=3M\|6M\|1Y\|3Y\|5Y\|10Y\|YTD\|MAX&from=&to=&adjustment=split\|total` | `bars` rows `[time, open, high, low, close, volume, value]`, `autoRejection` `[time, ARA\|ARB, change %, day]`, the listing header (currency, trading days, `limitedHistory`, lot size). `range` counts back from the listing's last trading day; `from` / `to` override it |
| `GET .../indicators?interval=&range=&set=sma50,sma200,rsi14,bb,macd,...` | the requested series; a group name expands (`bb` → `bb.upper`, `bb.middle`, `bb.lower`, `bb.width`). Names: `sma20 sma50 sma100 sma200 ema20 ema50 bb.* rsi14 macd.line macd.signal macd.histogram stoch.k stoch.d adx.adx adx.plusDi adx.minusDi atr14 atrPct atrBands.upper atrBands.lower obv volSma20 volSma50 valueSma20 valueSma50 roc21 roc63 roc126 roc252 channel20.high channel20.low channel50.high channel50.low mom12_1`, plus `rs` (stock / IHSG rebased to 100) and `index` (IHSG on the bars' dates) |
| `GET .../levels` | `zones`, `retracement`, `swings`, `week52`, `allTimeHigh`, `base`, the full `volumeProfile` (24 buckets), and `keyLevels`: one table of every level with its distance from the close, ordered from the top |
| `GET .../snapshot` | the IndicatorSnapshot ([../indicator-snapshot.md](../indicator-snapshot.md)) |
| `GET .../events` | corporate events with chart time, source and URL |
| `GET .../summary` | the preset summaries (section 8): per preset `stance` (`BUY`, `ACCUMULATE`, `WAIT`, `AVOID`), `stanceLabel`, `score` / `maxScore`, `buy` / `sell` / `stopLoss` (`price`, `type` LIMIT or STOP, `basis`, `distancePct`), `rewardRisk`, `thesis`, `points` (the evidence, `tone` POSITIVE / NEGATIVE / NEUTRAL); `caveats` (limited history, thin liquidity, ARA / ARB, stale prices), `disclaimer` |
| `GET /api/v1/technical/market?exchange=IDX&range=1Y` | IHSG regime (close, SMA 200, series) and breadth; no macro data (the Macro page has it). A market (FOREX, COMMODITY) answers with the IDX context |
| `GET`, `PUT /api/v1/technical/settings` | the user's last chart settings (a JSON object up to 20,000 characters; 204 when none) |
| `POST /api/v1/technical/refresh?exchange=IDX&ticker=&forceEvents=false` (INGESTION) | starts a refresh in the background (202): IHSG (the IHSG ingestion: from its last stored day; today's bar only after the price ingestion's session-close cutoff), the events that are due, then every snapshot. `GET` the same path for its status (`indexRows`, `eventCompanies`, `eventsStored`, `snapshots`, `errors`). A Yahoo rate limit stops the event fetches of that run |

`{ticker}` = `IHSG` (or `^JKSE`, URL-encoded) is the exchange's index: bars from `index_daily` in index points, levels
rounded to 0.01 instead of IDX ticks, `type` = `INDEX` and `lotSize` = null in the header, no auto-rejection days, no
events; its snapshot is computed on request and kept in memory only (`indicator_snapshot` belongs to companies), with
relative strength vs itself, auto-rejection, lot size and value traded null.

`{ticker}` = `LQ45` (or `^JKLQ45`) is an index the same way (`neracalab.technical.other-indexes`; listing id -2, IHSG
-1): its own bars from `index_daily`, `source` / `priceBasis` "Yahoo Finance ^JKLQ45", levels at 0.01, no events, no
auto-rejection, no lot, a snapshot in memory - but it **is compared with IHSG**: relative strength (`rs`, the snapshot's
`ratio` / `ratioChange50Pct`) and the rebased IHSG line are available. Yahoo Finance publishes no volume for
`^JKLQ45` (every bar 0): the volume indicators are empty and the snapshot says so. The ticker search lists the indexes
first (IHSG, then LQ45; "composite" finds IHSG only). The preset summaries read it as an index (the market), like IHSG.

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

- **Controls**: exchange or market (IDX, Forex, Commodities; section 9), ticker search with autocomplete (code or
  name, or IHSG for the composite index; with a market its currencies / commodities; keyboard navigable), interval Daily / Weekly / Monthly, range 3M / 6M / 1Y / 3Y / 5Y / Max, Split-adjusted vs Total
  return, indicator checkboxes (a side column; a sheet on a phone) and presets that toggle indicators:
  Murphy (SMA 50 / 200, zones, retracements, RSI, MACD, volume, OBV, RS vs IHSG), Keith Gill (SMA 200, volume,
  volume profile, base, 52-week range, events), Paul Tudor Jones (SMA 200, 20 / 50-bar channels, ADX, ATR bands),
  Druckenmiller (RS vs IHSG, 12-1 month momentum, IHSG regime panel).
- **Settings** persist per user on the server (`chart_setting`), with localStorage as a fallback, and in the URL
  (`?exchange=IDX&ticker=HRTA&interval=D&range=1Y&preset=murphy&ind=sma50,sma200,...`, shareable). The URL wins over the
  saved settings; a company page links to its chart (`Technicals` button in the company header).
- **Main chart**: TradingView Lightweight Charts 5.2.1 through a small wrapper (`price-chart.tsx`: `useRef` +
  `useEffect`, `autoSize`, `chart.remove()` on cleanup), loaded with `next/dynamic` (`ssr: false`). Candles (palm oil in
  USD and in ringgit and every monthly series: a plain line of the close, `chartsAsLine` in `lib/technical.ts`; the other
  daily commodities keep candles) with SMA /
  EMA, Bollinger, channels, ATR bands, IHSG rebased to the stock's first close; zones and the base range as shaded
  bands (a series primitive, `band-primitive.ts`); retracement, 52-week and all-time-high price lines; event markers
  (D dividend, S split, ...) and ARA / ARB arrows. Lower panes (v5 panes): volume colored by up / down day with its
  50-bar average, RSI, MACD, Stochastic, OBV, ADX ±DI, ATR %, RS vs IHSG, 12-1 month momentum. Colors follow the
  app's CSS variables and the light / dark theme. The `attributionLogo` stays on; the notice is in the page footer and
  on `/credits`.
- **Preset summaries** (below the chart): one card per preset, the selected one first and highlighted - the stance,
  "Buy at or below / at or above", "Sell (target)" and "Stop loss" with their distance from the close and why the
  level was chosen, reward to risk, the thesis, and the evidence (each indicator's vote) on request; "Show on chart"
  applies the preset. Caveats (limited history, thin liquidity, ARA / ARB days, stale prices) above them.
- **IHSG**: searched like a ticker; the header shows index points and an "Index" badge instead of the lot and the
  Fundamentals link; relative strength, the IHSG comparison line, events and ARA / ARB markers are disabled for it.
- **Side panels**: key levels (CSV export), snapshot in readable form labeled "data, not signals" (CSV export), volume
  by price (Recharts), IHSG regime (Recharts).
- **Export**: the chart as PNG (`takeScreenshot`), key levels and snapshot as CSV.
- Disclaimer on the page: "For information only. Not investment advice."

## 6. Tests

| Test | What it proves |
|---|---|
| `IdxRulesTest` | tick of every band, rounding / floor / ceil to valid prices across band boundaries (every price 1 .. 20,000), ARA / ARB by date and band, the rule history |
| `IndicatorEngineTest` | SMA, EMA, RSI, MACD, Bollinger, ATR, Stochastic, OBV, ROC, channels, momentum against reference formulas written in the test; warm-ups; weekly / monthly aggregation incl. an ISO week across the year end |
| `StructureTest` | fractal pivots, zone clustering, volume-profile bucketing, retracement math (up and down swings), base detection, extremes |
| `SnapshotCalculatorTest` | on about 300 real days of HRTA, SMSM and MYOR with IHSG (`src/test/resources/technical/*.csv`): the complete versioned schema, valid tick prices, determinism and the input hash, limited history, missing sessions vs stale prices, earnings reaction |
| `TechnicalControllerTest` | every endpoint against the Docker Postgres (rolled back): search, bars, aggregation, indicators, levels, snapshot, events, market, settings, invalid parameters, unknown ticker, a 30-day listing and one without prices; IHSG as a listing (search, bars in points by `IHSG` and `^JKSE`, levels with 2 decimals, snapshot not stored, no events, summary); LQ45 as a listing (found after IHSG, its own bars by `LQ45` and `^JKLQ45`, relative strength vs IHSG, levels with 2 decimals, no events, summary); the summaries of a stock (valid IDX prices, stop < buy < sell) |
| `PresetSummariesTest` | the four summaries on the real HRTA, SMSM and MYOR fixtures (stance, valid IDX prices, stop < buy < sell, reward to risk, thesis), determinism, an uptrend bought and a downtrend avoided (PTJ: a buy-stop above the close below the 200-day, 5:1 target), an index in points without relative strength vs itself, no prices with fewer than 15 bars; USD/JPY read through its own trend (prices at its 0.001 tick, no IHSG votes); no preset reads macro data |
| `TechnicalControllerTest` (instruments) | a synthetic currency pair (markets dropdown, search, bars in its unit with its `tickSize` 0.001 in the header, levels at 0.001, no volume node, snapshot schema 2 with regimes, summary with `priceDecimals` 3, unknown instrument 404) and a monthly commodity (interval forced to M, 422 for levels / snapshot / summary) |
| `IndexIngestionServiceTest` | the IHSG ingestion: from the latest stored day, today's bar only after the cutoff, no request when up to date, the whole history the first time and with `full` |

## 7. Known limitations

- The preset summaries are fixed rules over the indicators, not the investors' own judgement: no fundamentals, news,
  order flow or position sizing; a stance can change with every new bar. "For information only. Not investment advice."

- No earnings, RUPS / RUPSLB, rights-issue or private-placement dates: `reporting_period.filing_date` is not filled
  by the ingestion yet, and no event source exists for the others.
- The special monitoring board and suspensions have no source.
- The monthly series (coal, LNG, nickel, tin, rubber, urea, DAP, potash from the World Bank; cobalt from the IMF; the wood
  pulp index from FRED) have a chart, but no levels, snapshot or summaries.
- Breadth and the relative-strength percentiles are over the listings stored in Neraca Lab (61 on 2026-10-14), not
  the whole exchange.
- `value` (value traded) is approximated by close x volume.
- No springdoc / OpenAPI types: the API types are hand-written zod schemas (`src/lib/technical.ts`).

## 8. Preset summaries

`technical.engine.PresetSummaries` (rules version `2026-10-16.2`, technical only), served by `GET .../summary` and shown below the chart.
Input: the listing's snapshot and its daily indicators (the 20 / 50-day channels of the bars before the last one, EMA
20). Each preset scores its own evidence (every vote is listed in `points`), takes a stance from the score, and derives
three prices. Every price is a valid IDX price (an index: 0.01); stop < buy < sell always holds (a target at or below
the entry becomes 3 ATR - PTJ 5 - above it, a stop at or above the entry 1 ATR below it). Without an ATR (fewer than 15
bars) no prices are given. A missing indicator (limited history, no IHSG) does not vote.

| Preset | Evidence (votes) | Stance | Buy | Sell (target) | Stop |
|---|---|---|---|---|---|
| Murphy (trend with confirmation) | close vs SMA 200 (primary trend), golden / death cross, close vs SMA 50, MACD histogram, RSI (overbought / oversold), OBV slope, relative strength vs IHSG | score ≥ 3 BUY, ≥ 1 ACCUMULATE, > -2 WAIT, else AVOID | BUY / ACCUMULATE: the nearest support below the close within 12 % (support zone top, retracement of the up swing, SMA 50 / 200); else 1 ATR below. WAIT / AVOID: the strongest support zone (most touches) or the 52-week low, only after a close back above the SMA 50 | the nearest resistance above the entry + ½ ATR (resistance zone bottom, retracement of a down swing, SMA 200 overhead, 52-week high) | ½ ATR below the support zone, else 1.5 ATR below the entry |
| Keith Gill (contrarian accumulation) | a base (+1.5), position in the 52-week range, drawdown from the all-time high (≤ -50 %), up / down volume ratio, volume spikes on up vs down days, OBV, SMA 200 (back above / falling) | ≥ 3 BUY, ≥ 1.5 ACCUMULATE, ≥ -1 WAIT, else AVOID | in a base: its lower quarter (at the close when already there); above the base: a retest of the base high; no base: the heaviest volume node below the close, a support zone, the 52-week low | the 52-week high (prior peak) if 5 % above, else the all-time high, else the next resistance | ½ ATR below the base low / volume node / support zone, else 2 ATR |
| Paul Tudor Jones (200-day rule, breakouts) | above / below the SMA 200 (±2), ADX ≥ 25 / ≥ 20 with +DI vs -DI, a break of the 20-day high / low, top of the 50-day channel, ATR in the top 20 % of the year | below the SMA 200 AVOID; trending (ADX ≥ 20, +DI leads) BUY when score ≥ 2.5 else ACCUMULATE; ADX < 20 WAIT | a buy-stop one tick above the 20-day high (at the close when today broke it); AVOID: a buy-stop above the SMA 200 and the 20-day high | 5 times the risk (5:1) | the 20-day low when within 2 ATR, else 2 ATR below the entry; trail to the 20-day low |
| Druckenmiller (leadership and the market) | IHSG vs its SMA 200 (±1), breadth (≥ 55 / ≤ 45 %: ±0.5), 6-month return percentile (±1), 12-month (±0.5), relative strength vs IHSG over 50 days (±1), 12-1 month momentum (±0.5), close vs SMA 50 (±0.5) (an index: no leadership votes; a Forex pair or commodity: its own SMA 200 instead of IHSG and leadership, section 9) | share of the maximum score: ≥ ½ BUY, ≥ ⅙ ACCUMULATE, ≥ -⅙ WAIT, else AVOID | a pullback within 10 % (EMA 20, SMA 50, support zone) else ½ ATR below the close; WAIT / AVOID below the SMA 50: a buy-stop above it | the 52-week high if 3 % above, else the all-time high, else 3 ATR above the close | 1 ATR below the SMA 50 when it is below the entry, else 2 ATR |

Example (ANTM, 2026-10-15, close Rp 2,990): Murphy AVOID (buy 2,920 only after a close above the SMA 50, sell 3,060,
stop 2,740); Keith Gill BUY (a 73-day base: buy 2,900, sell 4,970 at the 52-week high, stop 2,680); Paul Tudor Jones
AVOID (below the 200-day: buy-stop 3,420, target 4,570 = 5:1, stop 3,190); Druckenmiller AVOID (IHSG 16.8 % below its
200-day: buy-stop 3,160 above the SMA 50).

## 9. Forex pairs and commodities

The dropdown of the page lists the exchanges, then **Forex** and **Commodities** (`GET /api/v1/technical/markets`).
With a market, the search finds its currencies (9: USD/IDR, USD/JPY, USD/CNY, USD/MYR, USD/PHP, USD/INR, USD/KRW,
EUR/USD, AUD/JPY) or commodities (26: Brent, WTI, natural gas, LNG, coal, gold, silver, copper, aluminium, iron ore, steel hot-rolled coil,
nickel, tin, cobalt, palm oil in USD and in ringgit, soybean oil, soybean meal, corn, wheat, sugar, rubber, wood pulp
index, urea, DAP, potash): the
instruments of Price Ingestion ([PRICE_INGESTION_DOCS.md](PRICE_INGESTION_DOCS.md), section 1b). Every API above takes
the market as `{exchange}` and the instrument code as `{ticker}` (`/api/v1/technical/FOREX/USDIDR/summary`).

- **Bars** from `instrument_daily` in the quote currency and unit ("IDR per USD", "USD per barrel"). The header has
  `type` FOREX / COMMODITY, `unit`, `frequency` and `source`, and `lotSize` null. There is nothing to adjust, so the page
  hides the price-basis toggle.
- **Levels and preset prices** are rounded to the instrument's tick (USD/IDR 1, USD/JPY and AUD/JPY 0.001, USD/PHP and
  USD/INR 0.001, USD/KRW 0.01, USD/CNY, USD/MYR and EUR/USD 0.0001, Brent 0.01, gold 0.1, palm oil 0.25, palm oil in
  ringgit 1, corn and wheat 0.25 US cents, copper 0.0005). The summary returns `priceDecimals` and `unit` for the page.
- **Decimals on the page** follow the tick (since 2026-10-23): the bars' header (`ohlcv`, `indicators`) carries the
  instrument's `tickSize`, and the chart's price scale, the header price and the snapshot's ATR and MACD (which are in
  price units) use its decimals - EUR/USD 1.1235 with an ATR of 0.0062, where 2 decimals showed 1.12 and an ATR of 0.
  A stock or index has no `tickSize` in the header and its chart keeps its formatting; the snapshot's ATR and MACD use
  at least the decimals of the snapshot's `price.tickSize` (a stock, tick 1 or more: unchanged; IHSG, tick 0.01: the
  ATR with 2 decimals instead of 1).
- **What does not apply** (as for IHSG): relative strength vs IHSG and the comparison line, events, auto-rejection, the
  lot.
- **The snapshot** is computed on request and kept in memory. These fields are null, with a note:
  - the percentiles among the listed stocks;
  - value traded;
  - the IDX session checks (the instrument trades on its own calendar).

  The market regime group is that of IDX.
- **No volume.** A currency pair has no volume (over the counter). The volume indicators are empty, the volume profile
  has no node, and a caveat says so.
- **The World Bank, IMF and FRED series are monthly.** They are charted on the monthly interval whatever was asked; the interval toggle shows "Monthly
  series". Levels, the snapshot and the summaries need daily bars, so these APIs answer **422 "Monthly series"** for it.
  The page shows the chart and its indicators on monthly bars, and a note instead of the summaries.
- **Druckenmiller** reads an instrument through its own trend instead of IHSG and leadership: above / below its
  SMA 200 (±1.5), 12-1 month momentum (±1), the SMA 50 (±0.5). Murphy, Keith Gill and Paul Tudor Jones apply their rules
  to the bars unchanged. No preset reads macro data (since 2026-10-16; the macro view is the Macro page,
  [MACRO_DOCS.md](MACRO_DOCS.md), section 6a).
