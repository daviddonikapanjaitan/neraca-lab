# Neraca Lab - Daily Price Ingestion (v1)

Loads daily prices (`price_daily`) from a market data provider and recalculates the company's
valuation (`market_snapshot`, `valuation_snapshot`, VALUATION rows of `financial_metric`).
Default provider: the Yahoo Finance chart API (free, unofficial); fallback: EODHD (paid API key).
The same queue also ingests the IHSG index and the **Forex pairs and commodities** (section 1b).
Code for these: `backend/src/main/java/com/neracalab/backend/market/`.

Code: `backend/src/main/java/com/neracalab/backend/price/`

| Class                      | Role                                                                                                     |
|----------------------------|----------------------------------------------------------------------------------------------------------|
| `PriceIngestionController` | `/api/v1/prices/ingestions` endpoints and error responses (ProblemDetail)                                |
| `PriceUpdateController`    | `/api/v1/prices/status`, `/updates` (every company of an exchange to today), `/index-ingestions` (IHSG)    |
| `PriceUpdateService`       | status of an exchange against the last completed trading day; queues the companies behind (and the index) |
| `IndexIngestion`           | the exchange's index (IHSG) in the price queue; implemented by `technical.IndexIngestionService` (`index_daily`) |
| `PriceIngestionQueue`      | in-memory job queue with 5 worker threads (`neracalab.jobs.workers`); HTTP 429 back-off; one active job per company (per index) |
| `PriceIngestionJob`        | job state and its JSON view                                                                              |
| `PriceIngestionService`    | one ingestion: date range, provider request, cleaning, re-adjustment check, write + valuation refresh   |
| `PriceDailyRepository`     | company lookup, latest stored day, `price_daily` upsert                                                  |
| `ValuationRepository`      | company-scoped recalculation with the formulas of `V1.0.6__data_metrics_valuation.sql`                   |
| `PriceIngestionSchedule`   | optional evening run (off by default): `PriceUpdateService.updateAll` - the companies behind and IHSG   |
| `PricesAfterUpload`        | queues the prices of a company whose financial statement filing was just stored                          |
| `PriceProperties`          | configuration `neracalab.prices.*`                                                                       |
| `provider/PriceProvider`   | provider interface; `YahooPriceProvider` / `EodhdPriceProvider`, one active by configuration             |
| `provider/PacedHttpClient` | the one HTTP client of the providers: cookie store, browser User-Agent, one request at a time, pause     |
| `InstrumentIngestion`      | a Forex pair / commodity in the price queue; implemented by `market.InstrumentIngestionService` (`instrument_daily`) |
| `market.MarketController`, `MarketService` | `/api/v1/markets`: the markets with their instruments, one instrument, "Update all" of a market |
| `market.BarCleaner`        | drops the bad prints of the Yahoo currency / futures history (a close that jumps away and straight back) |
| `market.WorldBankPinkSheetClient` | the monthly coal price (World Bank Pink Sheet workbook)                                          |

## 1. Endpoints

Need the `INGESTION` permission. Every API needs a login: `AUTH="Authorization: Bearer <token>"` from `POST /api/v1/auth/login`
([AUTH_DOCS.md](AUTH_DOCS.md), section 3).

```bash
curl -H "$AUTH" -X POST "http://localhost:8080/api/v1/prices/ingestions?exchange=IDX&ticker=HRTA"   # queue a job
curl -H "$AUTH"  http://localhost:8080/api/v1/prices/ingestions/{id}                               # one job
curl -H "$AUTH"  http://localhost:8080/api/v1/prices/ingestions                                    # provider, queue, recent jobs
```

| Request                                                                 | Response                                                                                                    |
|-------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------|
| `POST /api/v1/prices/ingestions?exchange={code}&ticker={code}[&full=true]` | **202** new job (`Location: /api/v1/prices/ingestions/{id}`); **200** the job already queued / running for the company |
| `GET /api/v1/prices/ingestions/{id}`                                    | the job; 404 when unknown (or forgotten after a restart / beyond `job-history`)                             |
| `GET /api/v1/prices/ingestions`                                         | `provider`, `pending` (queued jobs, without the running one), `jobs` (most recent first)                    |

`exchange` and `ticker` are required and case-insensitive (`idx` / ` hrta` work). Errors as in the
company APIs: 400 unsupported exchange (with `supportedExchanges`) or invalid ticker, 400 missing
parameter, 404 no company with this ticker on this exchange. The company must exist (it is created
by the financial statement upload or a seed script); the ingestion never creates companies.

The POST only queues: the provider is called by the background worker, never inside a web request,
and the frontend reads prices from the database only.

**After a financial statement upload** the same job is queued without a request: when a filing was stored
(`SUCCEEDED` or `INCOMPLETE`), `PricesAfterUpload` submits `full=false` for its company, recorded for the
user who uploaded the file - unless the stored prices already reach the last completed trading day
(`PriceIngestionService.upToDate`) or the company already has an active price job. A company uploaded for
the first time therefore gets its whole price history, its market caps and its valuations by itself
(`neracalab.ingestion.prices-after-upload`, default `true`;
[AI_INGESTION_DOCS.md](AI_INGESTION_DOCS.md) section 4).

### Every company of an exchange up to today, and the IHSG index

```bash
curl -H "$AUTH" "http://localhost:8080/api/v1/prices/status?exchange=IDX"                    # how far the prices reach
curl -H "$AUTH" -X POST "http://localhost:8080/api/v1/prices/updates?exchange=IDX&index=true"  # every company behind + IHSG
curl -H "$AUTH" -X POST "http://localhost:8080/api/v1/prices/index-ingestions?exchange=IDX"    # IHSG alone (&full=true: whole history)
```

