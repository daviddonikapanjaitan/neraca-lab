# Neraca Lab - Daily Price Ingestion (v1)

Loads daily prices (`price_daily`) from a market data provider and recalculates the company's
valuation (`market_snapshot`, `valuation_snapshot`, VALUATION rows of `financial_metric`).
Default provider: the Yahoo Finance chart API (free, unofficial); fallback: EODHD (paid API key).

Code: `backend/src/main/java/com/neracalab/backend/price/`

| Class                      | Role                                                                                                     |
|----------------------------|----------------------------------------------------------------------------------------------------------|
| `PriceIngestionController` | `/api/v1/prices/ingestions` endpoints and error responses (ProblemDetail)                                |
| `PriceIngestionQueue`      | in-memory job queue with one worker thread; HTTP 429 back-off; one active job per company               |
| `PriceIngestionJob`        | job state and its JSON view                                                                              |
| `PriceIngestionService`    | one ingestion: date range, provider request, cleaning, re-adjustment check, write + valuation refresh   |
| `PriceDailyRepository`     | company lookup, latest stored day, `price_daily` upsert                                                  |
| `ValuationRepository`      | company-scoped recalculation with the formulas of `V1.0.6__data_metrics_valuation.sql`                   |
| `PriceIngestionSchedule`   | optional evening run over all active companies (off by default)                                          |
| `PriceProperties`          | configuration `neracalab.prices.*`                                                                       |
| `provider/PriceProvider`   | provider interface; `YahooPriceProvider` / `EodhdPriceProvider`, one active by configuration             |
| `provider/PacedHttpClient` | the one HTTP client of the providers: cookie store, browser User-Agent, one request at a time, pause     |

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
(`V1.0.13__data_BNGA_shares.sql`), so it has market caps from 2021-12-31.

Market snapshots need share counts. INDY's come from its FY2023 filing (5,202,692,000, derived from
the exact EPS denominator, [AI_INGESTION_DOCS.md](AI_INGESTION_DOCS.md) section 4), so its market
caps start on 2022-01-03 (the first trading day after the first share snapshot, 2021-12-31); earlier
prices have no market cap. INDY on 2026-10-05: USD 0.14317549 x 5,202,692,000 = USD 744.9 million
(Rp 2,570 x 5,202,692,000 = Rp 13.4 trillion).

## 3. Polite crawling

| Rule                                   | Implementation                                                                                                           |
|----------------------------------------|--------------------------------------------------------------------------------------------------------------------------|
| Slowly, one request at a time          | one worker thread; `PacedHttpClient.get` is synchronized and waits a random `min-delay`..`max-delay` (1-2 s) between requests |
| Never re-request what is stored        | range starts at `MAX(trading_date)`; no request when up to date; never called from a web request                        |
| Back off on HTTP 429                   | queue paused 15, 30, then 60 minutes (`backoff`, longer when `Retry-After` asks), then the same job is retried          |
| Normal browser, persistent cookies     | browser `User-Agent` on every request; one `java.net.http.HttpClient` with a `CookieManager` for the application        |
| Fallback provider                      | `PriceProvider` interface; `neracalab.prices.provider=eodhd` switches to EODHD                                           |

**HTTP 429 in detail.** A retried job starts again from `MAX(trading_date)` (nothing of it was
written). A success resets the wait sequence. A 429 after the last wait stops the run: the job and
every queued job fail ("Run stopped ..."); submit again later and each ticker continues from its
latest stored day. Other failures (unknown symbol, network error, currency mismatch) fail only
their own job.

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
| `schedule.enabled` (`PRICE_SCHEDULE_ENABLED`) | `false`    | evening run: queues every active company of `schedule.exchange`           |
| `schedule.cron`, `schedule.zone`, `schedule.exchange` | `0 30 17 * * MON-FRI`, `Asia/Jakarta`, `IDX` | when and for which exchange              |

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
| `PriceIngestionQueueTest`     | 429 back-off and retry of the same job, run stop and failed queued jobs, back-off reset after a success, other failures, one active job per company |
| `PriceIngestionControllerTest`| the endpoints over MockMvc: 202 + `Location`, job polling, queue listing, 400 / 404                                                           |
| `PriceIngestionRulesTest`     | cleaning rules, numeric price comparison, last completed trading day (cutoff, weekends)                                                       |
| `YahooPriceProviderTest`      | parsing of a real Yahoo response (HRTA.JK around the Rp40 dividend and a holiday), symbol mapping, `Retry-After`                              |

Run with `cd backend && ./mvnw test` (needs the Postgres on localhost:5432).

Live check of this version (Yahoo Finance, 2026-10-03): HRTA from the seed end 2026-09-30 → 1
request, 2 days inserted, valuation of 2026-10-02 (P/E 7.58 at Rp2,190); a second call → 0
requests; a company without prices (BBCA, temporary) → 1 full-history request, 5,382 days from
2004-06-08 inserted (127 bars skipped); unknown company → 404.
