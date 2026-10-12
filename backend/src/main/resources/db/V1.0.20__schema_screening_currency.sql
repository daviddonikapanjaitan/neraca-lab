-- =====================================================================
-- Neraca Lab - currency of the screening fundamentals (PostgreSQL 15+)
--
--   fundamental_snapshot.financial_currency : the currency Yahoo Finance reports the fundamentals of the
--                      row in (financialData.financialCurrency). The market data of the row (price,
--                      market_cap, eps_ttm, trailing_pe) is in the listing currency (stock_listing.currency).
--
-- A company that reports in another currency than its listing trades in (INDY: quoted in IDR, reports
-- in USD) has mixed figures in a row: book_value_per_share, the TTM amounts, total_cash / total_debt and
-- the annual figures are in the financial currency, and Yahoo's price_to_book, enterprise_value and
-- ev_to_ebitda divide or add the two as if they were one (INDY: price 2,610 IDR / book value 0.231 USD =
-- 11,298.7). The screening metrics convert with the rate of fx_rate_daily (V1.0.11) instead.
--
-- NULL on rows whose fundamentals were fetched before this column existed: the reporting currency of
-- the company (company.currency), when it is in the companies table, is used until the next refresh.
--
-- Executed on every application start by Spring SQL init, after V1.0.19. Every statement is idempotent.
-- =====================================================================

ALTER TABLE fundamental_snapshot
    ADD COLUMN IF NOT EXISTS financial_currency CHAR(3);

COMMENT ON COLUMN fundamental_snapshot.financial_currency IS 'Currency of the fundamentals (book value per share, TTM amounts, cash, debt, annual figures); the market data is in stock_listing.currency.';
