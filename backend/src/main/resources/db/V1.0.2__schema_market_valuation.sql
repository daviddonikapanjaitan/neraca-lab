-- =====================================================================
-- Neraca Lab - segments, market data, share counts, metrics and valuation
-- (PostgreSQL 15+, all objects in the public schema)
--
-- Relationships (every table references company):
--   segment            -> segment_financial (per reporting_period)
--   reporting_period   -> financial_metric, valuation_snapshot (period the figures come from)
--   price_daily + share_snapshot + balance_sheet -> market_snapshot (derived, daily)
--   statements + price_daily + share_snapshot    -> valuation_snapshot -> financial_metric
--
-- Executed on every application start by Spring SQL init, after V1.0.1__schema.sql.
-- Every statement is idempotent. Same conventions as V1.0.1 (full currency units,
-- NULL = not reported / not available).
-- =====================================================================

-- ---------------------------------------------------------------------
-- legacy tables replaced in this version:
--   revenue_segment -> segment + segment_financial
--   stock_price     -> price_daily (+ share_snapshot for share counts)
-- CASCADE also drops the analysis views that read stock_price; V1.0.3__views.sql
-- recreates them on price_daily.
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS revenue_segment;
DROP TABLE IF EXISTS stock_price CASCADE;

-- ---------------------------------------------------------------------
-- segment : reportable segments / revenue lines of a company
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS segment (
    segment_id          BIGSERIAL PRIMARY KEY,

    company_id          BIGINT NOT NULL
        REFERENCES company(company_id),

    segment_type        VARCHAR(20) NOT NULL,
    segment_name        VARCHAR(255) NOT NULL,
    segment_name_en     VARCHAR(255),
    description         TEXT,
    active              BOOLEAN NOT NULL DEFAULT TRUE,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_segment UNIQUE (company_id, segment_type, segment_name),
    -- target of the composite FK in segment_financial
    CONSTRAINT uq_segment_company UNIQUE (segment_id, company_id),
    CONSTRAINT ck_segment_type
        CHECK (segment_type IN ('PRODUCT', 'SERVICE', 'GEOGRAPHY', 'CUSTOMER', 'OTHER'))
);

COMMENT ON TABLE  segment IS 'Reportable segments / revenue lines of a company, as named in the filings.';
COMMENT ON COLUMN segment.segment_type IS 'PRODUCT, SERVICE, GEOGRAPHY, CUSTOMER or OTHER (items the filings classify inconsistently).';
COMMENT ON COLUMN segment.segment_name IS 'Segment name as written in the filing (original language).';
COMMENT ON COLUMN segment.segment_name_en IS 'English translation of segment_name.';

-- ---------------------------------------------------------------------
-- segment_financial : figures per segment per reporting period
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS segment_financial (
    segment_financial_id BIGSERIAL PRIMARY KEY,

    segment_id          BIGINT NOT NULL
        REFERENCES segment(segment_id),

    company_id          BIGINT NOT NULL
        REFERENCES company(company_id),

    period_id           BIGINT NOT NULL
        REFERENCES reporting_period(period_id),

    revenue             NUMERIC(24,4),
    cost_of_revenue     NUMERIC(24,4),
    gross_profit        NUMERIC(24,4),
    operating_income    NUMERIC(24,4),
    total_assets        NUMERIC(24,4),

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_segment_financial UNIQUE (segment_id, period_id),
    -- segment and period must belong to the same company as the row
    CONSTRAINT fk_segment_financial_segment_company
        FOREIGN KEY (segment_id, company_id)
        REFERENCES segment (segment_id, company_id),
    CONSTRAINT fk_segment_financial_period_company
        FOREIGN KEY (period_id, company_id)
        REFERENCES reporting_period (period_id, company_id)
);

CREATE INDEX IF NOT EXISTS ix_segment_financial_period
    ON segment_financial (period_id);

COMMENT ON TABLE  segment_financial IS 'Segment figures per period. Revenue rows of one period sum to income_statement.revenue.';
COMMENT ON COLUMN segment_financial.revenue IS 'Segment revenue (same sign convention as income_statement).';
COMMENT ON COLUMN segment_financial.operating_income IS 'Segment result, when the filing discloses operating segments.';