| Request | Response |
|---|---|
| `GET /api/v1/prices/status?exchange=IDX` | `lastCompletedTradingDay` (today from 17:00 WIB, else the trading day before; weekends step back to Friday), `companies` (active), `upToDate`, `behind`, `withoutPrices`, `oldestLatestDate`, `newestLatestDate`, `index` (`code`, `symbol`, `rows`, `firstDate`, `lastDate`, `upToDate`), `provider`, `pending` |
| `POST /api/v1/prices/updates?exchange=IDX[&index=true]` | **202**: `queued`, `alreadyActive` (their job is kept), `upToDate` (skipped), `withoutPrices` (of the queued), `index` (`outcome` QUEUED / ALREADY_ACTIVE / UP_TO_DATE), `firstJob` |
| `POST /api/v1/prices/index-ingestions?exchange=IDX[&full=true]` | **202** new job, **200** the job already queued / running (`ticker` = IHSG, `index` = true); 404 when the exchange has no index |

The index job also stores the exchange's other indexes (`neracalab.technical.other-indexes`: LQ45 = Yahoo Finance
`^JKLQ45` for the Macro page), each from its own latest stored day; a failure of one of them is logged and does not fail
the job. The index counts as up to date (`index.upToDate`, the `UP_TO_DATE` outcome) only when IHSG and LQ45 both reach
the last completed trading day, so a missing LQ45 queues the job. `index.indexes` lists every index of the job (`code`,
`symbol`, `name`, `rows`, `firstDate`, `lastDate`, `upToDate` each); Ingestion › Price Ingestion shows both in
"Update all companies to today" ("Also update the IHSG and LQ45 indexes") and in the index card.

"Update all companies" (Ingestion › Price Ingestion, right-hand card) queues every active company whose latest stored
day is before the last completed trading day as an ordinary `full=false` job: only the missing days are fetched, from
the company's latest stored day (re-fetched as the overlap check) to that day - ANTM stored up to 9 Oct is fetched from
9 Oct to 15 Oct. A company without prices gets its whole history; a company already up to date is not queued. The jobs
run in the same queue (5 workers, one paced request at a time) and appear one by one in the jobs table. Exchanges other
than IDX are added as `Exchange` constants; the card lists them.

The **IHSG ingestion** (`IndexIngestion`, Yahoo Finance `^JKSE` into `index_daily`) runs in the same queue as an index
job: from the latest stored day to the last completed trading day (no request when that day is stored; today's bar
only after the session close cutoff), the whole history (from 1990) the first time or with `full=true`. It is recorded
in `ingestion_job` as a `PRICE` job with ticker `IHSG` ("Stored 1 new and 0 updated index day, latest ..."). The
technical refresh uses the same ingestion.

Measured on 2026-10-15 at 20:08 WIB: 80 IDX companies, 6 up to date, 74 behind (the oldest from 5 Oct) - 74 jobs queued,
all `SUCCEEDED` within about two minutes (ANTM: 4 new days, BBCA 4, HRTA 2), every company stored up to 15 Oct; IHSG
already up to date (8,891 days), its job "Already up to date".

### 1b. Forex and Commodities

The dropdown of Price Ingestion lists the exchanges, then **Forex** and **Commodities**. With a market:
- the second dropdown becomes **Currency** or **Commodity**;
- the right-hand card becomes "Update all currencies / commodities to today".

The instruments are the catalog `market_instrument`, seeded by `V1.0.26__data_markets_macro.sql`. Their bars are
stored in `instrument_daily`, in the quote currency and the unit of each instrument.

