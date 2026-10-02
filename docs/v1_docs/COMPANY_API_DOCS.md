# Neraca Lab - Company APIs (v1)

Read-only APIs over the data in the database (see [DB_SCHEMA_DOCS.md](DB_SCHEMA_DOCS.md)): which
companies exist on an exchange, and everything stored for one company. Data gets in through the
upload endpoint ([AI_INGESTION_DOCS.md](AI_INGESTION_DOCS.md)) or the SQL seed scripts.

Code: `backend/src/main/java/com/neracalab/backend/company/`

| Class                    | Role                                                                   |
|--------------------------|------------------------------------------------------------------------|
| `ExchangeController`     | `GET /api/v1/exchanges`                                                |
| `CompanyController`      | company endpoints and error responses (ProblemDetail)                  |
| `CompanyService`         | assembles the responses in one read-only REPEATABLE READ transaction   |
| `CompanyQueryRepository` | SQL (JdbcClient), every query scoped to one company or exchange        |
| `Exchange`               | supported exchanges (enum name = `company.exchange`)                   |
| `Tickers`                | ticker normalisation (trim, upper case, format), shared with ingestion |

The web frontend that uses these APIs is described in [FRONTEND_DOCS.md](FRONTEND_DOCS.md).

## 1. Supported exchanges

```http
GET /api/v1/exchanges
```

```json
[ { "code": "IDX", "name": "Indonesia Stock Exchange", "country": "Indonesia" } ]
```

The constants of `Exchange`, in declaration order. `code` is the value for the `exchange`
parameter / path segment of the company APIs (e.g. for an exchange filter).

## 2. List companies of an exchange

```http
GET /api/v1/companies?exchange=IDX
```

```bash
curl "http://localhost:8080/api/v1/companies?exchange=IDX"
```

| Parameter  | Required | Default | Notes                                   |
|------------|----------|---------|-----------------------------------------|
| `exchange` | no       | `IDX`   | case-insensitive; must be in `Exchange` |

Response (companies ordered by ticker):

```json
{
  "exchange": "IDX",
  "exchangeName": "Indonesia Stock Exchange",
  "count": 1,
  "companies": [
    {
      "companyId": 1, "ticker": "HRTA", "exchange": "IDX",
      "companyName": "Hartadinata Abadi", "legalName": "PT Hartadinata Abadi Tbk",
      "sector": "Consumer Cyclicals", "industry": "Apparel & Luxury Goods",
      "country": "Indonesia", "currency": "IDR", "fiscalYearEnd": "2025-12-31", "active": true,
      "periodCount": 10, "firstPeriodEnd": "2024-03-31",
      "latestPeriod": "2026 H1", "latestPeriodEnd": "2026-06-30",
      "latestPriceDate": "2026-09-30"
    }
  ]
}
```

| Field                             | Source                                                                                                       |
|-----------------------------------|--------------------------------------------------------------------------------------------------------------|
| master data                       | `company`                                                                                                    |
| `periodCount`, `firstPeriodEnd`   | `reporting_period` (count, earliest `period_end`)                                                            |
| `latestPeriod`, `latestPeriodEnd` | most recent `reporting_period` (`<fiscal_year> <period_type>`; the longest period wins on an equal end date) |
| `latestPriceDate`                 | `max(price_daily.trading_date)`; `null` when no prices are loaded                                            |

An exchange without companies returns `count: 0` and an empty list.

## 3. Company detail

```http
GET /api/v1/companies/{exchange}/{ticker}
```

```bash
curl http://localhost:8080/api/v1/companies/IDX/HRTA
```

Exchange and ticker are case-insensitive (`/idx/hrta` works); the ticker is trimmed and
upper-cased like at ingestion (`Tickers.normalize`).

| Field                                                              | Content                                                                              | Source                                                                       |
|--------------------------------------------------------------------|--------------------------------------------------------------------------------------|------------------------------------------------------------------------------|
| `company`                                                          | all master data incl. `exchangeName`, `createdAt`, `updatedAt`                       | `company`                                                                    |
| `coverage`                                                         | row counts per table, first / latest period end, first / latest price date           | all company tables                                                           |
| `periods[]`                                                        | most recent first (longest first on equal end dates)                                 | `reporting_period`                                                           |
| `periods[].incomeStatement` / `balanceSheet` / `cashFlowStatement` | every statement column, camelCase; `null` when no row exists for the period          | `income_statement`, `balance_sheet`, `cash_flow_statement`                   |
| `periods[].segments[]`                                             | segment figures of the period, largest revenue first                                 | `segment_financial` + `segment`                                              |
| `periods[].metrics`                                                | fundamental metrics by name: `{category, value, unit}`                               | `financial_metric` with `metric_date = period_end`, category not `VALUATION` |
| `segments[]`                                                       | the company's segments / revenue lines                                               | `segment`                                                                    |
| `shareSnapshots[]`                                                 | share counts, most recent first                                                      | `share_snapshot`                                                             |
| `latestPrice`                                                      | last trading day (OHLCV); `null` without prices                                      | `price_daily`                                                                |
| `latestMarketSnapshot`                                             | last market cap / enterprise value; `null` without prices                            | `market_snapshot`                                                            |
| `valuations[]`                                                     | valuation snapshots, most recent first, with the `period` whose TTM figures they use | `valuation_snapshot`                                                         |
| `corporateActions[]`                                               | most recent first                                                                    | `corporate_action`                                                           |

