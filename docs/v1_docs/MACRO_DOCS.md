# Macro data (Ingestion › Macro)

The macro data of the **Druckenmiller** and **Paul Tudor Jones** presets of Companies › Technicals. The requirements
are in [`macro.md`](../../macro.md); this document describes what is built.

- **Druckenmiller** reads markets through liquidity: the direction of the Fed and Bank Indonesia, US net liquidity,
  credit, the dollar, the rupiah and foreign money.
- **Tudor Jones** reads the regime: volatility, credit, the US yield curve, the dollar, inflation and Indonesian yields.

Both read **direction and change** (cutting or hiking, steepening or flattening, liquidity growing or shrinking), not
levels. The series are stored as they are published. Signals and regimes are computed from them when a snapshot or
summary is computed (section 5).

## 1. Series

The catalog is `macro_series_def`, seeded by `V1.0.26__data_markets_macro.sql`. Adding a series needs no code change
when its source exists. Every source was checked live on 2026-10-15.

The **Prio** column: 1 = core, 2 = next, 3 = nice to have. The **Lag** column: the days after the end of the period
when the value is public.

| Code | Series | Unit | Frequency | Source | Lag | Prio |
|---|---|---|---|---|---|---|
| `BI_RATE` | BI Rate | % | per decision | Bank Indonesia BI-Rate page (Download export) | 0 | 1 |
| `ID_CPI_YOY` | Indonesia CPI inflation, YoY | % | monthly | Bank Indonesia inflation page (Download export) | 2 | 1 |
| `SBN_10Y` | Indonesia 10-year government bond yield | % | daily | CNBC `ID10Y-ID` bars (2011-) | 0 | 1 |
| `ID_FX_RESERVES` | BI foreign exchange reserves | USD mn | monthly | SEKI `TABEL5_9.xls`, row "Total" | 8 | 2 |
| `ID_M2` | Indonesia broad money M2 | IDR bn | monthly | SEKI `TABEL1_1_1.xls`, row "Uang Beredar Luas" | 31 | 2 |
| `ID_FOREIGN_NET_BUY` | Net foreign buy / sell on IDX | IDR bn | daily | **CSV upload** (the IDX site answers a bot challenge) | 0 | 2 |
| `ID_SBN_FOREIGN` | Foreign ownership of tradable SBN | IDR tn | daily | **CSV upload** (DJPPR report) | 1 | 2 |
| `ID_CDS_5Y` | Indonesia 5-year CDS | bps | daily | **CSV upload** (no free source) | 0 | 3 |
| `US_FED_FUNDS` | Effective Fed Funds rate | % | daily | FRED `DFF` | 1 | 1 |
| `US_10Y`, `US_2Y` | US Treasury 10Y / 2Y yield | % | daily | FRED `DGS10`, `DGS2` | 1 | 1 |
| `US_CPI_INDEX` | US CPI (YoY derived) | index | monthly | FRED `CPIAUCSL` | 15 | 1 |
| `US_FED_ASSETS` | Fed total assets | USD mn | weekly | FRED `WALCL` | 1 | 1 |
| `US_HY_SPREAD` | US high-yield spread (ICE BofA OAS) | % | daily | FRED `BAMLH0A0HYM2` (FRED serves the last 3 years only) | 1 | 1 |
| `DXY` | US Dollar Index | index | daily | Yahoo Finance `DX-Y.NYB` | 1 | 1 |
| `US_TGA` | Treasury General Account | USD mn | weekly | FRED `WTREGEN` | 1 | 2 |
| `US_RRP` | Fed overnight reverse repo | USD bn | daily | FRED `RRPONTSYD` | 1 | 2 |
| `US_BREAKEVEN_10Y` | 10-year breakeven inflation | % | daily | FRED `T10YIE` | 1 | 2 |
| `US_CORE_CPI_INDEX` | US core CPI (YoY derived) | index | monthly | FRED `CPILFESL` | 15 | 2 |
| `US_M2` | US M2 | USD bn | monthly | FRED `M2SL` | 25 | 2 |
| `VIX` | CBOE Volatility Index | index | daily | FRED `VIXCLS` | 1 | 2 |
| `COT_GOLD_NET`, `COT_CRUDE_NET`, `COT_USD_NET` | Speculative net positions (CFTC) | contracts | weekly | CFTC Public Reporting, legacy futures only (`088691`, `067651`, `098662`) | 3 | 3 |
| `CN_PMI_MFG` | China manufacturing PMI | index | monthly | **CSV upload** | 1 | 3 |

USD/IDR is not a macro series of its own; it is read for the macro view as `USD_IDR`:
- from the **Forex** price ingestion (`instrument_daily` USDIDR, Yahoo Finance) when stored;
- otherwise from the ECB reference rates in `fx_rate_daily`.

Either way it is public the day after its date, because both close after the IDX session.

