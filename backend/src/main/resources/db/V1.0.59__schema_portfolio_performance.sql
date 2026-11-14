-- =====================================================================
-- Neraca Lab - Portfolio > Performance (instruction/Position Portfolio.xlsx, sheet tracking_performance)
--
--   portfolio_performance_year  : one row per user and year, as the sheet's rows "2022-2023" ... "2026-2027": the
--                                 capital at the start of the year ("Total Invest <year before>" = the market value
--                                 + cash at the end of the year before), the money put in during the year ("Total
--                                 Invest <year>"), the dividends received and the year-end figures the sheet recorded
--                                 (cash, market value, average value, IHSG / LQ45 YTD). Typed by the user.
--   portfolio_performance_daily : one snapshot per user and IDX trading day, saved at or after 17:00 WIB (the end of
--                                 the session, PortfolioPerformanceSchedule): market value, average value, cash, the
--                                 profit or loss (of the stocks against their average value, and of the year as the
--                                 sheet computes it) and the IHSG / LQ45 close with their change of the day and of the
--                                 year. Written by the schedule or the page's "Save today", never edited.
--
-- The sheet's formulas: POT G/L = market value + cash - start capital - new investment; POT G/L % = POT G/L / start
-- capital; Deviden % = dividend / (market value + cash). Amounts in IDR.
-- Executed on every application start by Spring SQL init, after V1.0.54. Every statement is idempotent.
-- =====================================================================

CREATE TABLE IF NOT EXISTS portfolio_performance_year (
    user_id         BIGINT NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    year            SMALLINT NOT NULL CHECK (year BETWEEN 2000 AND 2100),
    start_capital   NUMERIC(20, 2) CHECK (start_capital IS NULL OR start_capital >= 0),
    new_investment  NUMERIC(20, 2) NOT NULL DEFAULT 0,
    dividend        NUMERIC(20, 2) CHECK (dividend IS NULL OR dividend >= 0),
    cash            NUMERIC(20, 2) CHECK (cash IS NULL OR cash >= 0),
    market_value    NUMERIC(20, 2) CHECK (market_value IS NULL OR market_value >= 0),
    average_value   NUMERIC(20, 2) CHECK (average_value IS NULL OR average_value >= 0),
    ihsg_ytd        NUMERIC(12, 6),
    lq45_ytd        NUMERIC(12, 6),
    note            VARCHAR(500),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, year)
);

COMMENT ON TABLE portfolio_performance_year IS 'Portfolio > Performance: a user''s year as the sheet tracking_performance records it (V1.0.59), IDR.';
COMMENT ON COLUMN portfolio_performance_year.start_capital IS 'Capital at the start of the year ("Total Invest <year before>"): market value + cash at the end of the year before. Null: taken from the year before.';
COMMENT ON COLUMN portfolio_performance_year.new_investment IS 'Money put in during the year ("Total Invest <year>"); negative for a withdrawal.';
COMMENT ON COLUMN portfolio_performance_year.dividend IS 'Cash dividends received during the year ("Deviden").';
COMMENT ON COLUMN portfolio_performance_year.cash IS 'Cash at the end of the year as recorded; the year''s last daily snapshot takes precedence.';
COMMENT ON COLUMN portfolio_performance_year.market_value IS 'Market value of the stocks at the end of the year as recorded; the year''s last daily snapshot takes precedence.';
COMMENT ON COLUMN portfolio_performance_year.average_value IS 'Average (cost) value of the stocks at the end of the year as recorded.';
COMMENT ON COLUMN portfolio_performance_year.ihsg_ytd IS 'IHSG change of the year as the sheet recorded it (0.2192 = +21.92%).';
COMMENT ON COLUMN portfolio_performance_year.lq45_ytd IS 'LQ45 change of the year as the sheet recorded it.';

CREATE TABLE IF NOT EXISTS portfolio_performance_daily (
    user_id             BIGINT NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    snapshot_date       DATE NOT NULL,
    captured_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    source              VARCHAR(10) NOT NULL CHECK (source IN ('SCHEDULE', 'MANUAL')),
    positions           INTEGER NOT NULL,
    priced              INTEGER NOT NULL,
    priced_today        INTEGER NOT NULL,
    cash                NUMERIC(20, 2) NOT NULL,
    market_value        NUMERIC(20, 2) NOT NULL,
    average_value       NUMERIC(20, 2) NOT NULL,
    profit_loss         NUMERIC(20, 2) NOT NULL,
    profit_loss_pct     NUMERIC(12, 6),
    day_gain            NUMERIC(20, 2),
    day_gain_pct        NUMERIC(12, 6),
    total_investment    NUMERIC(20, 2) NOT NULL,
    year_start_capital  NUMERIC(20, 2),
    year_new_investment NUMERIC(20, 2) NOT NULL DEFAULT 0,
    ytd_profit          NUMERIC(20, 2),
    ytd_profit_pct      NUMERIC(12, 6),
    ihsg_close          NUMERIC(20, 8),
    ihsg_date           DATE,
    ihsg_change_pct     NUMERIC(12, 6),
    ihsg_ytd_pct        NUMERIC(12, 6),
    lq45_close          NUMERIC(20, 8),
    lq45_date           DATE,
    lq45_change_pct     NUMERIC(12, 6),
    lq45_ytd_pct        NUMERIC(12, 6),
    PRIMARY KEY (user_id, snapshot_date)
);

COMMENT ON TABLE portfolio_performance_daily IS 'Portfolio > Performance: a user''s portfolio and IHSG / LQ45 at the end of an IDX trading day, saved at or after 17:00 WIB (V1.0.59).';
COMMENT ON COLUMN portfolio_performance_daily.priced_today IS 'Positions whose price is of snapshot_date (the others kept an older price).';
COMMENT ON COLUMN portfolio_performance_daily.profit_loss IS 'market_value - average_value: the stocks'' gain or loss against their average buy price.';
COMMENT ON COLUMN portfolio_performance_daily.profit_loss_pct IS 'profit_loss / average_value.';
COMMENT ON COLUMN portfolio_performance_daily.ytd_profit IS 'The sheet''s POT G/L of the year so far: market_value + cash - year_start_capital - year_new_investment.';
COMMENT ON COLUMN portfolio_performance_daily.ytd_profit_pct IS 'ytd_profit / year_start_capital (the sheet''s POT G/L %).';
COMMENT ON COLUMN portfolio_performance_daily.ihsg_change_pct IS 'IHSG close of ihsg_date / the close of the trading day before - 1.';
COMMENT ON COLUMN portfolio_performance_daily.ihsg_ytd_pct IS 'IHSG close of ihsg_date / the last close of the year before - 1.';