Shortened example:

```json
{
  "company": { "companyId": 1, "ticker": "HRTA", "exchange": "IDX", "exchangeName": "Indonesia Stock Exchange", "...": "..." },
  "coverage": { "periods": 10, "incomeStatements": 10, "balanceSheets": 7, "cashFlowStatements": 10,
                "segments": 7, "segmentFinancials": 59, "priceDays": 649,
                "firstPriceDate": "2024-01-02", "latestPriceDate": "2026-09-30", "...": "..." },
  "periods": [
    {
      "periodId": 1, "period": "2026 H1", "fiscalYear": 2026, "fiscalQuarter": 2, "periodType": "H1",
      "periodStart": "2026-01-01", "periodEnd": "2026-06-30", "audited": false,
      "sourceFiling": "FinancialStatement-2026-II-HRTA.xlsx",
      "incomeStatement": { "revenue": 33806129682661.0000, "...": "..." },
      "balanceSheet": { "totalAssets": 11350876052356.0000, "...": "..." },
      "cashFlowStatement": { "endingCash": 983698277704.0000, "...": "..." },
      "segments": [ { "segmentId": 3, "segmentType": "PRODUCT",
                      "segmentName": "Penjualan perhiasan dan logam mulia - Grosir",
                      "segmentNameEn": "Jewelry and precious metal sales - Wholesale",
                      "revenue": 30199040866058.0000, "...": "..." } ],
      "metrics": { "gross_margin": { "category": "PROFITABILITY", "value": 0.0369300000, "unit": "ratio" } }
    }
  ],
  "segments": [], "shareSnapshots": [], "latestPrice": {}, "latestMarketSnapshot": {},
  "valuations": [], "corporateActions": []
}
```

Conventions (same as the database):

- Amounts in full units of `company.currency`; income-statement expenses positive, cash outflows
  negative; `null` = not reported, `0` = reported as zero. Numbers keep the database scale
  (e.g. `NUMERIC(24,4)` -> `123.0000`).
- `H1` / `9M` are year-to-date periods (IDX "Kuartal II / III"), `fiscalQuarter` is the quarter
  the period ends in, `null` for `FY`.
- Ratios are fractions (`0.25` = 25%).
- Dates are ISO `yyyy-MM-dd`, timestamps ISO-8601 UTC.
- Daily series are summarised, not listed: the price range is in `coverage`, the last day in
  `latestPrice` / `latestMarketSnapshot`.

All queries of one response run in one read-only REPEATABLE READ transaction, so the response is
a consistent snapshot even while an upload writes the same company.

## 4. Errors

Errors are RFC 9457 ProblemDetail bodies (`application/problem+json`):

| HTTP | `title`              | When                                                                 |
|------|----------------------|----------------------------------------------------------------------|
| 400  | Unsupported exchange | exchange not in `Exchange`; body lists `supportedExchanges`          |
| 400  | Invalid ticker       | not 1-20 letters, digits, `.` or `-` starting with a letter or digit |
| 404  | Company not found    | no company with this ticker on this exchange                         |

```json
{ "title": "Unsupported exchange", "status": 400,
  "detail": "Unsupported exchange 'NYSE'; supported: IDX",
  "instance": "/api/v1/companies", "supportedExchanges": ["IDX"] }
```

## 5. Company identity

A company is identified by `(ticker, exchange)`, unique in the database
(`uq_company_ticker_exchange`); `exchange` is `NOT NULL`, and the checks `ck_company_ticker` /
`ck_company_exchange` keep both codes upper case without blanks, so `hrta` or `" HRTA"` can never
become a second row. The upload normalises the filing's ticker with the same `Tickers.normalize`
and registers the company with one atomic `INSERT ... ON CONFLICT ... DO UPDATE ... RETURNING`.
Details: [DB_SCHEMA_DOCS.md](DB_SCHEMA_DOCS.md) section 3.1, [AI_INGESTION_DOCS.md](AI_INGESTION_DOCS.md) section 3.

## 6. Adding an exchange

1. Add a constant to `Exchange` (code = value stored in `company.exchange`), e.g.
   `NYSE("New York Stock Exchange", "United States")`.
2. `GET /api/v1/exchanges` lists it and the company APIs accept it immediately (so the frontend's
   exchange filter offers it); the list is empty until companies of that exchange are loaded.
3. The upload endpoint reads IDX XBRL workbooks only (`IngestionRepository.EXCHANGE = IDX`);
   another exchange needs its own reader / mapper, or SQL data scripts.

## 7. Tests

| Test                    | Covers                                                                                                                 |
|-------------------------|------------------------------------------------------------------------------------------------------------------------|
| `CompanyControllerTest` | all three endpoints over MockMvc against the HRTA seed data: ordering, figures, `null` statements, coverage, 400 / 404 |
| `CompanyUniquenessTest` | duplicate, lower-case, padded and exchange-less rows rejected; ingestion upsert keeps one row                          |
| `CompanyCodesTest`      | ticker and exchange normalisation                                                                                      |

Like `BackendApplicationTests`, they need the Postgres on localhost:5432 (the full stack from the
start scripts, or `docker compose up -d postgres redis` in `backend/`).