Gold, crude oil, coal and palm oil are prices (Price Ingestion with the market Commodities, `PRICE_INGESTION_DOCS.md`),
not macro series.

What macro.md lists but is not ingested:
- the trade balance and the current account (BPS needs an API key; the BI balance of payments table is quarterly);
- Indonesian bank loan growth.

These can be added as manual series with a CSV.

## 2. Ingestion

**Job model**
- One job per series, in its own in-memory queue with **one worker**: the sources are asked one after the other.
- Each job is recorded in `ingestion_job` with type `MACRO`, exchange `MACRO` and the series code as ticker.
- At most one active job per series.
- A restart drops queued jobs (submit again); nothing is fetched twice.

**Window**
- Incremental: from **45 days before** the latest stored observation (`neracalab.macro.overlap-days`), so recent
  revisions are picked up.
- The first run, or **Full**, fetches from the series' `history_from` (2000, or the source's own start).
- A source that always returns its whole table (the Bank Indonesia exports, SEKI) is read whole. Only the window is kept.

**Checks before anything is stored**
- A value outside the series' range (`min_value .. max_value`) fails the job and **nothing is stored**. Example:
  an inflation of 500 %.
- A date given twice, or a date in the future, also fails the job and nothing is stored.
- A move larger than `max_change` between two observations is stored but **listed as a warning** in the result and the
  stage ("2 large moves to check"). Real examples: the year-end Fed funds spikes of 2000 and 2001, the Fed balance sheet
  in March 2020.

**Point in time**
- Every observation gets `period_end` (the last day of its month for a monthly series) and
  **`release_date` = `period_end` + `release_lag_days`**.
- The snapshot and the presets only use observations whose release date is on or before their as-of date. For
  example, September CPI (public on 15 October) is not known to a snapshot of 14 October.
- The lags are publication estimates. FRED does not carry the vintage of each value.

**Revisions**
- A changed value replaces the stored one and counts up `revision`; the first release date is kept.
- The result lists the revisions ("2026-08-01: 334.7 -> 334.9").
- No vintages are stored: a revision is visible to an earlier as-of date. This is a known limitation (section 7).

**Up to date**: `MacroSeries.expectedLatest(today)` is the latest observation that should be public now:
- **daily**: the weekday before today minus the lag;
- **weekly**: 7 days plus the lag;
- **monthly / quarterly**: the latest period whose end plus the lag has passed;
- **BI rate**: 31 days after the last decision (BI meets monthly).

A series with an older latest observation is **behind**; "Update all macro data" and the evening run queue only those.

**CSV upload** (any series; the only way for the manual ones)
- Format: lines of `obs_date,value`. A header line is allowed; `,`, `;` or a tab separate the fields.
- Dates are ISO (`2026-10-15`). A monthly series is dated on the 1st.
- Values use a decimal point.
- The upload is checked like a fetch, stored with source `manual`, and recorded as a MACRO job.
- Observations on other dates are kept.

## 3. Sources

All requests go through `crawl.PoliteHttpClient`:
- a User-Agent that names the application (`neracalab.crawl.user-agent`);
- one request per host at a time, with **2 s** between two requests to the same host;
- a cookie store;
- **no retries and no work-arounds**: a refusal (403, 429, a bot challenge) fails the job with its status, and the
  series can be uploaded as a CSV instead.

Parsers fail loudly on a layout they do not know.

| Source | How |
|---|---|
| FRED (`FredSource`) | `https://fred.stlouisfed.org/graph/fredgraph.csv?id={id}&cosd={from}`, no API key. Header `observation_date,{ID}`; a day without a value (`.` or empty) is skipped. |
| Yahoo Finance (`YahooMacroSource`) | The chart client of the price ingestion (paced with it); daily closes; today's New York bar is left out. |
| CNBC (`CnbcSource`) | `https://ts-api.cnbc.com/harmony/app/bars/{symbol}/1D/{from}/{to}/adjusted/EST5EDT.json`, **one request per calendar year**: a request over many years leaves days out (2026-09-10 and 2026-09-21 were missing from the 2011 - 2026 answer). The close of each day. Weekend-dated bars are left out: they are sessions dated a day early by the service's US time zone (most of 2015, next to the bar of the right day) or repeats of Friday. Today's (Jakarta) bar is left out. CNBC has no bar in 2014: 3,394 weekdays from September 2011. |
| Bank Indonesia (`BankIndonesiaSource`) | Reads the page, then posts its ASP.NET form back with the **Download** button and the page's date range (BI-Rate: `TextBoxDateStart / End`, dd/mm/yyyy; inflation: `TextBoxDateFrom / To`, mm/yyyy). The answer is an `.xlsx` with a "Period" column ("23 September 2026", "19 Agustus 2026", "Agustus 2026": Indonesian or English months) and a value column ("5.75 %"). |
| BI SEKI (`BiSekiSource`) | `https://www.bi.go.id/SEKI/tabel/{table}` (`.xls`); `source_ref` = `TABLE.xls#row label`. The months run across the columns: a row of month names (Jan .. Des; `*` = provisional) under a row of years written once per year. A year is counted from the first label (a month not after the one before starts the next year), and every year label must agree with that count, or the job fails. A column without a month (the yearly total) is skipped. |
| CFTC (`CftcSource`) | `https://publicreporting.cftc.gov/resource/6dca-aqww.json` (Socrata), filtered by `cftc_contract_market_code` and date; net = non-commercial long - short, dated on the Tuesday of the positions (public on Friday). |

