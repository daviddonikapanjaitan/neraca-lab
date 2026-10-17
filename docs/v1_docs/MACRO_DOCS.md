# Macro data (Ingestion › Macro)

The macro data of the **Macro page** (section 6a), read the way **Druckenmiller**, **Paul Tudor Jones**, **George
Soros** and **Ray Dalio** read markets. The requirements are in [`macro.md`](../../macro.md); this document describes what is built. Companies ›
Technicals reads **no** macro data since 2026-10-16: its presets and panels are technical only.

- **Druckenmiller** reads markets through liquidity: the direction of the Fed and Bank Indonesia, US net liquidity,
  credit, the dollar, the rupiah and foreign money.
- **Tudor Jones** reads the regime: volatility, credit, the US yield curve, the dollar, inflation and Indonesian yields.
- **Soros** reads reflexivity: the loop of foreign money, the rupiah and Bank Indonesia that feeds a boom or a bust.
- **Dalio** reads the economic machine: the growth / inflation quadrant, real rates and credit.

Both read **direction and change** (cutting or hiking, steepening or flattening, liquidity growing or shrinking), not
levels. The series are stored as they are published. Signals and regimes are computed from them when the Macro page
is requested (section 5).

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

Either way a rate is public on its own date: the Forex ingestion stores a day only once it is complete, and the ECB
fixes its rate the same afternoon. So the Macro page shows the latest Forex close as soon as the price ingestion has
stored it (Ingestion › Price Ingestion, market Forex, or its update of all currencies); its chart names the source.

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
- The signals only use observations whose release date is on or before their as-of date. For example, September CPI
  (public on 15 October) is not known as of 14 October. The Macro page reads as of today, so everything public by now
  counts.
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

The Macro page (`GET /api/v1/macro/outlook`, permission COMPANIES, section 6a) returns the series, signals and regimes.

## 5. Signals and regimes (`technical.engine.MacroSignals`)

Computed for the Macro page from the observations released by its as-of date (today). The Technicals snapshot had a
`macro` section with them until schema 2; schema 3 (2026-10-16) has none.

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

The macro panel, the regimes and the macro preset summaries are on the Macro page (section 6a); Companies › Technicals no
longer shows them (2026-10-16).

## 6a. The Macro page (sidebar › Macro, permission COMPANIES)

A country's stock index read through its macro data, between Companies and Screening in the sidebar
(`/macro?country=ID&index=IHSG&range=1Y`; the choice is kept in the URL). Everything is computed in Java by fixed
rules (`macro.outlook.MacroOutlook`, rules version `2026-10-16.1`), no AI model; the page only draws it.

**Choices**
- **As of today** (Jakarta): every observation stored and public by now is read - the latest Forex close of USD/IDR as
  soon as it is stored, not only data up to the index's last trading day. Each chart names its source (USD/IDR: "Forex
  price ingestion (Yahoo Finance IDR=X)", else the ECB rates).
- **Country**: Indonesia. The United States and China are listed as "coming later" (disabled); the API answers 400.
- **Index** of the country: IHSG (`^JKSE`) and LQ45 (`^JKLQ45`, from 1997). LQ45 is stored in `index_daily` with IHSG,
  in the same index job of the price queue (`neracalab.technical.other-indexes`); the index job counts as up to date
  only when both reach the last completed trading day, so a missing LQ45 queues it.
- **Range** of the charts: 6M, 1Y (default), 3Y, 5Y, 10Y, MAX. The rules always read the whole history.

**Sections, top to bottom**
1. **The index** as a plain line (no indicators): close, 1M / 3M / YTD / 1Y change, 52-week range, the investability
   score and the action in the header. Its 200-day average and the average's slope (against a month before) are used
   by the rules, not drawn.
2. **Macro panel (liquidity and risk regimes, rates, dollar)**: the two regimes of section 5 with every vote and their
   state three months before, then one chart per series - Indonesia (BI rate, CPI, SBN 10Y, USD/IDR, reserves in USD bn,
   M2 YoY, and the CSV series when uploaded) and global (Fed funds, US 10Y, the US curve 10Y - 2Y, US net liquidity, the
   high-yield spread, DXY, VIX, US CPI YoY, China PMI). Under each chart a **summary** of what it says for the country's
   equities and a tone (Supportive / Headwind / Neutral). The summaries read the same signals as the regimes, so they
   agree with the votes; a stale series says so instead of guessing. Derived charts: the curve (10Y - 2Y by date), net
   liquidity (Fed assets - TGA - reverse repo, weekly), CPI and M2 YoY (month against the same month a year before).
