-- =====================================================================
-- Neraca Lab - fundamental analysis schema (PostgreSQL 15+)
--
-- Executed on every application start by Spring SQL init
-- (spring.sql.init.*, see application.yaml). No Flyway.
-- Every statement is idempotent: safe to run again and again.
--
-- Conventions
--   * Amounts are stored in full units of company.currency (no thousands / millions).
--   * Income statement : revenue and income positive, expenses POSITIVE
--                        (income_tax positive = tax expense, negative = tax benefit).
--   * Cash flow        : signed as cash moves, inflow positive, outflow NEGATIVE
--                        (capital_expenditure, dividends_paid, debt_repaid ... are negative).
--   * NULL means "not reported / not available"; 0 means "reported as zero".
-- =====================================================================

-- ---------------------------------------------------------------------
-- company
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS company (
    company_id          BIGSERIAL PRIMARY KEY,
    ticker              VARCHAR(20) NOT NULL,
    exchange            VARCHAR(50),
    cik                 VARCHAR(20),
    company_name        VARCHAR(255) NOT NULL,
    legal_name          VARCHAR(255),
    industry            VARCHAR(150),
    sector              VARCHAR(150),
    country             VARCHAR(100),
    currency            CHAR(3),
    fiscal_year_end     DATE,
    ipo_date            DATE,
    active              BOOLEAN NOT NULL DEFAULT TRUE,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_company_ticker_exchange UNIQUE NULLS NOT DISTINCT (ticker, exchange),
    CONSTRAINT ck_company_currency CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$')
);

COMMENT ON TABLE  company IS 'Listed company master data.';
COMMENT ON COLUMN company.cik IS 'SEC Central Index Key (US filers only).';
COMMENT ON COLUMN company.currency IS 'ISO 4217 reporting currency of the financial statements.';
COMMENT ON COLUMN company.fiscal_year_end IS 'Most recent fiscal year end date; its month/day define the fiscal calendar.';

-- ---------------------------------------------------------------------
-- reporting_period
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS reporting_period (
    period_id           BIGSERIAL PRIMARY KEY,

    company_id          BIGINT NOT NULL
        REFERENCES company(company_id),

    fiscal_year         INTEGER NOT NULL,

    fiscal_quarter      SMALLINT,

    period_type         VARCHAR(20) NOT NULL,

    period_start        DATE,
    period_end          DATE NOT NULL,

    filing_date         DATE,
    source_filing       VARCHAR(100),
    audited             BOOLEAN,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_reporting_period
        UNIQUE NULLS NOT DISTINCT (company_id, fiscal_year, fiscal_quarter, period_type),
    -- target of the composite FKs below: a statement row can never point to
    -- a period that belongs to another company
    CONSTRAINT uq_reporting_period_company UNIQUE (period_id, company_id),
    CONSTRAINT ck_reporting_period_type
        CHECK (period_type IN ('FY', 'Q1', 'Q2', 'Q3', 'Q4', 'H1', '9M', 'TTM')),
    CONSTRAINT ck_reporting_period_quarter
        CHECK (fiscal_quarter IS NULL OR fiscal_quarter BETWEEN 1 AND 4),
    CONSTRAINT ck_reporting_period_dates
        CHECK (period_start IS NULL OR period_start <= period_end)
);

CREATE INDEX IF NOT EXISTS ix_reporting_period_company_end
    ON reporting_period (company_id, period_end);

COMMENT ON TABLE  reporting_period IS 'One row per company per reported period.';
COMMENT ON COLUMN reporting_period.period_type IS
    'FY = full fiscal year; Q1..Q4 = single 3-month quarter; H1 = 6-month year-to-date; 9M = 9-month year-to-date; TTM = trailing twelve months. IDX "Kuartal II" filings are 6-month YTD, so they are stored as H1 with fiscal_quarter = 2.';
COMMENT ON COLUMN reporting_period.fiscal_quarter IS
    'Quarter in which the period ends (1-4); NULL for FY.';
COMMENT ON COLUMN reporting_period.source_filing IS 'Source document, e.g. the IDX XBRL workbook file name.';
COMMENT ON COLUMN reporting_period.audited IS 'TRUE audited, FALSE unaudited, NULL unknown.';