| Market | Code | Instrument | Source | History | Tick | Bad-print threshold |
|---|---|---|---|---|---|---|
| Forex | `USDIDR` | USD/IDR (IDR per USD) | Yahoo Finance `IDR=X` (London close) | 2001- | 1 | 3 % |
| Forex | `USDJPY` | USD/JPY (JPY per USD) | Yahoo Finance `JPY=X` | 1996- | 0.001 | 5 % |
| Forex | `USDCNY` | USD/CNY (CNY per USD, onshore yuan) | Yahoo Finance `CNY=X` | 2001- | 0.0001 | 2 % |
| Forex | `USDMYR` | USD/MYR (MYR per USD) | Yahoo Finance `MYR=X` | Dec 2003- | 0.0001 | 4 % |
| Forex | `USDPHP` | USD/PHP (PHP per USD) | Yahoo Finance `PHP=X` | Dec 2003- | 0.001 | 2.7 % |
| Forex | `USDINR` | USD/INR (INR per USD) | Yahoo Finance `INR=X` | Dec 2003- | 0.001 | 3.5 % |
| Forex | `USDKRW` | USD/KRW (KRW per USD) | Yahoo Finance `KRW=X` | Dec 2003- | 0.01 | 6 % |
| Forex | `EURUSD` | EUR/USD (USD per EUR; a rise is a weaker dollar) | Yahoo Finance `EURUSD=X` | Dec 2003- | 0.0001 | 4 % |
| Forex | `AUDJPY` | AUD/JPY (JPY per AUD; risk appetite, carry trades) | Yahoo Finance `AUDJPY=X` | Dec 2003- | 0.001 | 10 % |
| Commodities | `BRENT` | Crude Oil (Brent), USD per barrel | Yahoo Finance `BZ=F` (front month) | 2007- | 0.01 | 15 % |
| Commodities | `WTI` | Crude Oil (WTI), USD per barrel | Yahoo Finance `CL=F` (front month) | 2000- | 0.01 | 30 % |
| Commodities | `NATURAL_GAS` | Natural Gas (Henry Hub), USD per MMBtu | Yahoo Finance `NG=F` | 2000- | 0.001 | 40 % |
| Commodities | `LNG` | LNG (Japan), USD per MMBtu | **World Bank Pink Sheet**, "Liquefied natural gas, Japan", **monthly** | 1980- | 0.01 | - |
| Commodities | `COAL` | Coal (Newcastle), USD per metric ton | **World Bank Pink Sheet**, "Coal, Australian", **monthly** | 1979- | 0.01 | - |
| Commodities | `GOLD` | Gold, USD per troy ounce | Yahoo Finance `GC=F` | 2000- | 0.1 | 8 % |
| Commodities | `SILVER` | Silver, USD per troy ounce | Yahoo Finance `SI=F` | 2000- | 0.005 | 15 % |
| Commodities | `COPPER` | Copper, USD per pound | Yahoo Finance `HG=F` | 2000- | 0.0005 | 25 % |
| Commodities | `NICKEL` | Nickel, USD per metric ton | **World Bank Pink Sheet**, "Nickel" (LME), **monthly** | 1980- | 0.01 | - |
| Commodities | `ALUMINIUM` | Aluminium, USD per metric ton | Yahoo Finance `ALI=F` (COMEX; September 2016 missing) | 2014- | 0.25 | 10 % |
| Commodities | `IRON_ORE` | Iron Ore (62% Fe, CFR China), USD per dry metric ton | Yahoo Finance `TIO=F` | 2010- | 0.01 | 15 % |
| Commodities | `STEEL_HRC` | Steel (US Midwest hot-rolled coil), USD per short ton | Yahoo Finance `HRC=F` | 2008- | 1 | 15 % |
| Commodities | `TIN` | Tin, USD per metric ton | **World Bank Pink Sheet**, "Tin" (LME), **monthly** | 1980- | 0.01 | - |
| Commodities | `COBALT` | Cobalt, USD per metric ton | **IMF Primary Commodity Prices**, `PCOBA` (LME spot, 99.80 %), **monthly** | 1992- | 0.01 | - |
| Commodities | `PALM_OIL` | Palm Oil (USD), USD per metric ton | Yahoo Finance `CPO=F` (CME USD Malaysian crude palm oil) | 2010- | 0.25 | 10 % |
| Commodities | `PALM_OIL_MYR` | Palm Oil (MYR), MYR per metric ton | Yahoo Finance `CPO=F` x `MYR=X` (an approximation of Bursa Malaysia FCPO) | 2010- | 1 | 10 % |
| Commodities | `SOYBEAN_OIL` | Soybean Oil, US cents per pound | Yahoo Finance `ZL=F` | 2000- | 0.01 | 10 % |
| Commodities | `SOYBEAN_MEAL` | Soybean Meal, USD per short ton | Yahoo Finance `ZM=F` | 2000- | 0.1 | 15 % |
| Commodities | `CORN` | Corn, US cents per bushel | Yahoo Finance `ZC=F` | 2000- | 0.25 | 12 % |
| Commodities | `WHEAT` | Wheat (Chicago SRW), US cents per bushel | Yahoo Finance `ZW=F` | 2000- | 0.25 | 15 % |
| Commodities | `SUGAR` | Sugar #11, US cents per pound | Yahoo Finance `SB=F` | 2000- | 0.01 | 18 % |
| Commodities | `RUBBER` | Rubber (TSR 20), USD per kg | **World Bank Pink Sheet**, "Rubber, TSR20", **monthly** | 1999- | 0.01 | - |
| Commodities | `PULP` | Wood Pulp (US PPI), index 1982 = 100 | **FRED** `WPU0911` (BLS producer price index), **monthly** | 1990- | 0.001 | - |
| Commodities | `UREA` | Urea, USD per metric ton | **World Bank Pink Sheet**, "Urea", **monthly** | 1980- | 0.01 | - |
| Commodities | `DAP` | DAP (diammonium phosphate), USD per metric ton | **World Bank Pink Sheet**, "DAP", **monthly** | 1980- | 0.01 | - |
| Commodities | `POTASH` | Potash (potassium chloride), USD per metric ton | **World Bank Pink Sheet**, "Potassium chloride", **monthly** | 1980- | 0.01 | - |

**Units.** Corn, wheat, sugar and soybean oil are stored in US cents, as the exchanges and Yahoo Finance (currency
`USX`) quote them; the unit says so ("US cents per bushel"). The page shows every commodity as value and unit.

