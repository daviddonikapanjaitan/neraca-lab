# Neraca Lab

Application for analyzing financial statements using LLM.

- Backend using: Spring Boot 4 (Java 21) + PostgreSQL 17 + Redis 7
- Frontend using: Next.js

Assistant developer: Claude Code (Opus 5.5 LLM Model)

## Project structure

```
neraca_lab/
├── backend/                     Spring Boot application
│   ├── Dockerfile
│   ├── docker-compose.yaml      postgres + redis + backend
│   └── src/main/resources/
│       ├── application.yaml
│       └── db/                  SQL scripts (no Flyway)
│           ├── V1.0.1__schema.sql
│           ├── V1.0.2__views.sql
│           └── V1.0.3__data_HRTA_2026_H1.sql
└── data/                        source financial statements (IDX XBRL .xlsx)
```

## Running the backend

Requires Docker.

```bash
cd backend
docker compose up -d --build     # start postgres, redis and backend
docker compose logs -f backend   # follow logs
docker compose down              # stop (add -v to wipe the database volume)
```

| Service  | Port | Default credentials          |
|----------|------|------------------------------|
| backend  | 8080 | -                            |
| postgres | 5432 | db/user/password `neracalab` |
| redis    | 6379 | password `neracalab`         |

Override the defaults with a `backend/.env` file (`DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`,
`REDIS_PASSWORD`, `JAVA_OPTS`). Use real passwords outside local development.

To run the backend from the IDE instead, start only the infrastructure with
`docker compose up -d postgres redis`; `application.yaml` defaults to `localhost`.

## Database

The schema is managed by plain SQL scripts in `backend/src/main/resources/db/`, executed by
Spring SQL init (`spring.sql.init.*`) on every application start, in the order listed in
`application.yaml`. Every script is idempotent (`IF NOT EXISTS`, upserts), so re-running is safe.
Hibernate does not touch the schema (`ddl-auto: none`).

| Script                          | Content                                                        |
|---------------------------------|----------------------------------------------------------------|
| `V1.0.1__schema.sql`            | schemas `company`, `fundamental`, `market` and their tables    |
| `V1.0.2__views.sql`             | analysis views (recreated on every start)                      |
| `V1.0.3__data_HRTA_2026_H1.sql` | PT Hartadinata Abadi Tbk (HRTA) Q2-2026 filing data            |

### Tables

| Table                             | Purpose                                                    |
|-----------------------------------|------------------------------------------------------------|
| `company.company`                 | company master data                                        |
| `company.corporate_action`        | splits, rights issues, dividends, buybacks, ...            |
| `fundamental.reporting_period`    | one row per company per period (`FY`, `Q1`-`Q4`, `H1`, `9M`, `TTM`) |
| `fundamental.income_statement`    | income statement                                           |
| `fundamental.balance_sheet`       | statement of financial position                            |
| `fundamental.cash_flow_statement` | cash flow statement                                        |
| `fundamental.revenue_segment`     | revenue breakdown from the notes                           |
| `market.stock_price`              | share prices, needed for valuation views                   |

Conventions: amounts in full units of the company currency; income-statement expenses are
positive; cash-flow outflows are negative; `NULL` = not reported, `0` = reported as zero.
IDX "Kuartal II" filings are 6-month year-to-date, stored as `period_type = 'H1'`.

### Analysis views (Roaring Kitty / Keith Gill style deep value)

| View                               | Content                                                                 |
|------------------------------------|-------------------------------------------------------------------------|
| `fundamental.v_key_metrics`        | net cash, tangible book, NCAV (net-net), liquidity, FCF, margins, ROE/ROA/ROIC, working-capital days |
| `fundamental.v_valuation`          | P/E, P/B, P/TBV, EV/EBIT, EV/EBITDA, FCF yield at the price on period end |
| `fundamental.v_latest_valuation`   | same multiples using the latest price and latest full report            |

Ratios are fractions (`0.25` = 25%). `*_annualized` columns scale partial-year flows by
12 / period months. The valuation views return rows once prices are loaded, e.g.:

```sql
INSERT INTO market.stock_price (company_id, price_date, close_price)
SELECT company_id, DATE '2026-06-30', 1000 FROM company.company WHERE ticker = 'HRTA';

SELECT * FROM fundamental.v_latest_valuation;
```

### Adding a new financial statement

1. Put the source file in `data/`.
2. Create the next script, e.g. `V1.0.4__data_<TICKER>_<YEAR>_<PERIOD>.sql`, following the
   upsert pattern of `V1.0.3__data_HRTA_2026_H1.sql`.
3. Add it to `spring.sql.init.data-locations` in `application.yaml` and restart the backend.
