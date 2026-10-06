-- =====================================================================
-- Neraca Lab - daily exchange rates (PostgreSQL 15+)
--
--   fx_rate_daily   reference rate of one currency pair per day: rate = quote_currency units per
--                   1 base_currency unit (USD/IDR 17,913 = base USD, quote IDR); source 'ecb' =
--                   European Central Bank reference rates via the Frankfurter API
--
-- price_daily is in company.currency. A company that reports in another currency than its
-- listing trades in (INDY: quoted in IDR on IDX, reports in USD) gets its prices converted by the
-- price ingestion: listing price / rate of the last FX day before the trading day. The rates used
-- are stored here, so every converted price can be reproduced.
-- (Yahoo's USDIDR=X is not used: its history has days off by a factor of 10 and weeks of a stale
-- value, e.g. 888.11 on 2010-11-01, 9,612.45 through Oct-Dec 2013.)
--
-- Executed on every application start by Spring SQL init. Every statement is idempotent.
-- =====================================================================

CREATE TABLE IF NOT EXISTS fx_rate_daily (
    fx_rate_id      BIGSERIAL PRIMARY KEY,
    base_currency   CHAR(3) NOT NULL,
    quote_currency  CHAR(3) NOT NULL,
    rate_date       DATE NOT NULL,
    rate            NUMERIC(20,8) NOT NULL,
    source          VARCHAR(20) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_fx_rate_daily UNIQUE (base_currency, quote_currency, rate_date),
    CONSTRAINT ck_fx_rate_daily_positive CHECK (rate > 0),
    CONSTRAINT ck_fx_rate_daily_currencies CHECK (base_currency ~ '^[A-Z]{3}$' AND quote_currency ~ '^[A-Z]{3}$'
                                                  AND base_currency <> quote_currency)
);

COMMENT ON TABLE  fx_rate_daily IS 'Daily reference exchange rates used to convert listing prices into the reporting currency.';
COMMENT ON COLUMN fx_rate_daily.rate IS 'quote_currency units per 1 base_currency unit on rate_date.';
COMMENT ON COLUMN fx_rate_daily.source IS 'Rate source: ecb (European Central Bank reference rates via Frankfurter).';
