-- =====================================================================
-- Neraca Lab - Portfolio > Position (instruction/Position Portfolio.xlsx, sheet Position)
--
--   portfolio          : one row per user: the cash held and the total investment (the capital put in), the two
--                        figures of the sheet that are typed rather than derived
--   portfolio_position : the user's stocks: average buy price, number of shares and the date of the first buy
--                        ("Tanggal Masuk"); one row per company. Everything else of the sheet (price, BVPS, forward
--                        EPS, EV, ...) is computed on request from Companies > Fundamentals, never stored
--
-- Executed on every application start by Spring SQL init, after V1.0.52. Every statement is idempotent.
-- =====================================================================

CREATE TABLE IF NOT EXISTS portfolio (
    user_id           BIGINT PRIMARY KEY REFERENCES users (user_id) ON DELETE CASCADE,
    cash              NUMERIC(20, 2) NOT NULL DEFAULT 0 CHECK (cash >= 0),
    total_investment  NUMERIC(20, 2) NOT NULL DEFAULT 0 CHECK (total_investment >= 0),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE portfolio IS 'Portfolio > Position of a user: cash held and total investment (capital put in), in IDR (V1.0.53).';
COMMENT ON COLUMN portfolio.cash IS 'Cash held in the portfolio (IDR), as the sheet''s "Cash" cell.';
COMMENT ON COLUMN portfolio.total_investment IS 'Capital put into the portfolio (IDR): Total Profit = Market Total + Cash - Total Investment.';

CREATE TABLE IF NOT EXISTS portfolio_position (
    position_id    BIGSERIAL PRIMARY KEY,
    user_id        BIGINT NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    company_id     BIGINT NOT NULL REFERENCES company (company_id),
    average_price  NUMERIC(20, 4) NOT NULL CHECK (average_price > 0),
    shares         NUMERIC(20, 0) NOT NULL CHECK (shares > 0),
    date_in        DATE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_portfolio_position UNIQUE (user_id, company_id)
);

CREATE INDEX IF NOT EXISTS idx_portfolio_position_user ON portfolio_position (user_id);

COMMENT ON TABLE portfolio_position IS 'A stock of a user''s portfolio (Portfolio > Position, V1.0.53): one row per company.';
COMMENT ON COLUMN portfolio_position.average_price IS 'Average buy price per share in the listing currency (IDR).';
COMMENT ON COLUMN portfolio_position.shares IS 'Number of shares held (not lots).';
COMMENT ON COLUMN portfolio_position.date_in IS 'Date of the first buy (the sheet''s "Tanggal Masuk").';
