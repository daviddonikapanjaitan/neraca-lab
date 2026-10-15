-- =====================================================================
-- Neraca Lab - Forex / commodity prices and macro data (PostgreSQL 15+)
--
--   market_instrument    the Forex pairs and commodities that can be ingested and charted beside the
--                        listings of an exchange (Ingestion › Price Ingestion, Companies › Technicals);
--                        the catalog is seeded by V1.0.26__data_markets_macro.sql
--   instrument_daily     their bars: daily (Yahoo Finance) or monthly (World Bank Pink Sheet: one bar
--                        per month, open = high = low = close = the monthly average, dated on the 1st)
--   macro_series_def     the macro series of the Druckenmiller and Paul Tudor Jones views (macro.md):
--                        source, unit, frequency, publication lag and sanity range; seeded by
--                        V1.0.26__data_markets_macro.sql
--   macro_series         (V1.0.23) gains the period an observation describes, the day it became public
--                        (release_date: the snapshot never uses a value before it), its revision count
--                        and when it was last written
--
-- Executed on every application start by Spring SQL init. Every statement is idempotent.
-- =====================================================================

CREATE TABLE IF NOT EXISTS market_instrument (
    instrument_code     VARCHAR(20) PRIMARY KEY,
    instrument_id       SMALLSERIAL NOT NULL,
    market              VARCHAR(20) NOT NULL,
    name                VARCHAR(100) NOT NULL,
    source              VARCHAR(20) NOT NULL,
    source_symbol       VARCHAR(100) NOT NULL,
    quote_currency      CHAR(3) NOT NULL,
    unit                VARCHAR(40) NOT NULL,
    frequency           VARCHAR(10) NOT NULL,
    tick_size           NUMERIC(20,8) NOT NULL,
    max_spike           NUMERIC(6,4),
    history_from        DATE NOT NULL,
    sort_order          INTEGER NOT NULL DEFAULT 0,
    active              BOOLEAN NOT NULL DEFAULT TRUE,
    description         TEXT,

    CONSTRAINT uq_market_instrument_id UNIQUE (instrument_id),
    CONSTRAINT ck_market_instrument_market CHECK (market IN ('FOREX', 'COMMODITY')),
    CONSTRAINT ck_market_instrument_source CHECK (source IN ('yahoo', 'worldbank')),
    CONSTRAINT ck_market_instrument_frequency CHECK (frequency IN ('DAILY', 'MONTHLY')),
    CONSTRAINT ck_market_instrument_tick CHECK (tick_size > 0),
    CONSTRAINT ck_market_instrument_spike CHECK (max_spike IS NULL OR max_spike > 0),
    CONSTRAINT ck_market_instrument_code CHECK (instrument_code ~ '^[A-Z0-9_]+$')
);

COMMENT ON TABLE market_instrument IS 'Forex pairs and commodities that can be ingested (Price Ingestion) and charted (Technicals). source_symbol: Yahoo Finance symbol, or the World Bank Pink Sheet column. tick_size: rounding of chart levels and preset prices. max_spike: a daily close that jumps more than this fraction away from the bars on both sides and comes back (a bad print of the source) is not stored, and a high / low further than it from the open and close is cut back (NULL: no cleaning).';

CREATE TABLE IF NOT EXISTS instrument_daily (
    instrument_daily_id BIGSERIAL PRIMARY KEY,
    instrument_code     VARCHAR(20) NOT NULL REFERENCES market_instrument(instrument_code),
    trading_date        DATE NOT NULL,
    open_price          NUMERIC(20,8),
    high_price          NUMERIC(20,8),
    low_price           NUMERIC(20,8),
    close_price         NUMERIC(20,8) NOT NULL,
    volume              BIGINT,
    source              VARCHAR(20) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_instrument_daily UNIQUE (instrument_code, trading_date),
    CONSTRAINT ck_instrument_daily_close CHECK (close_price > 0)
);

COMMENT ON TABLE instrument_daily IS 'Bars of a Forex pair or commodity (market_instrument), in its quote currency and unit. A monthly instrument has one bar per month dated on the 1st (the monthly average).';

-- ---------------------------------------------------------------------
-- macro data
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS macro_series_def (
    series_code         VARCHAR(20) PRIMARY KEY,
    name                VARCHAR(120) NOT NULL,
    country             VARCHAR(10) NOT NULL,
    category            VARCHAR(20) NOT NULL,
    unit                VARCHAR(40) NOT NULL,
    frequency           VARCHAR(10) NOT NULL,
    source              VARCHAR(20) NOT NULL,
    source_ref          VARCHAR(200),
    source_url          TEXT,
    release_lag_days    INTEGER NOT NULL DEFAULT 0,
    priority            SMALLINT NOT NULL DEFAULT 2,
    used_by             VARCHAR(60) NOT NULL,
    min_value           NUMERIC(24,8) NOT NULL,
    max_value           NUMERIC(24,8) NOT NULL,
    max_change          NUMERIC(24,8),
    history_from        DATE NOT NULL DEFAULT DATE '2000-01-01',
    sort_order          INTEGER NOT NULL DEFAULT 0,
    active              BOOLEAN NOT NULL DEFAULT TRUE,
    description         TEXT,

    CONSTRAINT ck_macro_series_def_frequency CHECK (frequency IN ('DAILY', 'WEEKLY', 'MONTHLY', 'QUARTERLY', 'EVENT')),
    CONSTRAINT ck_macro_series_def_source CHECK (source IN ('fred', 'yahoo', 'bi', 'bi_seki', 'cnbc', 'cftc', 'manual')),
    CONSTRAINT ck_macro_series_def_priority CHECK (priority BETWEEN 1 AND 3),
    CONSTRAINT ck_macro_series_def_range CHECK (min_value < max_value),
    CONSTRAINT ck_macro_series_def_lag CHECK (release_lag_days >= 0),
    CONSTRAINT ck_macro_series_def_code CHECK (series_code ~ '^[A-Z0-9_]+$')
);

COMMENT ON TABLE macro_series_def IS 'Macro series of the Druckenmiller and Paul Tudor Jones views (macro.md). source_ref: FRED id, Yahoo symbol, CNBC symbol, CFTC contract code, BI page or SEKI table#row. release_lag_days: days after the end of the period the value is public (point in time). min_value / max_value: a value outside is rejected; max_change: a larger move between two observations is reported as a warning.';

ALTER TABLE macro_series
    ADD COLUMN IF NOT EXISTS period_end   DATE,
    ADD COLUMN IF NOT EXISTS release_date DATE,
    ADD COLUMN IF NOT EXISTS revision     INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS updated_at   TIMESTAMPTZ NOT NULL DEFAULT now();

COMMENT ON TABLE macro_series IS 'Macro observations (macro_series_def). obs_date: the observation date (the 1st of the month for a monthly series, the decision date of the BI rate); period_end: the last day of the period it describes; release_date: the day it became public (the snapshot only uses values released by its date); revision: how often the source changed the value. USD/IDR comes from fx_rate_daily.';

CREATE INDEX IF NOT EXISTS ix_macro_series_code_release ON macro_series (series_code, release_date);