3. **Preset summaries** - the macro side, not the chart:

   | Preset | Stance |
   |---|---|
   | **Paul Tudor Jones** (defence first) | Below the 200-day average: AVOID ("nothing below the 200-day average"; WAIT when the risk regime is risk-on). Above it: BUY when risk-on, ACCUMULATE when neutral with a score of 0 or more, else WAIT; risk-off: WAIT (tighten stops). |
   | **Druckenmiller** (liquidity first, 12 - 24 months ahead) | Liquidity EASING: BUY above the 200-day average, ACCUMULATE below it (liquidity leads prices). NEUTRAL: ACCUMULATE when the liquidity score rose by 2 or more in 3 months (turning), else WAIT. TIGHTENING: AVOID, WAIT when turning. |
   | **George Soros** (reflexivity) | The loop: foreign net buying on IDX (20 sessions), foreign SBN holdings (1 month), the rupiah, BI's direction, BI reserves (+1 at +1 % in 3 months, -1 at -2 %), the DXY and the country premium (SBN 10Y over the US 10Y, +/- 0.25 points in 3 months) each vote +1 / -1 / 0; VIRTUOUS at +2, VICIOUS at -2, unknown with fewer than 3 votes; compared with the loop 3 months before (turning: 2 points or more). VIRTUOUS: BUY above the 200-day average, ACCUMULATE below it, WAIT when it weakened by 2 or more (the boom losing its fuel). NEUTRAL: ACCUMULATE when it improved by 2 or more, AVOID when it weakened by 2 or more with the index stretched 15 % or more above its average, else WAIT. VICIOUS: AVOID (a bust, or a trend whose premise is failing), WAIT when it improved by 2 or more. |
   | **Ray Dalio** (the economic machine) | The quadrant: growth from the proxies stored (China PMI, the US curve, Indonesian M2; no GDP series) - EXPANDING at a mean of +1/3, SLOWING at -1/3; inflation from Indonesian CPI against BI's band and the 3-month trend of Indonesian and US CPI - RISING at +1/2, FALLING at -1/2. Goldilocks (growth up, inflation not rising), reflation (both up), stagflation (growth down, inflation up), disinflationary slowdown (both down or inflation stable), else no clear quadrant. Votes: growth, inflation (falling +1), the Indonesian real rate, the US 10Y real yield (+/- 0.25 points in 3 months), credit; unknown with fewer than 3. +2 or more: BUY above the 200-day average, ACCUMULATE below it (a disinflationary slowdown: ACCUMULATE at most); -2 or less: AVOID; else WAIT (balanced, All Weather). Stagflation is never bought: AVOID at -2 or less, else WAIT. |

   Each has its thesis, the evidence (every vote, the real rate, reserves, the trend) and **buy when / sell when**.
4. **Conclusion: is the country investable?** A score from 0 to 100 = 50 + 50 x the weighted mean of six readings
   (each -1 .. +1; a part without data is left out and the weights re-normalised):

   | Part | Weight | Reading |
   |---|---|---|
   | Liquidity regime (Druckenmiller) | 25 | its score / its number of signals |
   | Risk regime (Tudor Jones) | 20 | its score / its number of signals |
   | Indonesian monetary policy | 15 | mean of BI cutting +1 / holding 0 / hiking -1 and the real rate accommodative +1 / neutral 0 / restrictive -0.5 |
   | Inflation vs BI's target (2.5 % +/- 1) | 10 | inside the band and not rising +1, above it or rising -1, below it 0 |
   | Rupiah and external buffers | 15 | mean of the rupiah (strengthening +1 / weakening -1), reserves over 3 months (>= +1 % +1, <= -2 % -1) and the SBN 10Y (falling +1 / rising -1) |
   | Index trend | 15 | mean of above / below the 200-day average (+1 / -1) and the average rising / falling (+1 / -1) |

   **Verdict**: 60 and more INVESTABLE, 40 - 59 SELECTIVE, below 40 NOT INVESTABLE. **Action**:

   | Score | Index above its 200-day average | Below it |
   |---|---|---|
   | 60 + | BUY | ACCUMULATE (in steps) |
   | 40 - 59 | ACCUMULATE on dips (WAIT when liquidity is tightening) | WAIT |
   | < 40 | WAIT (no new money, tighten stops) | SELL / reduce |

   The page lists the conditions with whether each is met now - **buy** when the score is 60+ and the index closes above
   its 200-day average; **accumulate** in the two cases above; **sell / reduce** when the score is below 40 and the index
   closes below its average - and "what would change the call": the parts against, heaviest first.

On 2026-10-16 (IHSG 5,185, 26 % below its 200-day average of 7,033): liquidity tightening (-3 of 6), risk neutral (-1 of
6), BI hiking with a restrictive real rate, inflation inside the band, a firmer rupiah - **36 / 100, not investable now,
sell / reduce**; the Paul Tudor Jones and Druckenmiller presets AVOID.

| Endpoint (permission COMPANIES) | Returns |
|---|---|
| `GET /api/v1/macro/outlook/countries` | the countries (`available`) and their indexes (`rows`, `lastDate`) |
| `GET /api/v1/macro/outlook?country=ID&index=IHSG&range=1Y` | `country`, `range`, `countries`, `asOfDate`, `index` (with `points` `[epoch s, close]`), `regimes` (`stateThen`, `scoreThen`), `panels` (`points`, `lastValue`, `tone`, `summary`, `readBy`), `presets` (`ptj`, `druckenmiller`, `soros`, `dalio`), `conclusion` (`score`, `verdict`, `action`, `components`, `buyWhen` / `accumulateWhen` / `sellWhen` with `met`, `watch`), `notes`, `disclaimer`. 400: a country not available yet, an unknown country, index or range |

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
| `MacroControllerTest` | The catalog and states, the history, a manual series refused, the CSV upload checked then stored (release date by the lag, a MACRO job). |
| `IngestionJobTypeConstraintTest` | Every script that re-creates the job-type check lists `MACRO`. |
| `MacroOutlookTest` | The Macro page's rules on synthetic data: tight money, risk-off and a downtrend not investable (SELL, all four presets AVOID, the panels' tones); easy money, risk-on and an uptrend investable (BUY); good macro below the 200-day average accumulated, not bought; the derived series (curve, net liquidity, CPI YoY) and point in time; missing data noted, not guessed; Soros's loop (-3 of 4 a bust, +4 of 5 a boom); Dalio's Goldilocks bought, stagflation never. |
| `MacroOutlookControllerTest` | The countries (Indonesia with IHSG and LQ45; the US and China later), the outlook's sections on the stored data, USD/IDR = the latest Forex close (date, value, source), the refusals (400), a login required. |
| `IndexIngestionServiceTest` | LQ45 ingested with IHSG from its own latest day, its failure not failing IHSG; up to date only when both are. |