**Bad-print thresholds** were set by running `BarCleaner` over each whole history (2026-10-17): none touches a real day.
Natural gas keeps 27 Jan 2022 (the February contract's expiry squeeze, 6.265 between 4.277 and 4.639), sugar 28 Apr
2000 (the May expiry, 5.16 between 6.18 and 6.53), copper the 4.49 low of 30 Jul 2025 (the US tariff exemption), wheat
the 1,065 - 1,334.5 range of 27 Feb 2008. The silver squeeze of 1 Feb 2021 and wheat on 5 Aug 2010 (Russia's export
ban) and 25 Feb 2022 (the invasion of Ukraine) are below the thresholds.

**Currency pairs added on 2026-10-23** (USD/CNY, USD/MYR, USD/PHP, USD/INR, USD/KRW, EUR/USD, AUD/JPY): the rupiah's
Asian peers, the dollar and risk appetite, read for Indonesia on the Macro page and in the AI macro analysis
([MACRO_DOCS.md](MACRO_DOCS.md) section 6a). Their thresholds were set the same way, on each whole Yahoo history: drop
the prints that jump away and come straight back, never a real day.
- **USD/CNY 2 %** (a managed currency): 5.863 on 2011-07-18 between 6.43 and 6.47, 7.1587 on 2023-10-30 inside 7.31.
- **USD/INR 3.5 %:** the stale 52.493 of 2012-01-27 and -31. At 2 % the cleaner would drop the real 49.388 between
  them instead; 85.194 of 2023-11-02 (2.3 % off) cannot be dropped without that error and stays.
- **USD/PHP 2.7 %:** 2016-11-17 (43.703 inside 49), 2017-03-06, 2021-12-13, 2025-06-13; 2012-01-30's 42.619 between
  two stale 43.714 is kept.
- **USD/MYR 4 %:** four prints of 2006 - 2007 (e.g. 3.4609 on 2006-06-05 inside 3.63).
- **USD/KRW 6 %:** none. The real 1,160 of 2008-09-16 (Lehman week) comes back 5.6 % the next day; at 4.5 % it would
  be dropped, so the 2005 prints near 1,000 inside 1,045 stay.
- **EUR/USD 4 %:** five prints of 2008 (1.5571 twice, 1.505, 1.4957, 1.4918 between 1.2717 and 1.2926); the real
  1.2933 of 2009-02-20 is kept.
- **AUD/JPY 10 %:** none. Its October 2008 carry-trade crash (58.4 on the 24th) and the rally of 13 - 14 October are
  real and move 9 - 13 % a day.

**Palm oil in ringgit.** Bursa Malaysia's crude palm oil future (FCPO) has no free source a program may read: Yahoo
Finance does not serve it, MPOB's price data are a paid subscription. `PALM_OIL_MYR` is therefore `CPO=F` (the CME
USD Malaysian crude palm oil future, cash-settled on Bursa Malaysia's FCPO) times USD/MYR (`MYR=X`) of the same day - the
last rate up to 5 days before on a Malaysian holiday; a day without one is not stored. It follows FCPO closely (2022 peak
RM 6,913, 2024 average RM 4,110) but is not Bursa's own settlement price.

**Monthly series.** Coal, LNG, nickel, tin, rubber, urea, DAP and potash share one workbook: it is downloaded once and
kept for 10 minutes, so "Update all commodities" fetches it once.
- **Cobalt** is in neither the Pink Sheet nor Yahoo Finance (`COB=F` has one day) nor FRED. It comes from the IMF Primary
  Commodity Price System through its SDMX API, no key: `https://api.imf.org/external/sdmx/3.0/data/dataflow/IMF.RES/PCPS/+/G001.PCOBA.USD.M`
  (world, cobalt, US dollars, monthly; `ImfPcpsClient`, `neracalab.markets.imf.base-url`).
- **Wood pulp** has no free price: the IMF's wood pulp series (`PWPULP`) is empty, the Pink Sheet has none, and the
  market prices (FOEX, Fastmarkets) are paid. `PULP` is therefore the US producer price index of wood pulp (BLS, FRED
  `WPU0911`, 1982 = 100): it follows pulp prices, but it is an index, and the page shows its unit as such.
- **Bauxite** is not included: no exchange trades it, neither the Pink Sheet nor the IMF has a bauxite price, and FRED's
  producer price index of bauxite (`PCU2122992122992`) stopped in 1990.

**WTI** settled at -37.63 on 20 April 2020. A stored close must be positive, so that day is not stored, and the cleaner
cuts the negative open and low of 21 April back to the close; 30 % keeps every other real day of the history (18 March
2020: 20.37 between 26.95 and 25.22).

**Why coal is monthly.** Yahoo Finance has no current coal future: `MTF=F` (API2 Rotterdam) stopped updating in 2025,
and the Newcastle future is not served. A daily coal price has no free source a program may read (investing.com and
stooq answer with a bot challenge). The World Bank's monthly average of Australian (Newcastle) thermal coal is official
and free:
- **Source:** the workbook `CMO-Historical-Data-Monthly.xlsx`, sheet "Monthly Prices". Its address changes every month,
  so it is taken from the link on the Commodity Markets page.
- **Storage:** one bar per month, dated on the 1st, with open = high = low = close = the average.
- **Update:** up to the month before the current one.

**One instrument** (`InstrumentIngestionService`) is a `PRICE` job like a company. Its exchange column holds the market,
its ticker the code:
- **Daily instruments:** the fetch runs from 14 days before the latest stored day (the cleaning needs neighbours) to the
  **last completed day**: the weekday before today in the market's time zone (London for currencies, New York for the
  futures). A currency or future trades around the clock, so today's bar is still forming.
- **No request** when that day is stored already.
- **First run, or `full=true`:** the whole history from the instrument's `history_from`.
- **Stage text:** "Stored 2 new and 0 updated price days, latest 2026-10-14" ("months" for a monthly series).

**Bad prints** (`BarCleaner`, threshold `max_spike`). The Yahoo history of USD/IDR has impossible prints: 888.11
between 8,923 and 8,906 on 2010-11-01, a stale 9,612.45 on nine days of 2013, 15,069 on 2024-12-26. The cleaning:
- **Spikes:** drops a run of one to three closes that all lie more than the threshold away from the close before and
  after the run, on the same side, while those two agree within half of it. The price jumped and came straight back.
- **Real moves are kept:** a real move does not come back; the 2008 crash moves of USD/JPY are larger than 5 % and stay.
- **Wicks:** a high or low further than the threshold from the open and close is cut back to them.
- **Stored bad prints:** a dropped day that was stored is deleted.
- **Measured on the full histories (2026-10-16):**
  - USD/IDR: 23 bad prints dropped; its largest remaining daily move is 9.8 %, the real rally of 26 July 2001.
  - USD/JPY: 3 dropped.
  - Brent: 2 wicks cut.

**Requests**
```bash
curl -H "$AUTH" "http://localhost:8080/api/v1/markets"                                          # markets and instruments
curl -H "$AUTH" -X POST "http://localhost:8080/api/v1/markets/FOREX/ingestions?code=USDIDR"      # one instrument (&full=true)
curl -H "$AUTH" -X POST "http://localhost:8080/api/v1/markets/COMMODITY/updates"                # every commodity behind
```

| Request | Response |
|---|---|
| `GET /api/v1/markets`, `GET /api/v1/markets/{FOREX\|COMMODITY}` | `market`, `name`, `itemLabel` (Currency / Commodity), counts `upToDate` / `behind` / `withoutPrices`, `items` (`code`, `name`, `symbol`, `source`, `unit`, `quoteCurrency`, `frequency`, `rows`, `firstDate`, `lastDate`, `lastCompleted`, `upToDate`) |
| `POST /api/v1/markets/{market}/ingestions?code=&full=` | **202** new job, **200** job already active (`exchange` = the market, `ticker` = the code, `instrument` = true); 404 unknown instrument, 400 unknown market |
| `POST /api/v1/markets/{market}/updates` | **202**: `queued`, `alreadyActive`, `upToDate`, `withoutPrices`, `firstJob` |