## 4. API (permission INGESTION)

| Endpoint | Does |
|---|---|
| `GET /api/v1/macro/series` | Every series with `rows`, `firstDate`, `lastDate`, `lastValue`, `lastRelease`, `expectedLatest`, `state` (`UP_TO_DATE`, `BEHIND`, `NEVER_FETCHED`, `FAILING`, `MANUAL`) and its last job; counts per state. |
| `GET /api/v1/macro/series/{code}?from=2025-01-01` | One series and its observations: `[date, value, released]`. |
| `POST /api/v1/macro/ingestions?code=US_10Y[&full=true]` | Queues one series: **202** new job, **200** the job already queued / running. A manual series: 400. |
| `POST /api/v1/macro/updates` | "Update all macro data": queues every series with a source that is behind (**202**: queued / already active / up to date / manual). |
| `POST /api/v1/macro/series/{code}/upload` | Multipart `file`: a CSV of `obs_date,value`. Rejected: **422** with the reason, nothing stored. |

The jobs are listed by `GET /api/v1/ingestions?type=MACRO`; the result is `MacroIngestionService.Result`:
- `inserted`, `revised`, `unchanged`;
- `received` / `kept`;
- `latestDate`, `latestRelease`;
- `warnings`, `revisions`.

The evening run (`neracalab.macro.schedule.enabled` / `MACRO_SCHEDULE_ENABLED`, default `false`, cron `0 0 20 * * *` WIB)
does "Update all macro data".

The Technicals market panel (`GET /api/v1/technical/market`, permission COMPANIES) returns the last year of every series
and the signals and regimes as of the latest market day (`macroSignals`).

## 5. Signals and regimes (`technical.engine.MacroSignals`)

Computed for each snapshot (the `macro` section, schema 2; `docs/indicator-snapshot.md`) from the observations released
by its as-of date.

A series older than its frequency allows is **stale**. Its signals are null, with a note:
- daily: 10 days;
- weekly: 21 days;
- monthly: 80 days;
- the BI rate holds until the next decision.

| Signal | Definition |
|---|---|
| `biPolicy`, `fedPolicy` | Rate and its 6-month change: `CUTTING` (≤ -0.2), `HIKING` (≥ +0.2), `HOLDING`. |
| `idRealRate` | BI rate - Indonesian CPI YoY: `RESTRICTIVE` (≥ 2), `ACCOMMODATIVE` (< 0), `NEUTRAL`. |
| `sbn10y` | Yield, its 3-month change (`RISING` / `FALLING` at ±0.25) and its spread over the US 10Y. |
| `usCurve` | 10Y - 2Y: `INVERTED` (< 0), `FLAT` (< 0.5), `NORMAL`; 3-month trend `STEEPENING` / `FLATTENING` (±0.15); the 10Y's own 3-month change. |
| `usNetLiquidity` | Fed assets - TGA - reverse repo (USD bn) and its 3-month change: `GROWING` / `SHRINKING` (±1 %). With the Fed assets only, their change, and `basis` says so. |
| `creditStress` | US high-yield spread, its percentile over a year and its 1-month change: `STRESSED` (≥ 80th percentile or +1 point), `CALM` (≤ 30th), `NORMAL`. |
| `dollar` | DXY 3-month change (`STRENGTHENING` / `WEAKENING` at ±2 %); USD/IDR 3-month change (rupiah `WEAKENING` at +2 %, `STRENGTHENING` at -1 %). |
| `inflation` | Indonesian CPI and US CPI / core CPI YoY (from the index, month against the same month a year before) and their 3-month trend (±0.3); breakeven; the US real 10Y yield (10Y - breakeven) and its change. |
| `riskAppetite` | VIX: `FEAR` (≥ 25), `CALM` (≤ 16), `NORMAL`; its percentile over a year. |
| `foreignFlows` | Net foreign buying on IDX over the last 20 observations (`INFLOW` / `OUTFLOW`); the 1-month change of foreign SBN holdings. |
| `idLiquidity` | BI reserves (USD bn) and their 3-month change; M2 YoY. |
| `china` | PMI: `EXPANSION` (≥ 50) / `CONTRACTION`. |
| `positioning` | Gold, crude, USD: the net position's percentile over 3 years: `CROWDED_LONG` (≥ 90th), `CROWDED_SHORT` (≤ 10th). |

