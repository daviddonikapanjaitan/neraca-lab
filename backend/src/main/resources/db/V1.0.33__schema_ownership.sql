-- =====================================================================
-- Neraca Lab - KSEI's daily lists of the shareholders of more than 5% (Ingestion > Excel Documents (RAG),
-- Companies > Ownership (>=5%))
--
--   ownership_report   : one list per day (KEPEMILIKAN EFEK DIATAS 5% BERDASARKAN SID (PUBLIK), from idx.co.id, e.g.
--                        peng-2026-10-27-00104-lima-persen.xlsx): its date and the date it is compared with (the
--                        trading day before); uploading the list of a day again replaces it
--   ownership_holder   : each investor (SID) holding more than 5% of a listed company on either date: combined shares
--                        and percentage on both dates, the movement (BUY, SELL, UNCHANGED, ENTERED: crossed 5%,
--                        EXITED); an investor listed again under another name with the same accounts is an alias
--   ownership_account  : each securities account of an investor (broker or custodian, account name, shares on both
--                        dates)
--   v_ownership_movement : the investors of every list, and those that left it: an investor of the list of the day
--                        before (stored) that is not in the day's list fell below 5% (EXITED); KSEI does not list them
--   rag_document       : source type XLSX: per stored company one document of its holders of 5% or more and their
--                        changes over every stored day (source_key OWNERSHIP_5_PERCENT), for the AI analysis
--
-- The job type RAG_XLSX is listed in ck_ingestion_job_type by V1.0.10 and V1.0.14 (and in ck_ingestion_job_file by
-- V1.0.14), which re-create them on every start. Executed on every application start by Spring SQL init, after
-- V1.0.32. Every statement is idempotent.
-- =====================================================================

-- ---------------------------------------------------------------------
-- rag_document : the Excel documents of a company
-- ---------------------------------------------------------------------
ALTER TABLE rag_document
    DROP CONSTRAINT IF EXISTS ck_rag_document_source_type,
    ADD  CONSTRAINT ck_rag_document_source_type CHECK (source_type IN ('PDF', 'NEWS', 'XLSX'));

COMMENT ON COLUMN rag_document.source_key IS 'PDF: SHA-256 of the file (one document per file and company or country); NEWS: the article URL; XLSX: the Excel document type (OWNERSHIP_5_PERCENT: one document per company, rebuilt from every stored list).';

-- ---------------------------------------------------------------------
-- ownership_report
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ownership_report (
    report_id               BIGSERIAL PRIMARY KEY,

    exchange                VARCHAR(10) NOT NULL,
    document_type           VARCHAR(30) NOT NULL,
    -- the list's date, and the date its first columns hold (the trading day before)
    report_date             DATE NOT NULL,
    previous_date           DATE NOT NULL,
    title                   VARCHAR(500),
    -- the announcement number in the file name (peng-2026-10-27-00104-lima-persen.xlsx -> 00104)
    announcement_number     VARCHAR(30),

    file_id                 BIGINT
        REFERENCES ingestion_file(file_id),
    file_name               VARCHAR(255),

    issuers                 INTEGER NOT NULL,
    holders                 INTEGER NOT NULL,
    accounts                INTEGER NOT NULL,
    aliases                 INTEGER NOT NULL,
    warnings                TEXT,
    notes                   TEXT,

    job_id                  UUID,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_ownership_report UNIQUE (exchange, document_type, report_date),
    CONSTRAINT ck_ownership_report_type CHECK (document_type IN ('OWNERSHIP_5_PERCENT')),
    CONSTRAINT ck_ownership_report_dates CHECK (previous_date < report_date),
    CONSTRAINT ck_ownership_report_counts CHECK (issuers >= 0 AND holders >= 0 AND accounts >= 0 AND aliases >= 0)
);

CREATE INDEX IF NOT EXISTS ix_ownership_report_date ON ownership_report (exchange, document_type, report_date DESC);

COMMENT ON TABLE  ownership_report IS 'KSEI''s list of the shareholders of more than 5% of one day (Kepemilikan Efek Diatas 5% Berdasarkan SID), V1.0.33.';
COMMENT ON COLUMN ownership_report.report_date IS 'The list''s date (its second date columns).';
COMMENT ON COLUMN ownership_report.previous_date IS 'The date of its first columns: the trading day before.';
COMMENT ON COLUMN ownership_report.holders IS 'Investors listed (ownership_holder rows), aliases included.';
COMMENT ON COLUMN ownership_report.aliases IS 'Investors listed again under another name with the same accounts (ownership_holder.alias_of set).';
COMMENT ON COLUMN ownership_report.warnings IS 'What did not add up (accounts that do not sum to the investor''s total, rows not read), one per line; NULL when none.';
COMMENT ON COLUMN ownership_report.notes IS 'Numbers read as written although they may be thousands (no total or percentage decided them), one per line; NULL when none.';