**Measured on 2026-10-16 (first full load):**
- USD/IDR: 6,365 days.
- USD/JPY: 7,765 days.
- Brent: 4,782 days.
- Gold: 6,556 days.
- Palm oil: 3,965 days.
- Coal: 573 months (1979-01 .. 2026-09).

All jobs `SUCCEEDED` within a minute. Companies › Technicals charts every instrument (`TECHNICAL_DOCS.md`, section 9).

**The pairs of 2026-10-23, first full load** (latest day 2026-10-22, all seven `SUCCEEDED` within 11 seconds; days stored,
bad prints dropped): USD/CNY 6,340 (2), USD/MYR 5,956 (4), USD/PHP 5,929 (4), USD/INR 5,935 (2), USD/KRW 5,938 (0),
EUR/USD 5,935 (5), AUD/JPY 5,959 (0) - exactly the drops of the calibration above.
The USD/IDR pair is also the `USD_IDR` series of the macro view (`MACRO_DOCS.md`).

Every state change of a job is also recorded in the `ingestion_job` table (type `PRICE`,
`PriceIngestionTracker`), so `GET /api/v1/ingestions` lists price jobs next to the uploads and keeps
them after a restart ([INGESTION_JOBS_DOCS.md](INGESTION_JOBS_DOCS.md)). The endpoints above still
read the in-memory queue.

### Job

```json
{
  "id": "49eafc30-35df-4852-bf56-155ce7e737f5", "exchange": "IDX", "ticker": "HRTA", "full": false,
  "status": "SUCCEEDED", "requestedAt": "2026-10-02T20:09:38.826Z", "startedAt": "2026-10-02T20:09:38.826Z",
  "finishedAt": "2026-10-02T20:09:40.044Z", "resumeAt": null, "attempts": 1, "message": null,
  "requestedBy": { "userId": 1, "username": "admin" },
  "result": {
    "provider": "yahoo", "requests": 1, "requestedFrom": "2026-09-30", "requestedTo": "2026-10-03",
    "fullHistory": false, "reAdjusted": false, "barsReceived": 3, "barsSkipped": 0,
    "inserted": 2, "updated": 0, "unchanged": 1, "latestTradingDate": "2026-10-02",
    "valuation": { "marketSnapshots": 2, "valuationSnapshots": 1, "valuationMetrics": 11,
                   "staleValuationMetricsDeleted": 0 }
  }
}
```

| Field        | Meaning                                                                                                   |
|--------------|-----------------------------------------------------------------------------------------------------------|
| `status`     | `QUEUED`, `RUNNING`, `WAITING_RATE_LIMIT` (queue paused until `resumeAt`), `SUCCEEDED`, `FAILED`           |
| `attempts`   | runs of the job; more than 1 after rate-limit waits                                                       |
| `message`    | why the job failed or is waiting                                                                          |
| `requestedBy`| user who requested the prices (`ingestion_job.created_by`); `null` for a scheduled run                    |
| `result`     | outcome of a `SUCCEEDED` job (below)                                                                      |

| Result field        | Meaning                                                                                        |
|---------------------|------------------------------------------------------------------------------------------------|
| `requests`          | provider requests: 0 = already up to date, 1 = normal, 2 = history re-fetched                  |
| `requestedFrom/To`  | date range of the last request (`null` without request)                                        |
| `fullHistory`       | the whole history was fetched (first ingestion, `full=true` or re-adjusted history)            |
| `reAdjusted`        | the overlap check found re-adjusted prices (section 2, step 3)                                 |
| `barsReceived`      | bars in the provider response used                                                             |
| `barsSkipped`       | bars dropped by the cleaning rules                                                             |
| `inserted` / `updated` / `unchanged` | `price_daily` rows new / changed / equal to the stored row                      |
| `latestTradingDate` | `MAX(trading_date)` after the ingestion                                                        |
| `valuation`         | rows written by the valuation refresh (unchanged rows count 0)                                 |

### `full=false` (default) and `full=true`

|                      | `full=false`                                                  | `full=true`                                  |
|----------------------|---------------------------------------------------------------|----------------------------------------------|
| Fetches from         | the latest stored trading day (`MAX(trading_date)`)           | `full-history-from` (1990-01-01); the provider returns data from the listing date |
| Already up to date   | no request                                                    | always one request                           |
| Rows written         | the new days (and the overlap day when the provider changed it) | every day of the history, unchanged rows are not rewritten |

`full=false` switches to a full fetch by itself for the first ingestion of a company (no prices
stored) and when the provider re-adjusted its history. Use `full=true` to repair gaps or wrong
values in older days, or after switching the provider. Both send one request per ticker.

## 2. One ingestion

1. **Range.** From the company's latest stored trading day (re-fetched as an overlap check) to today
   (exchange time zone, `Exchange.zone()`, Asia/Jakarta for IDX); the full history when nothing is
   stored or `full=true`. When the last completed trading day is already stored, no request is made.
   The last completed trading day is today from `session-close-cutoff` (17:00 exchange time; IDX
   closes 16:00, post-closing ends 16:15), otherwise the previous day; weekends step back to Friday.
2. **One request, then cleaning.** Dropped:
   - bars after the last completed trading day (the unfinished intraday bar of today),
   - holiday placeholders without a close (Yahoo sends `null` in every array),
   - zero-volume rows with open = high = low = close (holiday rows repeating the previous close),
   - rows the `price_daily` checks would reject (close <= 0, high < low, volume < 0);
   one bar per date (the last one wins).
   **Currency.** `price_daily` is in `company.currency`. A listing that trades in another currency
   than the company reports in is converted (see "Listings quoted in another currency" below); the
   listing currency is the provider's (`meta.currency` of Yahoo), or the exchange's when the provider
   does not say (EODHD; IDX: IDR).