-- ---------------------------------------------------------------------
-- income_statement
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS income_statement (
    income_statement_id BIGSERIAL PRIMARY KEY,

    company_id          BIGINT NOT NULL
        REFERENCES company(company_id),

    period_id           BIGINT NOT NULL
        REFERENCES reporting_period(period_id),

    revenue             NUMERIC(24,4),
    cost_of_revenue     NUMERIC(24,4),
    gross_profit        NUMERIC(24,4),

    operating_expenses  NUMERIC(24,4),
    sga_expense         NUMERIC(24,4),
    rd_expense          NUMERIC(24,4),

    depreciation        NUMERIC(24,4),
    amortization        NUMERIC(24,4),

    operating_income    NUMERIC(24,4),
    ebit                NUMERIC(24,4),
    ebitda              NUMERIC(24,4),

    interest_income     NUMERIC(24,4),
    interest_expense    NUMERIC(24,4),

    pretax_income       NUMERIC(24,4),
    income_tax          NUMERIC(24,4),

    net_income          NUMERIC(24,4),
    net_income_to_parent NUMERIC(24,4),

    basic_eps           NUMERIC(20,8),
    diluted_eps         NUMERIC(20,8),

    basic_shares        NUMERIC(24,8),
    diluted_shares      NUMERIC(24,8),

    created_at          TIMESTAMPTZ DEFAULT now(),

    CONSTRAINT uq_income_statement UNIQUE (company_id, period_id),
    CONSTRAINT fk_income_statement_period_company
        FOREIGN KEY (period_id, company_id)
        REFERENCES reporting_period (period_id, company_id)
);

COMMENT ON COLUMN income_statement.operating_expenses IS 'All operating expenses below gross profit (selling, G&A, R&D, other operating).';
COMMENT ON COLUMN income_statement.depreciation IS 'Depreciation of PP&E plus right-of-use assets for the period.';
COMMENT ON COLUMN income_statement.income_tax IS 'Positive = tax expense, negative = tax benefit.';
COMMENT ON COLUMN income_statement.net_income IS 'Profit for the period, including non-controlling interests.';
COMMENT ON COLUMN income_statement.net_income_to_parent IS 'Profit attributable to owners of the parent (EPS numerator).';
COMMENT ON COLUMN income_statement.basic_shares IS 'Weighted average shares used for basic EPS.';

-- ---------------------------------------------------------------------
-- balance_sheet
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS balance_sheet (
    balance_sheet_id    BIGSERIAL PRIMARY KEY,

    company_id          BIGINT NOT NULL
        REFERENCES company(company_id),

    period_id           BIGINT NOT NULL
        REFERENCES reporting_period(period_id),

    cash_and_equivalents NUMERIC(24,4),
    marketable_securities NUMERIC(24,4),

    accounts_receivable NUMERIC(24,4),
    inventory           NUMERIC(24,4),

    current_assets      NUMERIC(24,4),
    total_assets        NUMERIC(24,4),

    accounts_payable    NUMERIC(24,4),
    deferred_revenue    NUMERIC(24,4),

    current_liabilities NUMERIC(24,4),
    total_liabilities   NUMERIC(24,4),

    short_term_debt     NUMERIC(24,4),
    long_term_debt      NUMERIC(24,4),

    lease_liabilities   NUMERIC(24,4),

    shareholders_equity NUMERIC(24,4),
    non_controlling_interest NUMERIC(24,4),
    total_equity        NUMERIC(24,4),

    retained_earnings   NUMERIC(24,4),

    goodwill            NUMERIC(24,4),
    intangible_assets   NUMERIC(24,4),

    shares_outstanding  NUMERIC(24,8),

    created_at          TIMESTAMPTZ DEFAULT now(),

    CONSTRAINT uq_balance_sheet UNIQUE (company_id, period_id),
    CONSTRAINT fk_balance_sheet_period_company
        FOREIGN KEY (period_id, company_id)
        REFERENCES reporting_period (period_id, company_id)
);