-- ---------------------------------------------------------------------
-- ownership_holder
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ownership_holder (
    holder_id               BIGSERIAL PRIMARY KEY,

    report_id               BIGINT NOT NULL
        REFERENCES ownership_report(report_id) ON DELETE CASCADE,
    -- repeated from the report for filtering without a join
    exchange                VARCHAR(10) NOT NULL,
    report_date             DATE NOT NULL,
    previous_date           DATE NOT NULL,

    ticker                  VARCHAR(20) NOT NULL,
    issuer_name             VARCHAR(300) NOT NULL,
    list_number              INTEGER NOT NULL,
    holder_name             VARCHAR(300) NOT NULL,
    holder_key              VARCHAR(300) NOT NULL,
    alias_of                INTEGER,
    nationality             VARCHAR(100),
    domicile                VARCHAR(100),
    investor_status         CHAR(1),

    shares_previous         BIGINT,
    percent_previous        NUMERIC(9, 4),
    shares_current          BIGINT,
    percent_current         NUMERIC(9, 4),
    change_shares           BIGINT GENERATED ALWAYS AS (shares_current - shares_previous) STORED,
    movement                VARCHAR(10) GENERATED ALWAYS AS (CASE
                                WHEN shares_previous IS NULL THEN 'ENTERED'
                                WHEN shares_current IS NULL THEN 'EXITED'
                                WHEN shares_current > shares_previous THEN 'BUY'
                                WHEN shares_current < shares_previous THEN 'SELL'
                                ELSE 'UNCHANGED' END) STORED,
    accounts                INTEGER NOT NULL,
    account_moves           INTEGER NOT NULL,

    CONSTRAINT uq_ownership_holder UNIQUE (report_id, list_number),
    CONSTRAINT ck_ownership_holder_shares CHECK ((shares_previous IS NULL OR shares_previous >= 0)
        AND (shares_current IS NULL OR shares_current >= 0)
        AND (shares_previous IS NOT NULL OR shares_current IS NOT NULL)),
    CONSTRAINT ck_ownership_holder_status CHECK (investor_status IS NULL OR investor_status IN ('L', 'A')),
    CONSTRAINT ck_ownership_holder_row CHECK (list_number >= 1 AND (alias_of IS NULL OR alias_of >= 1)),
    CONSTRAINT ck_ownership_holder_accounts CHECK (accounts >= 1 AND account_moves >= 0 AND account_moves <= accounts)
);

CREATE INDEX IF NOT EXISTS ix_ownership_holder_ticker ON ownership_holder (exchange, ticker, report_date DESC);
CREATE INDEX IF NOT EXISTS ix_ownership_holder_key ON ownership_holder (holder_key, report_date DESC);
CREATE INDEX IF NOT EXISTS ix_ownership_holder_report ON ownership_holder (report_id, ticker, holder_key);
CREATE INDEX IF NOT EXISTS ix_ownership_holder_date ON ownership_holder (report_date DESC, movement);

COMMENT ON TABLE  ownership_holder IS 'An investor (SID) holding more than 5% of a company in a list of ownership_report, V1.0.33.';
COMMENT ON COLUMN ownership_holder.list_number IS 'Its number in the list (No).';
COMMENT ON COLUMN ownership_holder.holder_name IS 'Nama Pemegang Saham, as listed.';
COMMENT ON COLUMN ownership_holder.holder_key IS 'The name normalized (upper case, letters and digits, without PT / Tbk): the same investor across days and companies.';
COMMENT ON COLUMN ownership_holder.alias_of IS 'list_number of the investor listed before in the same list with the same company, totals and accounts: the same SID under another name; NULL for the first.';
COMMENT ON COLUMN ownership_holder.investor_status IS 'L = local, A = foreign (asing); NULL when not printed.';
COMMENT ON COLUMN ownership_holder.shares_previous IS 'Combined shares on previous_date (Saham Gabungan Per Investor); NULL: not in the list then.';
COMMENT ON COLUMN ownership_holder.percent_previous IS 'Percentage of the shares listed on previous_date, as printed.';
COMMENT ON COLUMN ownership_holder.change_shares IS 'shares_current - shares_previous: bought (+) or sold (-); NULL when either is missing.';
COMMENT ON COLUMN ownership_holder.movement IS 'BUY, SELL, UNCHANGED, ENTERED (crossed 5%: not listed on previous_date) or EXITED (no holding on report_date); investors KSEI leaves out of the next list are EXITED in v_ownership_movement.';
COMMENT ON COLUMN ownership_holder.account_moves IS 'Accounts whose shares changed; with movement UNCHANGED: shares moved between the investor''s accounts.';

