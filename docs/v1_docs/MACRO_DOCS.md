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

## 1a. Weather (since 2026-11-16): El Nino / La Nina, rainfall, drought - global and Indonesia

Seeded by `V1.0.63__data_macro_weather.sql` (the sources' check: `V1.0.62__schema_macro_weather.sql`). Two groups that are
never mixed: **global** (code `GL_*`, country `GLOBAL`) - the state of the world's climate and of the other producers -
and **Indonesia** (code `ID_*`, country `ID`) - the rainfall and drought of its palm oil, coal and rice regions.
Categories `WEATHER_ENSO`, `WEATHER_IOD`, `WEATHER_RAIN`, `WEATHER_DROUGHT`; every one is read by all four presets.
The research behind the choice (45 years of rainfall against the indexes) is in
`instruction/quant/1_quant_testing/la_nina_el_nino_rainfall_drought_index.md`, sections 11 - 13.

| Code | Series | Unit | Frequency | Source | Lag | Dated on |
|---|---|---|---|---|---|---|
| `GL_ENSO_RONI` | El Nino / La Nina: Relative ONI (NOAA's official index since 2026) | degC | monthly | NOAA CPC `RONI.ascii.txt` (1950-) | 7 | the 1st of the season's **last** month (ASO 2026 -> 2026-10-01) |
| `GL_ENSO_ONI` | Oceanic Nino Index (classic) | degC | monthly | NOAA CPC `oni.ascii.txt` (1950-) | 7 | as RONI |
| `GL_NINO34_WK` | Nino 3.4 weekly anomaly (OISST) | degC | weekly | NOAA CPC `wksst9120.for` (1981-) | 5 | the week's centre (Wednesday) |
| `GL_SOI` | Southern Oscillation Index, monthly (BoM scaling) | SOI | monthly | Long Paddock `MonthlySOI1887-1989Base.txt` (1991-06-) | 3 | the 1st |
| `GL_SOI_30D` | SOI 30-day, daily | SOI | daily | Long Paddock `DailySOI1887-1989Base.txt`: the mean of 30 consecutive daily SOI (1991-07-) | 1 | the window's last day |
| `GL_IOD_DMI` | Indian Ocean Dipole (DMI), observed | degC | monthly | JAMSTEC `SINTEX_DMI.csv`, column `Obs` (1982-) | 10 | the 1st |
| `GL_PROB_ELNINO_3M`, `GL_PROB_LANINA_3M` | NOAA's chance of El Nino / La Nina for the season centred 3 months after the issue | % | event | NOAA CPC probability table (current page + monthly archive, from April 2026) | 0 | the second Thursday of the issue month (CPC's issue day) |
| `GL_PROB_ELNINO_6M`, `GL_PROB_LANINA_6M` | the same, 6 months ahead | % | event | as above | 0 | as above |
| `GL_RAIN_MY_PALM`, `GL_RAIN_AU_COAL`, `GL_RAIN_IN_MONSOON` | rainfall of Malaysia's palm oil regions, Australia's coal basins (Bowen, Hunter), India's monsoon | mm | monthly | CHIRPS v3.0 (1981-) | 15 | the 1st |
| `ID_RAIN_SUM_PALM`, `ID_RAIN_KAL_PALM`, `ID_RAIN_KAL_COAL`, `ID_RAIN_JAVA_RICE` | rainfall of Sumatra's and Kalimantan's palm oil regions, Kalimantan's coal regions, Java's rice regions | mm | monthly | CHIRPS v3.0 (1981-) | 15 | the 1st |
| `GL_SPI3_*`, `ID_SPI3_*` (one per rainfall series) | drought: 3-month Standardized Precipitation Index of the rainfall series | SPI | monthly | derived (`spi`) from the stored rainfall | 15 | the 1st |

**Sources and how they are read** (all through `PoliteHttpClient`: its User-Agent names the application, 2 s between
two requests to a host; checked live on 2026-11-16):

| Source (`source`) | Class | How |
|---|---|---|
| NOAA CPC (`noaa_cpc`) | `NoaaCpcSource` | `{cpc}/data/indices/<file>`: `SEAS YR [TOTAL] ANOM` (the anomaly is the last column; CPC labels DJF with January's year and NDJ with November's, so NDJ 2024 ends in January 2025); `wksst9120.for` is fixed width with the numbers run together (`20.6-0.1`): the 6th number of a week is the Nino 3.4 anomaly. The probabilities: `{cpc}/products/analysis_monitoring/enso/roni/probabilities/` (`table#probabilities-table`, the issue month in "Issued November 2026") and `.../archives/?type=probabilities&month=MM&year=YYYY` (`table#enso-archives-table`); columns found by their headings, row k must be the season centred k months after the issue month or the job fails; a month without an archived table (March 2026) is skipped. |
| JAMSTEC (`jamstec`) | `JamstecSource` | `{jamstec}/SINTEX_DMI.csv`: `time,Obs,Mean,...` - the observed column; the forecast rows (no observation) are left out; `2026-10-15` -> 2026-10-01. |
| Long Paddock (`longpaddock`) | `LongPaddockSource` | `{longpaddock}/MonthlySOI1887-1989Base.txt` (`Year Month SOI`); `.../DailySOI1887-1989Base.txt#30D`: `Year Day Tahiti Darwin SOI` (Day of the year) averaged over 30 consecutive days, a window with a missing day left out (equal to Long Paddock's "average SOI for last 30 days": -11.00 on 2026-11-16). |
| CHIRPS (`chirps`) | `ChirpsSource`, `CogReader`, `TiffLzw` | `{chirps}/chirps-v3.0.<yyyy>.<mm>.cog` - the monthly global cloud-optimized GeoTIFF (7200 x 2400 float32, 0.05 degree, 60 N - 60 S, LZW, 512-pixel tiles). Read with **HTTP range requests**: the first 16 KB (all image directories), then only the full-resolution tiles the regions touch, adjacent tiles in one request (about 4 requests a month for all seven regions). A region (`ChirpsSource.REGIONS`) is a list of boxes that avoid foreign land (Sumatra's leave out Peninsular Malaysia; Kalimantan's stay south of Sarawak); its value is the cos(latitude)-weighted mean of the land pixels whose centre lies in a box (the sea is NoData, -9999). One month gives every region and is kept in memory 12 hours, so after the first rainfall series the others need no request. A file missing for one of the last three months is not published yet (the fetch stops); one missing further back fails the job. |
| SPI (`spi`) | `SpiSource`, `StandardizedPrecipitation` | no request: the stored rainfall of `source_ref`; 3-month totals; per calendar end month a gamma distribution (Thom's estimate) fitted to 1991 - 2020 with the share of zero totals apart; the cumulative probability as a standard normal score, bounded to +/- 4.5; at least 25 base years per calendar month or the job fails ("fetch ... in full first"). -1 moderate drought, -1.5 severe, -2 extreme; +1.5 very wet. |

Not used: the **Bureau of Meteorology**'s weekly IOD and daily SOI files answer **HTTP 403** to a crawler that names
itself (they worked only with a browser's User-Agent) - no work-around, the same numbers come from JAMSTEC and Long
Paddock; NOAA PSL's HadISST DMI stopped in May 2026; OSMC's weekly DMI lags seven weeks; BMKG publishes images and
PDFs only; CHIRPS 2.0 is retired.

**The COG overviews are not read.** The CHIRPS files carry four overview levels, resampled across the sea mask: coastal
pixels ring to 800 mm or go negative (Java, September 2015: 12.7 mm at full resolution, 121 mm in the 0.2-degree
overview). Only the full-resolution tiles are used; the Java reader agrees with an independent reader (Pillow / libtiff)
to the hundredth of a mm on every region (September 2026: Sumatra 126.19, Kalimantan palm 60.74, coal 35.34, Java 22.26,
Malaysia 238.52, Australia 7.57, India 183.09).

**First load: in chunks, paced, never losing what was read.** The CHIRPS server throttles a bulk download from one
address: at the default 2 s it answered HTTP 429 after about 85 range requests (2026-11-16, ~3 MB of tiles a month).
So:
- its host waits **8 s** between two requests (`neracalab.crawl.host-delays."[data.chc.ucsb.edu]"`; every other host
  keeps `min-delay` 2 s);
- a fetch reads at most **60 months** over the network (`ChirpsSource.MAX_MONTHS_PER_FETCH`; months from memory do not
  count), **stores them**, and ends with a `MacroSource.PartialFetch`: the job succeeds ("Stored ... - to be continued
  from 1986-01-01 (queued again)") and the queue submits the series again **from that month** at the end of the queue,
  so other series are never held up for long;
- an HTTP 429 also ends the fetch with what it read (stored); the refused request is **not retried** - the series is
  asked again **15 minutes later** from the month that was refused;
- a continuation that read nothing is asked again at most 6 times in a row (`MAX_IDLE_CONTINUATIONS`), each wait
  twice the one before (15, 30, 60, 120, 240, 480 minutes: a daily quota is waited out), then the series waits for the
  next Fetch / "Update all"; a continuation that stored something resets the count;
- when a rainfall series has read everything, the queue runs its SPI series (`spi` with that `source_ref`).

The whole 1981 - now history is about 550 months x ~4 requests x 8 s, **several hours** in the background the first
time (each region pushes the frontier on: a month read for one region serves the others from memory for 12 hours).
Afterwards a fetch reads the 45-day overlap: two or three months, under a minute.

**The first load is done (2026-11-17):** the 7 rainfall series hold 1981-01 - 2026-10 (550 months each) and the 7
SPI-3 series 1981-03 - 2026-10 (548 each). 1981-01 - 2017-06 came by CSV upload (`source = manual`) from an offline
reader of the same tiles, regions and weighting (identical on every month both had read), because CHIRPS answered 429
after ~1.3 GB on 2026-11-16; 2017-07 onward by `ChirpsSource` in rounds of ~30 - 35 months between 429s. A backend
restart drops the queued continuations (they live in memory): after one, Fetch the rainfall series again (not Full) and
it continues from its latest stored month.

**Point in time.** The release dates are publication estimates: RONI / ONI by the 7th of the month after the season
(NOAA updated them on 2026-11-03), weekly Nino 3.4 the Monday after the week, JAMSTEC about the 10th, CHIRPS about the
13th - 15th of the next month, NOAA's probabilities on their issue day. Vintages are not kept: ONI and RONI are revised
(the ERSST v6 switch of September 2026 changed the whole history), CHIRPS final replaces nothing (only final monthly
files are read).

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
- one request per host at a time, with **2 s** between two requests to the same host (a longer pause for a host listed
  in `neracalab.crawl.host-delays`: the CHIRPS server, 8 s);
- HTTP range requests (`getRange`) for the cloud-optimized GeoTIFFs of the rainfall;
- a cookie store;
- **no retries and no work-arounds**: a refusal (403, 429, a bot challenge) fails the job with its status, and the
  series can be uploaded as a CSV instead. The one exception is how much is kept: the CHIRPS rainfall, read month by
  month, stores the months read before a 429 and asks again 15 minutes later from the refused month (section 1a) - the
  refused request itself is never repeated at once.

Parsers fail loudly on a layout they do not know.

| Source | How |
|---|---|
| FRED (`FredSource`) | `https://fred.stlouisfed.org/graph/fredgraph.csv?id={id}&cosd={from}`, no API key. Header `observation_date,{ID}`; a day without a value (`.` or empty) is skipped. |
| Yahoo Finance (`YahooMacroSource`) | The chart client of the price ingestion (paced with it); daily closes; today's New York bar is left out. |
| CNBC (`CnbcSource`) | `https://ts-api.cnbc.com/harmony/app/bars/{symbol}/1D/{from}/{to}/adjusted/EST5EDT.json`, **one request per calendar year**: a request over many years leaves days out (2026-09-10 and 2026-09-21 were missing from the 2011 - 2026 answer). The close of each day. Weekend-dated bars are left out: they are sessions dated a day early by the service's US time zone (most of 2015, next to the bar of the right day) or repeats of Friday. Today's (Jakarta) bar is left out. CNBC has no bar in 2014: 3,394 weekdays from September 2011. |
| Bank Indonesia (`BankIndonesiaSource`) | Reads the page, then posts its ASP.NET form back with the **Download** control (a submit button `...$ButtonExport`, or - since October 2026 on both pages - a link `javascript:__doPostBack('...$ButtonExport','')`, posted as `__EVENTTARGET`; before that change ID_CPI_YOY failed with "the page has no Download (ButtonExport) button") and the page's date range (BI-Rate: `TextBoxDateStart / End`, dd/mm/yyyy; inflation: `TextBoxDateFrom / To`, mm/yyyy). The answer is an `.xlsx` with a "Period" column ("23 September 2026", "19 Agustus 2026", "Agustus 2026": Indonesian or English months) and a value column ("5.75 %"). |
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
- **One table per region** (Indonesia, United States, Global and China), then the weather in two tables of its own
  (**Weather - Global**, **Weather - Indonesia**; section 1a), with columns:
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

## 6a. The Macro page (sidebar › Macro › Outlook, permission COMPANIES)

A country's stock index read through its macro data, between Companies and Screening in the sidebar
(`/macro?country=ID&index=IHSG&range=1Y`; the choice is kept in the URL). The sidebar entry is a dropdown: **Outlook**
(this page) and **AI Analysis** (the AI macro analysis, [MACRO_ANALYSIS_DOCS.md](MACRO_ANALYSIS_DOCS.md)). Everything is computed in Java by fixed
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

**Also on the page** (2026-10-21)
- **Commodities and currencies** (between the macro panel and the presets; `macro.outlook.MarketBoard`): every active
  commodity and currency pair of the Forex / commodity price ingestion, grouped into Indonesia's exports (coal, nickel,
  cobalt, palm oil, tin, copper, LNG, rubber, pulp), imports (crude oil, wheat, soybean meal, corn, sugar) and the
  others, with the last price, 1M / 3M / 1Y change, the place in its 52-week range, a 1Y sparkline (weekly closes)
  and what it means for the country: an export up 5 % or more in 3 months supportive (down 5 % a headwind), an import
  the reverse, USD/IDR up 2 % or more a headwind (down 1 % supportive), a stale price (10 days daily, 75 days monthly)
  neutral. The **terms of trade** sum the export and import votes: FAVOURABLE at +2, UNFAVOURABLE at -2.
- **Currencies** (2026-10-23): the nine pairs of the Forex price ingestion in a group of their own (the rest of
  "Havens and references": gold, silver and the reference commodities), each with what a rise means for Indonesia.
  Each pair's tone over 3 months:

  | Pair | Supportive | Headwind | Why |
  |---|---|---|---|
  | USD/IDR | -1 % or more | +2 % or more | the rupiah (as the dollar signal of section 5) |
  | USD/CNY | -2 % or more | +2 % or more | China is Indonesia's largest trading partner; a weaker yuan pulls Asian currencies down |
  | EUR/USD | +2 % or more | -2 % or more | a rise is a weaker dollar (as the DXY of section 5) |
  | AUD/JPY | +5 % or more | -5 % or more | risk appetite; a fall is risk aversion and carry trades unwinding |
  | USD/MYR, USD/PHP, USD/INR, USD/KRW, USD/JPY | - | - | neutral on their own; the peers are read together (below) |

  None of them votes in the terms of trade or in the investability score (the rules version is unchanged).
- **The rupiah against its Asian peers** (`regionalCurrencies`, under the terms of trade): USD/IDR's 3-month change
  beside the median of USD/CNY, USD/MYR, USD/PHP, USD/INR and USD/KRW (at least 3 current, not stale; the median, so one
  extreme peer does not decide). 1.5 points or more weaker than the median: **weaker than the region** - a pressure of
  Indonesia's own (foreign outflows, the fiscal and policy outlook), a headwind. 1.5 points or more firmer: **firmer than
  the region**, supportive. Between them: **in line** - the dollar moves all of Asia (a strong dollar when the median is
  +2 % or more, a weak one at -1 % or less). Fewer than 3 peers or no USD/IDR: unknown, and the summary says which data
  are missing. On 2026-10-23: USD/IDR +0.5 %, the peers' median -1.3 % (USD/KRW -10.4 %): 1.8 points weaker than the
  region.
- **Right column** (sticky on a wide screen): the **AI panel** - the latest AI macro analysis of the chosen index
  (investability, verdict, action, the four agents' stances, the thesis, beside the rules' score) with **Run AI
  analysis** and a link to its report - and **Indexes**: the country's indexes (IHSG, LQ45) with close, 1M / 3M / 1Y and
  the distance to the 200-day average.
- The Soros preset also returns its `loop` (VIRTUOUS, NEUTRAL, VICIOUS) and `loopThen`, the Dalio preset its
  `quadrant` (GOLDILOCKS, REFLATION, STAGFLATION, ...): the AI agents' validator reads them.

On 2026-10-16 (IHSG 5,185, 26 % below its 200-day average of 7,033): liquidity tightening (-3 of 6), risk neutral (-1 of
6), BI hiking with a restrictive real rate, inflation inside the band, a firmer rupiah - **36 / 100, not investable now,
sell / reduce**; the Paul Tudor Jones and Druckenmiller presets AVOID.

**Weather** (2026-11-16, rules version `2026-11-16.1`; `macro.outlook.ClimateBoard`, between the commodities and the
presets; `weather` of the outlook): context with **no vote** - the regimes, the presets' stances and scores and the
investability score are exactly the same with or without it (tested), because the link from the weather to the index is
not backtested.

- **Global**: El Nino / La Nina from RONI (state at +/- 0.5; weak, moderate 1.0, strong 1.5, very strong 2.0; its trend at
  +/- 0.3 against three months before; stale after four months), with ONI, the weekly Nino 3.4 and the 30-day SOI; the
  Indian Ocean Dipole from JAMSTEC's monthly DMI (+/- 0.4; stale after 75 days; it matters from July to November);
  NOAA's forecast (El Nino / La Nina in 3 and 6 months, the change since the previous issue, a move of 15 points or more
  called out as the surprise); the other producers (Malaysia's palm oil, Australia's coal, India's monsoon): the latest
  month in % of its 1991 - 2020 normal, the 3 months together, SPI-3.
- **Indonesia**: the four regions the same way, and its risk: **DROUGHT** (two or more regions at SPI-3 -1 or less),
  **WET** (two or more at +1.5 or more), **DRY_RISK** (El Nino or a positive IOD in the dry season, June - November, or
  NOAA at 60 % or more for El Nino in three months when those months are in the dry season), **WET_RISK** (the same for La
  Nina / a negative IOD), else NORMAL. Tone for the equities: DROUGHT and DRY_RISK a headwind, else neutral.
- **What it means**: plantations (a drought in the palm oil regions, or a strong El Nino in the dry season: supportive
  for CPO prices, a volume risk next year), coal (Australia's basins very wet: supportive for the price; Kalimantan's
  very wet: slower output; Kalimantan in drought: low rivers), food inflation and Bank Indonesia (Java's drought, El Nino
  in the dry season, a weak Indian monsoon from July: a headwind; good rains: supportive), the rupiah, growth and haze (a
  dry August - November on peat).
- **The four investors** (each preset's `climate`, also its last evidence point "Climate (no vote): ..."): Paul Tudor
  Jones - not his timeframe; a headwind only when the drought shows in rising CPI or SBN yields. Druckenmiller - the ENSO
  forecast and Bank Indonesia's room: a drought keeps BI tight (until El Nino fades when NOAA gives it under 50 % in six
  months); El Nino expected to end (30 % or less in six months) is the turn he buys early; La Nina or good rains leave BI
  room. Soros - a drought as the trigger of the bust loop (food inflation, a weaker rupiah, BI defending it): a headwind
  when the rupiah weakens or BI hikes, else a trigger to watch. Dalio - a food supply shock on the inflation axis (toward
  stagflation when growth slows), good rains disinflationary.

On 2026-11-16: strong El Nino (RONI +1.97 in ASO 2026, ONI +2.46), IOD positive (+0.94 in October), NOAA 100 % El Nino in
three months and 38 % in six. The Climate Hazards Center's own SPI-3 grid put Kalimantan's palm oil and coal regions in
extreme drought (about -2.1) and Sumatra in moderate drought to the end of October 2026; until the stored rainfall
reached October 2026 the page read Indonesia as **dry risk**.

On 2026-11-17, with the first load done, the stored SPI-3 agrees and the page reads Indonesia as **DROUGHT** (October
2026): Kalimantan palm oil -2.01 and coal -2.19 (extreme drought; 48 % and 49 % of the month's normal rainfall),
Sumatra palm oil -1.09 (moderate, 67 %), Java rice -0.82 (near normal, 44 % in a dry month). The other producers:
India's monsoon -1.51 (severe drought), Malaysia -0.84 (near normal), Australia's coal basins +1.09 (wet, 287 % of
normal).

| Endpoint (permission COMPANIES) | Returns |
|---|---|
| `GET /api/v1/macro/outlook/countries` | the countries (`available`) and their indexes (`rows`, `lastDate`) |
| `GET /api/v1/macro/outlook?country=ID&index=IHSG&range=1Y` | `country`, `range`, `countries`, `asOfDate`, `index` (with `points` `[epoch s, close]`), `regimes` (`stateThen`, `scoreThen`), `panels` (`points`, `lastValue`, `tone`, `summary`, `readBy`), `presets` (`ptj`, `druckenmiller`, `soros` with `loop`, `dalio` with `quadrant`), `indexes` (the country's indexes), `markets` (`commodities` and `forex`: rows with `role`, `tone`, `summary`, `points`; `termsOfTrade`; `regionalCurrencies`: `state`, `tone`, `rupiah3mPct`, `peersMedian3mPct`, `gapPts`, `peers`, `summary`), `conclusion` (`score`, `verdict`, `action`, `components`, `buyWhen` / `accumulateWhen` / `sellWhen` with `met`, `watch`), `notes`, `disclaimer`. 400: a country not available yet, an unknown country, index or range |

**Cache (Redis).** Computing the outlook reads every index bar, macro series, commodity and currency pair (about 0.5 s;
the MAX range is 1.3 MB of JSON). `MacroOutlookCache` keeps the JSON in Redis by country, index, range and day (the
exchange's; the outlook is as of today); a cached request takes about 10 - 30 ms. Header `X-Cache: HIT` or `MISS`.

- Cleared by every write that changes rows of `index_daily`, `instrument_daily`, `macro_series` or `fx_rate_daily`
  (the repositories publish `market.MarketDataChanged`; the cache is cleared after the commit, not by a rolled-back
  write) and by every backend start (the SQL scripts reload the catalogs). Clearing counts up a Redis generation
  (`neracalab:macro-outlook:generation`) that is part of every key, so an outlook computed while the data changed is
  never read again.
- `neracalab.macro.outlook-cache.ttl` (default `1h`) bounds a change made outside the application (SQL by hand);
  `enabled` (`MACRO_OUTLOOK_CACHE_ENABLED`, default `true`) turns it off.
- Redis unreachable: the outlook is computed for every request (logged as a warning); the page keeps working.
- Tests: `MacroOutlookCacheTest` (a hit equal to the computed outlook, cleared after a commit only, refusals not
  cached).

## 6b. Macro › Data (sidebar › Macro › Data, permission COMPANIES)

`/macro/data?series=US_10Y&range=5Y`: every series of Ingestion › Macro (section 1) as one large chart, chosen from a
dropdown grouped by country (Indonesia, United States, Global, China) and the weather apart (**Weather - Global**,
**Weather - Indonesia**; a series without data is disabled), with the Outlook's ranges (6M, 1Y, 3Y, 5Y, 10Y, MAX). Read
only: fetches and uploads stay on Ingestion › Macro (INGESTION).

- **Weather**: an anomaly (degC), the SOI and the SPI are RATE (changes in points, degC for an anomaly) and drawn with
  their thresholds (El Nino / La Nina +/- 0.5, the IOD +/- 0.4, the SOI +/- 7, drought -1 and very wet +1.5) instead of
  the average. A rainfall series (unit mm) also returns `normals` (`MacroSeriesChart.normals`: the 1991 - 2020 mean of
  each calendar month, the latest month and the last 3 months in % of normal): the chart draws the normal of each month
  as a dashed line, and the statistics add both percentages.

- **Window.** A range ends at the series' **latest observation**, not today, so a series published late (a monthly
  figure, a manual CSV such as the CDS) still shows its range; the page names that date and the series' state (up to
  date, behind, manual ...). An unknown `series` in the URL shows the first series with data.
- **Kind** (`MacroSeriesChart.kind`): **RATE** (unit `%`, `bps`, `points`: changes in points), **LEVEL** (an index or
  amount: changes in percent and units), **FLOW** (`ID_FOREIGN_NET_BUY`, one session's net buying: a period is the
  **sum** of its observations, drawn as green / red bars; its window leaves out the first day, so the net over 1Y equals
  the 1Y change).
- **Chart**: 460 px, grid, axes, a tooltip with the publication date, the range's average as a dashed line, a step line
  for the BI Rate (set at board meetings), a brush to zoom when there are more than 60 points. More than 2,000
  observations in the window are **drawn weekly** (the last of each week; a flow: the week's sum); the statistics always
  read every observation.
- **Changes** to the latest observation: 1M, 3M, 6M, YTD, 1Y, 3Y, 5Y (from the last observation on or before the start; a
  period the series does not reach back to is left out). **In this range**: observations, high and low with their
  dates, average, change (a flow: largest inflow / outflow, average, net). **About the series**: description, source
  with its link, frequency, unit, stored rows and dates, last publication, the presets that read it. **Latest
  observations**: the 12 newest with their change and publication date.

| Endpoint (permission COMPANIES) | Returns |
|---|---|
| `GET /api/v1/macro/data/series` | `today` and `series`: code, name, country, category, unit, frequency, source label and URL, description, `usedBy`, rows, first / last date, last value, last release, `state`, `kind` (no job messages) |
| `GET /api/v1/macro/data/series/{code}?range=1Y` | `series` (as above) and `chart`: `range`, `kind`, `from` (null: MAX), `to`, `sampled` (`WEEKLY` or null), `points` (`date`, `value`, `released`), `stats` (`observations`, `first`, `last`, `high`, `low`, `average`, `change`, `changePct` for a level), `changes` (`period`, `from`, `change`, `pct`), `recent` (12, newest first). 404 unknown series, 400 unknown range |

Code: `macro.outlook.MacroDataController`, `MacroSeriesChart` (pure); frontend `app/(dashboard)/macro/data/page.tsx`,
`components/macro/macro-data-client.tsx`, route handlers `/api/macro/data/series` and `/api/macro/data/series/[code]`.

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
- **Weather**: the regions are boxes, not province polygons (a few border pixels); daily rainfall, fire hotspots (NASA
  FIRMS) and the MJO are not ingested (instruction md section 12.4 lists them); NOAA's forecast history starts in April
  2026 (its archive); the weather casts no vote in the score until a backtest supports one.
- **SBN 10Y**: CNBC has no 2014 and starts in September 2011, so a change over a window that falls in 2014 is null.

## 8. Tests

| Test | Covers |
|---|---|
| `MacroSourceParsersTest` | FRED CSV, CNBC bars, CFTC rows, the BI form (both date-field namings) and its `.xlsx` export, a SEKI `.xls` (years across columns, a total column, a wrong year label failing), Indonesian months. |
| `MacroSeriesTest` | Period end, release date, "up to date" for every frequency. |
| `MacroSeriesChartTest` | Macro › Data: the kind by unit and flow series, the window ending at the latest observation, level changes in percent and rate changes in points, a flow's sums (net over the range = the period's change), weekly drawing above 2,000 observations with the statistics in full, the latest observations, an empty series and an unknown range. |
| `MacroDataControllerTest` | Against the stored series: every active series in catalog order with its kind and without job messages, US_10Y over 1Y (window, points, statistics), the net foreign flow's whole history drawn weekly, 404 / 400. |
| `MacroIngestionServiceTest` | Overlap window, the first full run, revisions, out of range / twice / future storing nothing, warnings, a manual series not fetched. |
| `MacroSignalsTest` | A tightening, risk-off set of series; point in time (September CPI unknown on 14 October); stale series; the BI rate held between decisions; the real rate. |
| `MacroControllerTest` | The catalog and states, the history, a manual series refused, the CSV upload checked then stored (release date by the lag, a MACRO job). |
| `IngestionJobTypeConstraintTest` | Every script that re-creates the job-type check lists `MACRO`. |
| `MacroOutlookTest` | The Macro page's rules on synthetic data: tight money, risk-off and a downtrend not investable (SELL, all four presets AVOID, the panels' tones); easy money, risk-on and an uptrend investable (BUY); good macro below the 200-day average accumulated, not bought; the derived series (curve, net liquidity, CPI YoY) and point in time; missing data noted, not guessed; Soros's loop (-3 of 4 a bust, +4 of 5 a boom); Dalio's Goldilocks bought, stagflation never. |
| `MacroOutlookControllerTest` | The countries (Indonesia with IHSG and LQ45; the US and China later), the outlook's sections on the stored data, USD/IDR = the latest Forex close (date, value, source), the refusals (400), a login required. |
| `IndexIngestionServiceTest` | LQ45 ingested with IHSG from its own latest day, its failure not failing IHSG; up to date only when both are. |
| `MacroIngestionQueueTest` | A fetch that stopped early continued from where it stopped, then the rainfall's SPI queued; continuations that read nothing stopped after `MAX_IDLE_CONTINUATIONS` (6). |
| `MacroWeatherSourcesTest` | The weather sources on their real layouts: RONI / ONI seasons dated on their last month (DJF, NDJ across the year), the weekly Nino 3.4 with glued numbers, NOAA's probability table (current and archived ids, row order checked, the second-Thursday issue day), JAMSTEC's observed DMI, Long Paddock's monthly SOI and 30-day mean (a gap leaves a window out); TIFF LZW against an encoder through full tables; a CHIRPS-like GeoTIFF read over byte ranges (land mean, sea left out), a month not published yet ending a fetch and one month serving every region from memory, a missing old month failing; a long history read in chunks of 60 months (cached months not counted) and an HTTP 429 keeping the months read before it, with where and when to continue; a slow host's own pause; the SPI against scipy's gamma and normal (1e-6), its base period and windows, the SPI source refusing an incomplete rainfall history. |
| `ClimateBoardTest` | A strong El Nino with a positive IOD and a Kalimantan / Sumatra drought: the global and Indonesian parts apart, % of normal and SPI states, the sectors' tones; point in time (ASO unknown on 5 November, NOAA's October issue read before the November one); a stale IOD unknown; a forecast raising a dry risk only for the dry season; no data said plainly; every preset's climate reading with the stances, scores and investability unchanged by the weather; Druckenmiller's turn when El Nino is expected to end; the rainfall normals and kinds of Macro › Data. |