Each of the two **regimes** is the sum of the votes of its signals (+1 / 0 / -1). It is unknown with fewer than 3 voting
signals. Each vote is listed with its reason.

- **Liquidity (Druckenmiller)**: the votes are the Fed, US net liquidity, credit, the DXY, the rupiah, the BI rate and
  foreign flows. `EASING` at +2, `TIGHTENING` at -2, else `NEUTRAL`.
- **Risk (Tudor Jones)**: the votes are the VIX, credit, the US curve (inverted: -1), the DXY, US inflation and the
  SBN 10Y yield. `RISK_ON` at +2, `RISK_OFF` at -2, else `NEUTRAL`.

How the preset summaries use them (`PresetSummaries`, rules version 2026-10-16.1):

**Druckenmiller**
- A stock or IHSG: the liquidity regime votes ±1, listing the signals behind it. It replaces the separate USD/IDR and
  BI-rate votes, which stay as the fallback while there is no regime.
- A **currency pair or commodity** has no IHSG regime and no leadership among the stocks; it is read through its own
  drivers:

  | Instrument | Drivers |
  |---|---|
  | USD/IDR | The regime with the opposite sign (tighter liquidity lifts USD/IDR); the dollar; the US 10Y; the BI - Fed rate gap; foreign flows. |
  | USD/JPY | The US 10Y; the dollar; the risk regime (the yen is a haven). |
  | Gold | The regime; the US real yield; the dollar; speculative crowding. |
  | Brent, coal, palm oil | The regime; the dollar; the risk regime; China's PMI; crude crowding for Brent. |

**Tudor Jones**: risk-off votes -1, risk-on +0.5. The 200-day rule stays first.

## 6. Ingestion › Macro page

- **"Update all macro data to today"**: counts per state and one button.
- **One table per region** (Indonesia, United States, Global and China), with columns:
  - series (code, frequency, unit, which preset uses it);
  - source (link) and its publication lag;
  - latest value and date;
  - state (and why it fails).
- **Actions per series:**
  - **Fetch** and **Full** (not for a manual series);
  - **CSV** upload;
  - **Chart**: a sheet with the whole history and the latest observations with their release dates.
- The shared jobs table opens on the Macro tab.

Companies › Technicals shows the macro panel, with:
- the liquidity and risk regimes and every vote;
- the BI rate, SBN 10Y, USD/IDR, Indonesian CPI, Fed funds, US 10Y, DXY, US high-yield spread and VIX over the last year.

The snapshot card lists both regimes.

## 7. Limitations

- **No vintages**: a revision replaces the value, so an earlier as-of date sees the revised figure.
- **Estimated release dates**: they are publication lags, not the actual release time of each figure.
- **FRED high-yield spread**: FRED serves only the last 3 years of the ICE BofA series. The percentile over a year
  is unaffected.
- **Bank Indonesia's pages**: they publish CPI later than BPS does. ID_CPI_YOY can show "behind" for a few days a
  month; the evening run asks again.
- **SEKI tables**: the parser reads the layout of TABEL5_9 and TABEL1_1_1. A changed layout (a year label that does
  not follow the months) fails the job; it does not store wrong months.
- **Manual series**: IDX foreign flows, SBN ownership, CDS and the China PMI have no free source a program may read.
  Until a CSV is uploaded, their signals (`foreignFlows`, `china`) are null.
- **SBN 10Y**: CNBC has no 2014 and starts in September 2011, so a change over a window that falls in 2014 is null.

## 8. Tests

| Test | Covers |
|---|---|
| `MacroSourceParsersTest` | FRED CSV, CNBC bars, CFTC rows, the BI form (both date-field namings) and its `.xlsx` export, a SEKI `.xls` (years across columns, a total column, a wrong year label failing), Indonesian months. |
| `MacroSeriesTest` | Period end, release date, "up to date" for every frequency. |
| `MacroIngestionServiceTest` | Overlap window, the first full run, revisions, out of range / twice / future storing nothing, warnings, a manual series not fetched. |
| `MacroSignalsTest` | A tightening, risk-off set of series; point in time (September CPI unknown on 14 October); stale series; the BI rate held between decisions; the real rate. |
| `PresetSummariesTest` | USD/JPY read through its drivers (decimals of its 0.001 tick); tighter liquidity lifting USD/IDR and weighing on gold. |
| `MacroControllerTest` | The catalog and states, the history, a manual series refused, the CSV upload checked then stored (release date by the lag, a MACRO job). |
| `IngestionJobTypeConstraintTest` | Every script that re-creates the job-type check lists `MACRO`. |