-- ---------------------------------------------------------------------
-- price_daily : daily OHLCV per company
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS price_daily (
    price_id             BIGSERIAL PRIMARY KEY,
    company_id           BIGINT NOT NULL
        REFERENCES company(company_id),
    trading_date         DATE NOT NULL,
    open_price           NUMERIC(20,8),
    high_price           NUMERIC(20,8),
    low_price            NUMERIC(20,8),
    close_price          NUMERIC(20,8),
    adjusted_close       NUMERIC(20,8),
    volume               BIGINT,

    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_price_daily UNIQUE (company_id, trading_date),
    CONSTRAINT ck_price_daily_positive CHECK (close_price IS NULL OR close_price > 0),
    CONSTRAINT ck_price_daily_range CHECK (high_price IS NULL OR low_price IS NULL OR high_price >= low_price),
    CONSTRAINT ck_price_daily_volume CHECK (volume IS NULL OR volume >= 0)
);

COMMENT ON TABLE  price_daily IS 'Daily prices in company.currency, one row per trading day. A listing quoted in another currency (INDY: IDR quote, USD reporting) is converted with fx_rate_daily.';
COMMENT ON COLUMN price_daily.adjusted_close IS 'Close adjusted for splits and cash dividends (provider adjustment).';
COMMENT ON COLUMN price_daily.volume IS 'Traded volume in shares.';

-- ---------------------------------------------------------------------
-- share_snapshot : share counts at a date
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS share_snapshot (
    share_snapshot_id    BIGSERIAL PRIMARY KEY,
    company_id           BIGINT NOT NULL
        REFERENCES company(company_id),
    snapshot_date        DATE NOT NULL,
    basic_shares         NUMERIC(24,8),
    diluted_shares       NUMERIC(24,8),
    shares_outstanding   NUMERIC(24,8),
    public_float         NUMERIC(24,8),
    treasury_shares      NUMERIC(24,8),

    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_share_snapshot UNIQUE (company_id, snapshot_date)
);

COMMENT ON TABLE  share_snapshot IS 'Share counts at a date (period ends of the filings, corporate actions, ...).';
COMMENT ON COLUMN share_snapshot.basic_shares IS 'Weighted average shares for basic EPS of the period ending on snapshot_date.';
COMMENT ON COLUMN share_snapshot.shares_outstanding IS 'Issued shares less treasury shares at snapshot_date; used for market cap.';
COMMENT ON COLUMN share_snapshot.public_float IS 'Shares held by the public (free float).';

-- ---------------------------------------------------------------------
-- market_snapshot : market value per company per day
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS market_snapshot (
    snapshot_id          BIGSERIAL PRIMARY KEY,
    company_id           BIGINT NOT NULL
        REFERENCES company(company_id),
    snapshot_date        DATE NOT NULL,
    share_price          NUMERIC(20,8),
    shares_outstanding   NUMERIC(24,8),
    market_cap           NUMERIC(28,4),
    enterprise_value     NUMERIC(28,4),

    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_market_snapshot UNIQUE (company_id, snapshot_date)
);

COMMENT ON TABLE  market_snapshot IS 'Market value per trading day, derived from price_daily, share_snapshot and the latest balance sheet.';
COMMENT ON COLUMN market_snapshot.shares_outstanding IS 'Latest share_snapshot on or before snapshot_date.';
COMMENT ON COLUMN market_snapshot.market_cap IS 'share_price x shares_outstanding.';
COMMENT ON COLUMN market_snapshot.enterprise_value IS
    'market_cap + total debt (incl. leases) - cash and investments + non-controlling interest, from the latest balance sheet with period_end on or before snapshot_date; NULL when no balance sheet is available yet.';

