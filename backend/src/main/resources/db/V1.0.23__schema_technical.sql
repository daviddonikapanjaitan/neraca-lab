-- =====================================================================
-- Neraca Lab - technical analysis (PostgreSQL 15+)
--
--   index_daily          daily bars of a market index (IHSG = Yahoo ^JKSE), for relative strength and the
--                        market regime
--   corporate_event      dated events of a listing for chart markers: dividends and splits from Yahoo
--                        Finance (earnings dates once the filings carry them); corporate_action keeps the
--                        splits detected in the filings
--   corporate_event_fetch when the events of a company were last fetched (the nightly job refetches them after
--                        neracalab.technical.events.refresh-after)
--   macro_series         macro observations (BI rate, SBN 10Y yield). USD/IDR is read from fx_rate_daily.
--                        No source is connected yet: rows can be inserted by hand; the snapshot reports
--                        null while a series is empty
--   indicator_snapshot   the latest technical snapshot of a company: versioned JSON documented in
--                        docs/indicator-snapshot.md, the input every future technical agent reads
--   chart_setting        the last chart settings of a user on the Technicals page
--
-- Daily prices stay in price_daily (company.currency). Executed on every application start by Spring SQL
-- init. Every statement is idempotent; no existing table is changed.
-- =====================================================================

CREATE TABLE IF NOT EXISTS index_daily (
    index_daily_id  BIGSERIAL PRIMARY KEY,
    index_code      VARCHAR(20) NOT NULL,
    exchange        VARCHAR(20) NOT NULL,
    trading_date    DATE NOT NULL,
    open_price      NUMERIC(20,8),
    high_price      NUMERIC(20,8),
    low_price       NUMERIC(20,8),
    close_price     NUMERIC(20,8),
    volume          BIGINT,
    source          VARCHAR(20) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_index_daily UNIQUE (index_code, trading_date),
    CONSTRAINT ck_index_daily_positive CHECK (close_price IS NULL OR close_price > 0),
    CONSTRAINT ck_index_daily_range CHECK (high_price IS NULL OR low_price IS NULL OR high_price >= low_price)
);

COMMENT ON TABLE index_daily IS 'Daily bars of a market index (IHSG: Yahoo ^JKSE), in index points.';

CREATE TABLE IF NOT EXISTS corporate_event (
    corporate_event_id  BIGSERIAL PRIMARY KEY,
    company_id          BIGINT NOT NULL REFERENCES company(company_id),
    event_type          VARCHAR(30) NOT NULL,
    event_date          DATE NOT NULL,
    value               NUMERIC(24,8),
    description         TEXT,
    source              VARCHAR(20) NOT NULL,
    source_url          TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_corporate_event UNIQUE (company_id, event_type, event_date, source),
    CONSTRAINT ck_corporate_event_type CHECK (event_type IN ('EARNINGS', 'RUPS', 'RUPSLB', 'DIVIDEND',
        'RIGHTS_ISSUE', 'PRIVATE_PLACEMENT', 'STOCK_SPLIT', 'REVERSE_SPLIT'))
);

COMMENT ON TABLE corporate_event IS 'Dated corporate events for chart markers. value: cash dividend per share (in the listing currency) or new shares per old share of a split.';

CREATE INDEX IF NOT EXISTS ix_corporate_event_company_date ON corporate_event (company_id, event_date);

CREATE TABLE IF NOT EXISTS corporate_event_fetch (
    company_id      BIGINT NOT NULL REFERENCES company(company_id),
    source          VARCHAR(20) NOT NULL,
    fetched_at      TIMESTAMPTZ NOT NULL,
    events          INTEGER NOT NULL,

    CONSTRAINT pk_corporate_event_fetch PRIMARY KEY (company_id, source)
);

CREATE TABLE IF NOT EXISTS macro_series (
    macro_series_id BIGSERIAL PRIMARY KEY,
    series_code     VARCHAR(30) NOT NULL,
    obs_date        DATE NOT NULL,
    value           NUMERIC(20,8) NOT NULL,
    source          VARCHAR(50) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_macro_series UNIQUE (series_code, obs_date)
);

COMMENT ON TABLE macro_series IS 'Macro observations: BI_RATE (Bank Indonesia policy rate, %), SBN_10Y (10-year government bond yield, %). USD/IDR comes from fx_rate_daily.';

CREATE TABLE IF NOT EXISTS indicator_snapshot (
    company_id      BIGINT PRIMARY KEY REFERENCES company(company_id),
    as_of_date      DATE NOT NULL,
    schema_version  INTEGER NOT NULL,
    snapshot_json   JSONB NOT NULL,
    input_hash      VARCHAR(64) NOT NULL,
    computed_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE indicator_snapshot IS 'Latest technical snapshot per company (docs/indicator-snapshot.md). input_hash: hash of the bars, index bars, events and settings it was computed from; recomputed when it changes.';

CREATE TABLE IF NOT EXISTS chart_setting (
    user_id         BIGINT PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
    settings_json   JSONB NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE chart_setting IS 'Last chart settings of a user on the Technicals page (ticker, interval, range, indicators).';