-- ---------------------------------------------------------------------
-- ownership_account
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ownership_account (
    account_id              BIGSERIAL PRIMARY KEY,

    holder_id               BIGINT NOT NULL
        REFERENCES ownership_holder(holder_id) ON DELETE CASCADE,
    account_index           INTEGER NOT NULL,
    participant             VARCHAR(200) NOT NULL,
    account_name            VARCHAR(300) NOT NULL,
    nationality             VARCHAR(100),
    domicile                VARCHAR(100),
    investor_status         CHAR(1),
    shares_previous         BIGINT,
    shares_current          BIGINT,
    change_shares           BIGINT GENERATED ALWAYS AS (shares_current - shares_previous) STORED,

    CONSTRAINT uq_ownership_account UNIQUE (holder_id, account_index),
    CONSTRAINT ck_ownership_account_shares CHECK ((shares_previous IS NULL OR shares_previous >= 0)
        AND (shares_current IS NULL OR shares_current >= 0)),
    CONSTRAINT ck_ownership_account_status CHECK (investor_status IS NULL OR investor_status IN ('L', 'A')),
    CONSTRAINT ck_ownership_account_index CHECK (account_index >= 1)
);

COMMENT ON TABLE  ownership_account IS 'A securities account of an investor of ownership_holder, V1.0.33.';
COMMENT ON COLUMN ownership_account.participant IS 'The KSEI participant holding the account: broker or custodian bank (Nama Pemegang Rekening Efek).';
COMMENT ON COLUMN ownership_account.account_name IS 'Nama Rekening Efek, as listed.';
COMMENT ON COLUMN ownership_account.shares_previous IS 'Shares in the account on previous_date (Jumlah Saham); NULL: no holding then (opened since).';
COMMENT ON COLUMN ownership_account.shares_current IS 'Shares in the account on report_date; NULL: no holding (closed).';

-- ---------------------------------------------------------------------
-- v_ownership_movement : every investor of every list, and those that left it
-- ---------------------------------------------------------------------
DROP VIEW IF EXISTS v_ownership_movement;

CREATE VIEW v_ownership_movement AS
SELECT h.holder_id, h.report_id, r.document_type, h.exchange, h.report_date, h.previous_date, h.ticker,
       h.issuer_name, h.list_number, h.holder_name, h.holder_key, h.alias_of, h.nationality, h.domicile,
       h.investor_status, h.shares_previous, h.percent_previous, h.shares_current, h.percent_current,
       h.change_shares, h.movement, h.accounts, h.account_moves, FALSE AS left_list,
       h.report_id AS holder_report_id
FROM ownership_holder h
JOIN ownership_report r ON r.report_id = h.report_id
UNION ALL
-- an investor of the day before that is not in the day's list: below 5% now (KSEI does not list it)
SELECT p.holder_id, r.report_id, r.document_type, r.exchange, r.report_date, r.previous_date, p.ticker,
       p.issuer_name, p.list_number, p.holder_name, p.holder_key, p.alias_of, p.nationality, p.domicile,
       p.investor_status, p.shares_current, p.percent_current, NULL, NULL, NULL, 'EXITED', p.accounts, 0, TRUE,
       p.report_id
FROM ownership_report r
JOIN ownership_report pr ON pr.exchange = r.exchange AND pr.document_type = r.document_type
                        AND pr.report_date = r.previous_date
JOIN ownership_holder p ON p.report_id = pr.report_id AND p.shares_current IS NOT NULL
WHERE NOT EXISTS (SELECT 1 FROM ownership_holder c
                  WHERE c.report_id = r.report_id AND c.ticker = p.ticker AND c.holder_key = p.holder_key);

COMMENT ON VIEW v_ownership_movement IS 'Every investor of every list of ownership_report, and (left_list) each investor of the list of the day before that is not in the day''s list: EXITED, its shares of the day before as shares_previous; holder_id and holder_report_id are then the row of the day before (V1.0.33).';