-- ---------------------------------------------------------------------
-- financial_metric : one metric value per company per period / date (long format)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS financial_metric (
    metric_id             BIGSERIAL PRIMARY KEY,
    company_id            BIGINT NOT NULL
        REFERENCES company(company_id),
    period_id             BIGINT
        REFERENCES reporting_period(period_id),
    metric_date            DATE,
    metric_name            VARCHAR(100) NOT NULL,
    metric_category        VARCHAR(50),
    metric_value           NUMERIC(28,10),
    unit                   VARCHAR(30),
    calculation_formula    TEXT,
    source                 VARCHAR(100),
    created_at              TIMESTAMPTZ DEFAULT now(),

    CONSTRAINT uq_financial_metric
        UNIQUE NULLS NOT DISTINCT (company_id, period_id, metric_date, metric_name),
    -- a metric of a period can never point to another company's period
    CONSTRAINT fk_financial_metric_period_company
        FOREIGN KEY (period_id, company_id)
        REFERENCES reporting_period (period_id, company_id),
    CONSTRAINT ck_financial_metric_anchor
        CHECK (period_id IS NOT NULL OR metric_date IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS ix_financial_metric_name_date
    ON financial_metric (company_id, metric_name, metric_date);

COMMENT ON TABLE  financial_metric IS 'Calculated metrics in long format (one row per metric), e.g. margins, returns, leverage, EV/OP.';
COMMENT ON COLUMN financial_metric.period_id IS 'Reporting period the metric is based on (for valuation metrics: the period of the TTM figures).';
COMMENT ON COLUMN financial_metric.metric_date IS 'Period end for fundamental metrics, valuation date for valuation metrics.';
COMMENT ON COLUMN financial_metric.metric_category IS 'PROFITABILITY, LIQUIDITY, LEVERAGE, EFFICIENCY, CASH_FLOW, BALANCE_SHEET, PER_SHARE, VALUATION.';
COMMENT ON COLUMN financial_metric.unit IS 'ratio (fraction, 0.25 = 25%), x (multiple), days, IDR or IDR/share (company currency).';
COMMENT ON COLUMN financial_metric.source IS 'Object the value was calculated from (v_key_metrics, valuation_snapshot, ...).';

-- ---------------------------------------------------------------------
-- valuation_snapshot : price vs trailing-twelve-month fundamentals
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS valuation_snapshot (
    valuation_id          BIGSERIAL PRIMARY KEY,
    company_id            BIGINT NOT NULL
        REFERENCES company(company_id),
    period_id             BIGINT
        REFERENCES reporting_period(period_id),
    valuation_date        DATE NOT NULL,
    share_price           NUMERIC(20,8),
    market_cap            NUMERIC(28,4),
    enterprise_value      NUMERIC(28,4),
    eps_ttm               NUMERIC(20,8),
    revenue_ttm           NUMERIC(28,4),
    ebitda_ttm            NUMERIC(28,4),
    operating_income_ttm  NUMERIC(28,4),
    fcf_ttm               NUMERIC(28,4),
    book_value            NUMERIC(28,4),
    pe_ratio              NUMERIC(20,8),
    ps_ratio              NUMERIC(20,8),
    pb_ratio              NUMERIC(20,8),
    ev_ebitda             NUMERIC(20,8),
    ev_sales              NUMERIC(20,8),
    ev_op                 NUMERIC(20,8),
    fcf_yield             NUMERIC(20,8),
    earnings_yield        NUMERIC(20,8),

    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_valuation_snapshot UNIQUE (company_id, valuation_date),
    CONSTRAINT fk_valuation_snapshot_period_company
        FOREIGN KEY (period_id, company_id)
        REFERENCES reporting_period (period_id, company_id)
);

COMMENT ON TABLE  valuation_snapshot IS 'Valuation at a date: last close on or before valuation_date against TTM figures of the latest period ended on or before it.';
COMMENT ON COLUMN valuation_snapshot.period_id IS 'Reporting period whose TTM figures and balance sheet are used.';
COMMENT ON COLUMN valuation_snapshot.share_price IS 'Last close on or before valuation_date.';
COMMENT ON COLUMN valuation_snapshot.enterprise_value IS 'market_cap + total debt (incl. leases) - cash and investments + non-controlling interest.';
COMMENT ON COLUMN valuation_snapshot.eps_ttm IS 'TTM profit attributable to the parent / shares outstanding.';
COMMENT ON COLUMN valuation_snapshot.book_value IS 'Equity attributable to owners of the parent.';
COMMENT ON COLUMN valuation_snapshot.ev_op IS 'EV / operating profit: enterprise_value / operating_income_ttm.';
COMMENT ON COLUMN valuation_snapshot.fcf_yield IS 'fcf_ttm / market_cap.';
COMMENT ON COLUMN valuation_snapshot.earnings_yield IS 'eps_ttm / share_price (inverse of P/E).';