COMMENT ON TABLE  balance_sheet IS 'Statement of financial position at reporting_period.period_end.';
COMMENT ON COLUMN balance_sheet.deferred_revenue IS 'Contract liabilities / advances received from customers.';
COMMENT ON COLUMN balance_sheet.short_term_debt IS 'Interest-bearing debt due within 12 months (incl. current maturities), excl. leases.';
COMMENT ON COLUMN balance_sheet.long_term_debt IS 'Interest-bearing debt due after 12 months (bank loans, bonds, financing payables), excl. leases.';
COMMENT ON COLUMN balance_sheet.lease_liabilities IS 'Current + non-current lease liabilities.';
COMMENT ON COLUMN balance_sheet.shareholders_equity IS 'Equity attributable to owners of the parent.';
COMMENT ON COLUMN balance_sheet.total_equity IS 'shareholders_equity + non_controlling_interest.';
COMMENT ON COLUMN balance_sheet.retained_earnings IS 'Appropriated + unappropriated retained earnings.';
COMMENT ON COLUMN balance_sheet.shares_outstanding IS 'Issued shares less treasury shares at period end.';

-- ---------------------------------------------------------------------
-- cash_flow_statement
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS cash_flow_statement (
    cash_flow_id        BIGSERIAL PRIMARY KEY,

    company_id          BIGINT NOT NULL
        REFERENCES company(company_id),

    period_id           BIGINT NOT NULL
        REFERENCES reporting_period(period_id),

    operating_cash_flow NUMERIC(24,4),

    capital_expenditure NUMERIC(24,4),

    investing_cash_flow NUMERIC(24,4),

    financing_cash_flow NUMERIC(24,4),

    acquisitions        NUMERIC(24,4),

    share_buybacks      NUMERIC(24,4),

    stock_issuance      NUMERIC(24,4),

    dividends_paid      NUMERIC(24,4),

    debt_issued         NUMERIC(24,4),

    debt_repaid         NUMERIC(24,4),

    lease_payments      NUMERIC(24,4),

    cash_change         NUMERIC(24,4),

    ending_cash         NUMERIC(24,4),

    created_at          TIMESTAMPTZ DEFAULT now(),

    CONSTRAINT uq_cash_flow_statement UNIQUE (company_id, period_id),
    CONSTRAINT fk_cash_flow_statement_period_company
        FOREIGN KEY (period_id, company_id)
        REFERENCES reporting_period (period_id, company_id)
);

COMMENT ON TABLE  cash_flow_statement IS 'Signed as cash moves: inflow positive, outflow negative.';
COMMENT ON COLUMN cash_flow_statement.capital_expenditure IS 'Purchases of PP&E and intangibles incl. advances for PP&E (negative).';
COMMENT ON COLUMN cash_flow_statement.debt_issued IS 'Proceeds from bank loans, bonds and financing payables (positive).';
COMMENT ON COLUMN cash_flow_statement.debt_repaid IS 'Repayments of bank loans, bonds and financing payables (negative), excl. leases.';
COMMENT ON COLUMN cash_flow_statement.lease_payments IS 'Principal payments of lease liabilities (negative).';

-- ---------------------------------------------------------------------
-- corporate_action
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS corporate_action (
    corporate_action_id  BIGSERIAL PRIMARY KEY,

    company_id           BIGINT NOT NULL
        REFERENCES company(company_id),

    action_date          DATE NOT NULL,

    action_type          VARCHAR(50) NOT NULL,

    ratio_from            NUMERIC(20,8),

    ratio_to              NUMERIC(20,8),

    shares_issued         NUMERIC(24,8),

    cash_raised           NUMERIC(28,4),

    description           TEXT,

    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_corporate_action_type CHECK (action_type IN (
        'IPO', 'STOCK_SPLIT', 'REVERSE_SPLIT', 'STOCK_DIVIDEND', 'BONUS_SHARES',
        'RIGHTS_ISSUE', 'PRIVATE_PLACEMENT', 'SHARE_BUYBACK', 'CASH_DIVIDEND',
        'BOND_ISSUANCE', 'MERGER', 'ACQUISITION', 'SPIN_OFF', 'DELISTING', 'OTHER'))
);

CREATE INDEX IF NOT EXISTS ix_corporate_action_company_date
    ON corporate_action (company_id, action_date);

COMMENT ON COLUMN corporate_action.ratio_from IS 'Old shares in a split / rights ratio, e.g. 1 in a 1:5 split.';
COMMENT ON COLUMN corporate_action.ratio_to IS 'New shares in a split / rights ratio, e.g. 5 in a 1:5 split.';

-- Segments, market data, share counts, metrics and valuation tables: V1.0.2__schema_market_valuation.sql
