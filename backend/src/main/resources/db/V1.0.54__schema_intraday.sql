-- =====================================================================
-- Neraca Lab - intraday prices of the stocks held in Portfolio > Position (IntradayService)
--
--   intraday_quote : the latest quote of a company in its latest IDX session (Yahoo Finance chart meta): last price,
--                    previous close, day high / low, volume, the time of the last trade, when it was fetched
--   intraday_bar   : the session's 5-minute bars (open, high, low, close, volume), for the chart of the day
--
-- Written every few minutes during the IDX session by the intraday schedule, in the listing currency (IDR). Kept apart
-- from price_daily (the daily history the Fundamentals and Technicals read is never touched) and from the portfolio
-- tables (no position is written). Bars older than neracalab.intraday.keep-days are deleted.
-- Executed on every application start by Spring SQL init, after V1.0.53. Every statement is idempotent.
-- =====================================================================

CREATE TABLE IF NOT EXISTS intraday_quote (
    company_id      BIGINT PRIMARY KEY REFERENCES company (company_id) ON DELETE CASCADE,
    session_date    DATE NOT NULL,
    price           NUMERIC(20, 4) NOT NULL CHECK (price > 0),
    previous_close  NUMERIC(20, 4),
    day_open        NUMERIC(20, 4),
    day_high        NUMERIC(20, 4),
    day_low         NUMERIC(20, 4),
    volume          BIGINT,
    market_time     TIMESTAMPTZ NOT NULL,
    session_start   TIMESTAMPTZ,
    session_end     TIMESTAMPTZ,
    source          VARCHAR(20) NOT NULL DEFAULT 'yahoo',
    fetched_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE intraday_quote IS 'Latest intraday quote of a stock held in a portfolio (V1.0.54): its latest IDX session, listing currency.';
COMMENT ON COLUMN intraday_quote.session_date IS 'Trading day of the quote (Asia/Jakarta).';
COMMENT ON COLUMN intraday_quote.previous_close IS 'Close of the session before (Yahoo chartPreviousClose): the base of the day''s change.';
COMMENT ON COLUMN intraday_quote.market_time IS 'Time of the last trade in the price (Yahoo regularMarketTime).';

CREATE TABLE IF NOT EXISTS intraday_bar (
    company_id    BIGINT NOT NULL REFERENCES company (company_id) ON DELETE CASCADE,
    bar_time      TIMESTAMPTZ NOT NULL,
    session_date  DATE NOT NULL,
    open_price    NUMERIC(20, 4),
    high_price    NUMERIC(20, 4),
    low_price     NUMERIC(20, 4),
    close_price   NUMERIC(20, 4) NOT NULL,
    volume        BIGINT,
    PRIMARY KEY (company_id, bar_time)
);

CREATE INDEX IF NOT EXISTS idx_intraday_bar_session ON intraday_bar (company_id, session_date);

COMMENT ON TABLE intraday_bar IS 'Intraday bars (5 minutes) of a stock held in a portfolio (V1.0.54), listing currency; bar_time = the bar''s start.';