3. **Overlap check.** When close or adjusted close of the re-fetched latest stored day differ from
   the stored values, the provider has re-based its history (a dividend or split after that day
   changes every earlier adjusted close; Yahoo's `close` is split-adjusted too). The full history is
   then fetched once more (one extra request) and every stored day is corrected.
4. **Write and revalue, in one transaction.** Upsert `price_daily` (same statement as
   `V1.0.5__data_HRTA_market.sql`; unchanged rows are not rewritten), then `ValuationRepository.refresh`:

| Table                                | Recalculated for the company                                                                                     |
|--------------------------------------|------------------------------------------------------------------------------------------------------------------|
| `market_snapshot`                    | every trading day (close × latest share count, EV from the latest balance sheet on or before the day)            |
| `valuation_snapshot`                 | period ends with TTM figures, the latest trading day, and every valuation date already stored for the company     |
| `financial_metric` (`VALUATION`)     | one row per non-null valuation figure; rows no valuation snapshot produces any more are deleted                   |

The formulas are those of `V1.0.6__data_metrics_valuation.sql` (which recalculates all companies on
start and after a filing upload), restricted to one company so a nightly run over hundreds of
tickers stays cheap (about 40 ms per company). A test checks that the company-scoped SQL produces
exactly the rows of `V1.0.6`. A company without financial statements gets prices and market
snapshots (market cap) but no valuation snapshots.

Prices are rounded to 4 decimals (Yahoo sends binary floats such as `2033.51953125`), like the seed.

### Listings quoted in another currency (INDY, SMDR)

INDY trades on IDX in IDR but reports in USD. Storing its IDR prices next to USD statements would make
every valuation wrong by a factor of about 17,900, so the ingestion used to stop with "INDY.JK is
quoted in IDR but INDY reports in USD; nothing stored". Now the prices are converted into the
reporting currency before they are stored, so `price_daily` stays in `company.currency` and every
valuation query, view and API works unchanged:

| Step | Rule |
|------|------|
| Rates | ECB euro foreign exchange reference rates through the Frankfurter API (`GET https://api.frankfurter.dev/v1/{from}..{to}?base=USD&symbols=IDR`, free, no key, one request per ingestion), stored in `fx_rate_daily` (`source = 'ecb'`) |
| Rate of a day | the rate of the last FX day **before** the trading day (Monday uses Friday's). The ECB fixes around 16:00 CET, after the IDX close, so the trading day's own rate is unknown at the close; using it would also make a run before the fixing differ from a later one, which the next run would take for a provider re-adjustment |
| Stored rates | only days before the last completed trading day; only rates of the active source are read |
| Conversion | open, high, low, close, adjusted close / rate, half-up to the 8 decimals of `price_daily`; volume (shares) unchanged. INDY 2026-10-05: IDR 2,570 / 17,950 (2026-10-02) = USD 0.14317549 |
| No rate | a bar without a rate in the 7 days before it is not stored (counted as skipped, `conversion.barsWithoutRate`), never guessed |
| Back to the quote | the company API (`latestPrice.quote`, `quotePrice` of the market snapshot and the valuations) and the technical layer multiply a stored price by the same rate and round to 2 decimals: the 8 decimals of the USD price leave a residue (CUAN 2026-10-16: 0.03009362 x 17,944 = 539.9999), at most 0.0001 rupiah, so 2 decimals give the quote itself (540). Checked on 2026-10-16: every stored CUAN and DSSA close x its rate equals Yahoo's IDR close |
| Result | `conversion`: listing and stored currency, `fxSource` (`ecb USD/IDR`), rates received, bars without a rate; shown in the job detail ("Currency") |

Yahoo's own `USDIDR=X` is deliberately not used: its history has days off by a factor of 10
(888.11 on 2010-11-01, 892 on 2012-02-07), weeks of a stale 9,612.45 (Oct-Dec 2013) and other bad
ticks (15,069.40 on 2024-12-26 against about 16,200), which no outlier threshold separates from the
real 2008 moves. The ECB series has a rate for every TARGET business day (no gap over 5 days since
2008). Verified on INDY: all 4,404 stored days (2008-06-11 .. 2026-10-05) were recomputed
independently from the raw Yahoo IDR closes and the raw ECB rates, 0 close differences.

SMDR (also IDR on IDX, USD reports) is converted the same way: 5,403 days, none without a rate
(2026-10-05: IDR 398 / 17,950 = USD 0.02217270). Its share counts come from public sources
(`V1.0.12__data_SMDR_shares.sql`, [AI_INGESTION_DOCS.md](AI_INGESTION_DOCS.md) section 4), so it has
market caps from 2021-01-04. BNGA's audited share counts come from its annual reports
(`V1.0.13__data_BNGA_shares.sql`), so it has market caps from 2021-12-31. RAJA (USD reports, 1:5 split on
2026-07-16) gets its split-adjusted counts from `V1.0.21__data_RAJA_shares.sql`: market caps from 2021-12-31.

The stored rates are also what the screening and the analysis convert with for companies that report in another
currency: the screening metrics and fiscal years ([SCREENING_DOCS.md](SCREENING_DOCS.md) section 3) and the
analysis fact sheet ([ANALYSIS_DOCS.md](ANALYSIS_DOCS.md) section 3). They are only as recent as the last price
ingestion of such a company.

Market snapshots need share counts. INDY's come from its FY2023 filing (5,202,692,000, derived from
the exact EPS denominator, [AI_INGESTION_DOCS.md](AI_INGESTION_DOCS.md) section 4), so its market
caps start on 2022-01-03 (the first trading day after the first share snapshot, 2021-12-31); earlier
prices have no market cap. INDY on 2026-10-05: USD 0.14317549 x 5,202,692,000 = USD 744.9 million
(Rp 2,570 x 5,202,692,000 = Rp 13.4 trillion).

A newly listed company's filings end before its listing, so their share count is the one of before the IPO:
BACH (listed 2026-07-08, only filing H1 2026) gets its count after the IPO (4,084,430,000 = 3,469,430,000 + 615,000,000
new shares) from `V1.0.24__data_BACH_shares.sql`. Without a full fiscal year it has no trailing twelve months; its
valuation snapshots then hold market cap, EV, book value and P/B only (2026-10-14: Rp 332 x 4,084,430,000 = Rp 1,356.0
billion, P/B 2.21), see [DB_SCHEMA_DOCS.md](DB_SCHEMA_DOCS.md) section 4.6.

## 3. Polite crawling

| Rule                                   | Implementation                                                                                                           |
|----------------------------------------|--------------------------------------------------------------------------------------------------------------------------|
| Slowly, one request at a time          | `PacedHttpClient.get` is synchronized and waits a random `min-delay`..`max-delay` (1-2 s) between requests; all workers share it, so more workers do not send requests faster |
| Never re-request what is stored        | range starts at `MAX(trading_date)`; no request when up to date; never called from a web request                        |
| Back off on HTTP 429                   | queue (every worker) paused 15, 30, then 60 minutes (`backoff`, longer when `Retry-After` asks), then the same job is retried |
| Normal browser, persistent cookies     | browser `User-Agent` on every request; one `java.net.http.HttpClient` with a `CookieManager` for the application        |
| Fallback provider                      | `PriceProvider` interface; `neracalab.prices.provider=eodhd` switches to EODHD                                           |

**HTTP 429 in detail.** A retried job starts again from `MAX(trading_date)` (nothing of it was
written). Jobs that were running beside the job answered 429 and are answered 429 too share its
pause: it counts once. A success resets the wait sequence. A 429 after the last wait stops the run:
the job, the jobs answered 429 beside it and every queued job fail ("Run stopped ..."); submit again
later and each ticker continues from its latest stored day. Other failures (unknown symbol, network
error, currency mismatch) fail only their own job.

**Workers.** `neracalab.jobs.workers` (default 5, env `INGESTION_WORKERS`) companies are ingested at
the same time: their database work (upsert, valuation refresh) runs side by side, their provider
requests still go out one at a time. A job waiting for a free worker is `QUEUED`.

**Throughput.** One request per ticker and day: about 900 requests for the whole IDX, i.e. 15-30
minutes with the 1-2 s pause. Yahoo publishes no limits; a server in a cloud data centre is
blocked much sooner than a home connection, which is when EODHD is the fallback.

The queue lives in memory: a restart loses queued jobs (re-submit; nothing is fetched twice).
Every job is also recorded in `ingestion_job`, so the history survives restarts; jobs that were
still active are marked `FAILED` (Interrupted) at the next start ([INGESTION_JOBS_DOCS.md](INGESTION_JOBS_DOCS.md)).

## 4. Providers

| Provider            | Request                                                                                                          | Symbol (IDX) | Notes                                                                                                   |
|---------------------|------------------------------------------------------------------------------------------------------------------|--------------|---------------------------------------------------------------------------------------------------------|
| `yahoo` (default)   | `GET https://query1.finance.yahoo.com/v8/finance/chart/HRTA.JK?period1=..&period2=..&interval=1d&events=div,split&includeAdjustedClose=true` | `HRTA.JK`    | unofficial, no key; `close` split-adjusted, `adjclose` split- and dividend-adjusted; dates from `meta.exchangeTimezoneName`; 404 / `chart.error` "Not Found" = unknown symbol |
| `eodhd`             | `GET https://eodhd.com/api/eod/HRTA.JK?from=..&to=..&period=d&order=a&fmt=json&api_token=..`                     | `HRTA.JK`    | paid API key; `close` raw, `adjusted_close` split- and dividend-adjusted; no currency in the response (the exchange's currency is assumed: IDX = IDR); 401 / 402 / 403 = token or plan limit. Not yet tested against the live API |
| exchange rates (`EcbFxRateProvider`) | `GET https://api.frankfurter.dev/v1/2026-09-28..2026-10-05?base=USD&symbols=IDR` | -            | ECB reference rates, independent of `provider`; used only for listings quoted in another currency than the company reports in; 404 = unknown currency |

The exchange suffix is a `switch` over `Exchange` in each provider, so adding an exchange constant
does not compile until both providers map it. Error messages name host and path only, never the
query string (it carries the EODHD token).

## 5. Configuration

`application.yaml` (`neracalab.prices`), environment variables in brackets; Docker passes the
variables from `backend/.env` (see [DOCKER_DOCS.md](DOCKER_DOCS.md)).

| Property                     | Default                     | Meaning                                                                    |
|------------------------------|-----------------------------|----------------------------------------------------------------------------|
| `provider` (`PRICE_PROVIDER`) | `yahoo`                    | `yahoo` or `eodhd`                                                         |
| `eodhd.api-token` (`EODHD_API_TOKEN`) | empty              | required for `eodhd`; the backend does not start without it               |
| `min-delay`, `max-delay`     | `1s`, `2s`                  | random pause between two provider requests                                |
| `backoff`                    | `[15m, 30m, 60m]`           | waits after consecutive HTTP 429; a 429 after the last one stops the run  |
| `session-close-cutoff`       | `17:00`                     | exchange-local time from which today's bar is final                       |
| `full-history-from`          | `1990-01-01`                | start of a full-history fetch                                             |
| `user-agent`                 | Chrome on Windows           | sent with every request                                                   |
| `connect-timeout`, `request-timeout` | `10s`, `30s`        | HTTP timeouts                                                             |
| `job-history`                | `500`                       | finished jobs kept in memory                                              |
| `yahoo.base-url`             | `https://query1.finance.yahoo.com` | `query2.finance.yahoo.com` serves the same API                     |
| `fx.base-url`                | `https://api.frankfurter.dev/v1` | ECB reference rates for listings quoted in another currency (INDY) |
| `schedule.enabled` (`PRICE_SCHEDULE_ENABLED`) | `false`    | evening run: like "Update all companies" - the companies of `schedule.exchange` behind, and IHSG and LQ45 |
| `schedule.cron`, `schedule.zone`, `schedule.exchange` | `0 30 17 * * MON-FRI`, `Asia/Jakarta`, `IDX` | when and for which exchange              |

`neracalab.markets` (Forex and Commodities):

| Property | Default | Meaning |
|---|---|---|
| `world-bank.page-url` | `https://www.worldbank.org/en/research/commodity-markets` | the page that links the current Pink Sheet workbook |
| `world-bank.file-url` (`WORLD_BANK_PINK_SHEET_URL`) | empty | the workbook itself (empty: the link on the page) |

`neracalab.crawl` (the HTTP client of the World Bank and the macro sources): `min-delay` `2s` per host, `request-timeout`
`90s`, `user-agent` naming the application. The evening price run (`schedule.enabled`) updates the companies of the
exchange and IHSG; Forex and Commodities are updated from the page.

## 6. Known limitations

- **Seed range of HRTA.** `V1.0.5__data_HRTA_market.sql` upserts HRTA's prices 2024-01-02 ..
  2026-09-30 on every start, so a provider correction inside that range (e.g. Yahoo's adjusted close
  323.9237 vs the seed's 323.9236 on 2024-01-02) is reverted at the next restart. Days after the seed
  are not affected.
- **Splits.** After a split the full re-fetch stores Yahoo's split-adjusted history, while
  `share_snapshot` keeps the share counts of the filings, so the historical market cap before the
  split is wrong until share counts are adjusted (a `corporate_action` based adjustment is not
  implemented).
- **Exchange holidays** are not known: a holiday simply returns no bar.
- **Yahoo's adjusted close jitters** on older days: two identical requests return different
  `adjclose` floats for about half of INDY's history (in the 4th decimal after rounding). Recent days
  are stable, so the overlap check is not affected, but a full re-fetch rewrites such days with
  differences of about 1e-6 (relative).
- **The queue is in memory** (section 3); the job history is kept in `ingestion_job`.

## 7. Tests

| Test                          | Covers                                                                                                                                       |
|-------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------|
| `PriceIngestionServiceTest`   | against the Docker Postgres with a stub provider, each test rolled back: incremental fetch with new rows, market / valuation snapshot and metrics of the new day; intraday cutoff; no request when up to date; full re-fetch after re-adjustment; first ingestion of a company without prices; the company-scoped valuation SQL produces exactly the `V1.0.6` rows; a listing quoted in another currency: conversion with the previous FX day's rate at 8 decimals, rates of the trading day and before the window not used, bars without a rate dropped, EODHD (no currency) converted with the exchange currency, same-currency listings untouched, no rates = nothing stored, rates of another source ignored |
| `EcbFxRateProviderTest`       | a real Frankfurter response: business days only, quote units per base unit; wrong base and missing rates rejected; days without the quote ignored |
| `PriceIngestionQueueTest`     | 429 back-off and retry of the same job, run stop and failed queued jobs, back-off reset after a success, other failures, one active job per company; five workers ingest five companies at the same time, 429 of jobs running together counts as one pause, the run stop fails them all, a queued job stays QUEUED during a pause; an index job (IHSG) runs through the index ingestion, one active job per index |
| `PriceIngestionControllerTest`| the endpoints over MockMvc: 202 + `Location`, job polling, queue listing, 400 / 404                                                           |
| `PriceUpdateServiceTest`      | "Update all companies": only the companies behind are queued (never `full`), a company without prices too, an active job kept, the up-to-date ones skipped; the index queued unless up to date; the status counts; an exchange without index |
| `PriceUpdateControllerTest`   | status, update of an exchange and the IHSG ingestion over MockMvc against the Docker Postgres (stub provider, mocked index): the index job in the queue, recorded in `ingestion_job` with ticker IHSG |
| `IndexIngestionServiceTest`   | the IHSG ingestion: from the latest stored day, today's bar only after the cutoff, no request when up to date, the whole history the first time |
| `InstrumentIngestionServiceTest` | a Forex pair / commodity: the last completed day by the market's zone, no request when up to date, the overlap window, today's bar and a bad print not stored (and deleted), coal monthly up to the month before, palm oil in ringgit as CPO=F x MYR=X (the last rate up to 5 days before), cobalt from the IMF and the wood pulp index from FRED monthly, the whole history the first time |
| `BarCleanerTest`              | the bad prints of the real USD/IDR and USD/JPY histories dropped (one to three days), a real move kept, wicks cut, the first and last bar never judged; the prints the thresholds of the 2026-10-23 pairs were set on (USD/CNY, USD/INR's stale 52.493 with the real 49.388 kept, USD/PHP, USD/MYR, USD/KRW's Lehman day kept, EUR/USD, AUD/JPY's October 2008 kept) |
| `WorldBankPinkSheetClientTest`| the Pink Sheet workbook: the months of one column, "…" months skipped, a column found without its footnote mark ("Rubber, TSR20 **"), one download per update (kept 10 minutes), an unknown column / sheet failing |
| `ImfPcpsClientTest`           | the IMF PCPS answer (SDMX-JSON): the months in order, a month without a price left out; not JSON, a period that is not a month, a price that is not positive, no value, no series failing |
| `MarketControllerTest`        | the markets and instruments (the 9 currency pairs in order), one instrument queued and recorded (exchange FOREX), "Update all" of a market, 404 / 400 |
| `PriceIngestionRulesTest`     | cleaning rules, numeric price comparison, last completed trading day (cutoff, weekends)                                                       |
| `PricesAfterUploadTest`       | prices of a stored filing's company: queued when missing or behind, for the uploader, never `full`; the active job is reused; nothing when up to date, for an unknown company or when switched off (`PriceIngestionServiceTest`: up to date only when the last completed trading day is stored) |
| `YahooPriceProviderTest`      | parsing of a real Yahoo response (HRTA.JK around the Rp40 dividend and a holiday), symbol mapping, `Retry-After`                              |

Run with `cd backend && ./mvnw test` (needs the Postgres on localhost:5432).

Live check of this version (Yahoo Finance, 2026-10-03): HRTA from the seed end 2026-09-30 → 1
request, 2 days inserted, valuation of 2026-10-02 (P/E 7.58 at Rp2,190); a second call → 0
requests; a company without prices (BBCA, temporary) → 1 full-history request, 5,382 days from
2004-06-08 inserted (127 bars skipped); unknown company → 404.
