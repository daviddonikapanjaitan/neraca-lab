-- =====================================================================
-- Neraca Lab - KSEI's monthly lists of the shareholders of more than 1% (Ingestion > Excel Documents (RAG),
-- Companies > Ownership (>=1%))
--
--   ownership_one_report : one list per month (the shareholders of more than 1% of every listed company at the end of
--                          a month, from idx.co.id, e.g. peng-2026-09-00024-satu-persen.xlsx): its date (the month's
--                          last trading day, its DATE column) and the stored list it is compared with (the latest one
--                          before it); uploading the list of a date again replaces it
--   ownership_one_holder : each investor holding more than 1% of a company on the list's date: its scripless and scrip
--                          shares, total and percentage, its type (individual, corporate, mutual fund, ...), local or
--                          foreign; the rows KSEI prints for the same investor (an SID and a scrip account, "PT X" and
--                          "X, PT") are one investor; and the investor of the list before it is matched with (the same
--                          name, the same name spelled otherwise, or the same block of shares under another name), its
--                          shares then, the change (BUY, SELL, UNCHANGED, ENTERED: new above 1%)
--   v_ownership_one_movement : the investors of every list, and those that left it: an investor of the list before
--                          (stored) that no investor of the list is matched with (not continued) fell below 1% or sold
--                          out (EXITED)
--   rag_document         : source type XLSX, source_key OWNERSHIP_1_PERCENT: per stored company one document of its
--                          holders of 1% or more and their changes over every stored month, for the AI analysis
--
-- The comparison is made by the application when a list is stored (for that list and for the next stored one), so a
-- month uploaded late is fitted in between. Executed on every application start by Spring SQL init, after V1.0.33.
-- Every statement is idempotent.
-- =====================================================================

COMMENT ON COLUMN rag_document.source_key IS 'PDF: SHA-256 of the file (one document per file and company or country); NEWS: the article URL; XLSX: the Excel document type (OWNERSHIP_5_PERCENT, OWNERSHIP_1_PERCENT: one document per company, rebuilt from every stored list).';

-- ---------------------------------------------------------------------
-- ownership_one_report
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ownership_one_report (
    report_id               BIGSERIAL PRIMARY KEY,

    exchange                VARCHAR(10) NOT NULL,
    document_type           VARCHAR(30) NOT NULL,
    -- the list's date (its DATE column: the month's last trading day)
    report_date             DATE NOT NULL,
    -- the stored list it is compared with (the latest stored before it); NULL: none is stored
    previous_date           DATE,
    -- the announcement number in the file name (peng-2026-09-00024-satu-persen.xlsx -> 00024)
    announcement_number     VARCHAR(30),

    file_id                 BIGINT
        REFERENCES ingestion_file(file_id),
    file_name               VARCHAR(255),

    issuers                 INTEGER NOT NULL,
    holders                 INTEGER NOT NULL,
    source_rows             INTEGER NOT NULL,
    warnings                TEXT,
    notes                   TEXT,

    job_id                  UUID,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_ownership_one_report UNIQUE (exchange, document_type, report_date),
    CONSTRAINT ck_ownership_one_report_type CHECK (document_type IN ('OWNERSHIP_1_PERCENT')),
    CONSTRAINT ck_ownership_one_report_dates CHECK (previous_date IS NULL OR previous_date < report_date),
    CONSTRAINT ck_ownership_one_report_counts CHECK (issuers >= 0 AND holders >= 0 AND source_rows >= holders)
);

CREATE INDEX IF NOT EXISTS ix_ownership_one_report_date ON ownership_one_report (exchange, document_type, report_date DESC);

COMMENT ON TABLE  ownership_one_report IS 'KSEI''s list of the shareholders of more than 1% at the end of a month, V1.0.34.';
COMMENT ON COLUMN ownership_one_report.report_date IS 'The list''s date (its DATE column).';
COMMENT ON COLUMN ownership_one_report.previous_date IS 'The stored list it is compared with: the latest one before it; NULL when none is stored (no changes known).';
COMMENT ON COLUMN ownership_one_report.holders IS 'Investors (ownership_one_holder rows): the rows of the same investor counted once.';
COMMENT ON COLUMN ownership_one_report.source_rows IS 'Rows of the sheet read (an investor may have several: an SID and a scrip account, or its name spelled twice).';
COMMENT ON COLUMN ownership_one_report.warnings IS 'Rows not read, one per line; NULL when none.';
COMMENT ON COLUMN ownership_one_report.notes IS 'What was decided while reading (rows of one investor counted together, a total that did not add up decided by the percentage, rows without a ticker skipped), one per line; NULL when none.';

-- ---------------------------------------------------------------------
-- ownership_one_holder
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ownership_one_holder (
    holder_id               BIGSERIAL PRIMARY KEY,

    report_id               BIGINT NOT NULL
        REFERENCES ownership_one_report(report_id) ON DELETE CASCADE,
    -- repeated from the report for filtering without a join
    exchange                VARCHAR(10) NOT NULL,
    report_date             DATE NOT NULL,

    ticker                  VARCHAR(20) NOT NULL,
    issuer_name             VARCHAR(300) NOT NULL,
    list_number             INTEGER NOT NULL,
    holder_name             VARCHAR(300) NOT NULL,
    holder_key              VARCHAR(300) NOT NULL,
    other_names             TEXT,
    source_rows             INTEGER NOT NULL,
    investor_type           VARCHAR(100),
    type_group              VARCHAR(20),
    investor_status         CHAR(1),
    nationality             VARCHAR(100),
    domicile                VARCHAR(100),

    shares_scripless        BIGINT NOT NULL,
    shares_scrip            BIGINT NOT NULL,
    shares                  BIGINT NOT NULL,
    percent                 NUMERIC(9, 4),

    -- the comparison with the list before it (previous_date), written by the application
    previous_date           DATE,
    previous_holder_id      BIGINT
        REFERENCES ownership_one_holder(holder_id) ON DELETE SET NULL,
    previous_name           VARCHAR(300),
    match_kind              VARCHAR(10),
    shares_previous         BIGINT,
    percent_previous        NUMERIC(9, 4),
    change_shares           BIGINT GENERATED ALWAYS AS (shares - shares_previous) STORED,
    movement                VARCHAR(10) GENERATED ALWAYS AS (CASE
                                WHEN previous_date IS NULL THEN NULL
                                WHEN shares_previous IS NULL THEN 'ENTERED'
                                WHEN shares > shares_previous THEN 'BUY'
                                WHEN shares < shares_previous THEN 'SELL'
                                ELSE 'UNCHANGED' END) STORED,
    -- an investor of the next stored list is matched with it, written by the application with that comparison
    continued               BOOLEAN NOT NULL DEFAULT FALSE,

    CONSTRAINT uq_ownership_one_holder UNIQUE (report_id, list_number),
    CONSTRAINT ck_ownership_one_holder_shares CHECK (shares_scripless >= 0 AND shares_scrip >= 0 AND shares >= 0
        AND (shares_previous IS NULL OR shares_previous >= 0)),
    CONSTRAINT ck_ownership_one_holder_status CHECK (investor_status IS NULL OR investor_status IN ('L', 'A')),
    CONSTRAINT ck_ownership_one_holder_group CHECK (type_group IS NULL OR type_group IN ('INDIVIDUAL', 'CORPORATE',
        'FUND', 'FINANCIAL', 'GOVERNMENT', 'OTHER')),
    CONSTRAINT ck_ownership_one_holder_match CHECK (match_kind IS NULL OR match_kind IN ('SAME', 'RENAMED', 'MOVED')),
    CONSTRAINT ck_ownership_one_holder_previous CHECK ((match_kind IS NULL) = (shares_previous IS NULL)
        AND (previous_date IS NOT NULL OR match_kind IS NULL)),
    CONSTRAINT ck_ownership_one_holder_row CHECK (list_number >= 1 AND source_rows >= 1)
);

-- the tables of the first version of this script, before the column was added
ALTER TABLE ownership_one_holder ADD COLUMN IF NOT EXISTS continued BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS ix_ownership_one_holder_ticker ON ownership_one_holder (exchange, ticker, report_date DESC);
CREATE INDEX IF NOT EXISTS ix_ownership_one_holder_key ON ownership_one_holder (holder_key, report_date DESC);
CREATE INDEX IF NOT EXISTS ix_ownership_one_holder_report ON ownership_one_holder (report_id, ticker, holder_key);
CREATE INDEX IF NOT EXISTS ix_ownership_one_holder_date ON ownership_one_holder (report_date DESC, movement);
CREATE INDEX IF NOT EXISTS ix_ownership_one_holder_previous ON ownership_one_holder (previous_holder_id);

COMMENT ON TABLE  ownership_one_holder IS 'An investor holding more than 1% of a company in a list of ownership_one_report, V1.0.34.';
COMMENT ON COLUMN ownership_one_holder.list_number IS 'Its order in the list (the first of its rows).';
COMMENT ON COLUMN ownership_one_holder.holder_name IS 'INVESTOR_NAME, as listed (of its largest row).';
COMMENT ON COLUMN ownership_one_holder.holder_key IS 'The name normalized (upper case, letters and digits, without PT / Tbk / Persero / Pte Ltd / Limited / Inc and the titles Drs / Dr / Ir / H / Hj): the same investor across months and companies.';
COMMENT ON COLUMN ownership_one_holder.other_names IS 'The other names of its rows in the same list (one per line); NULL when it has one row or one name.';
COMMENT ON COLUMN ownership_one_holder.source_rows IS 'Rows of the sheet counted together (an SID and a scrip account, or the name spelled twice).';
COMMENT ON COLUMN ownership_one_holder.investor_type IS 'INVESTOR_CLASSIFICATION (or the code of INVESTOR_TYPE: CP, ID, MF, ...) as a label: Individual, Corporate, Mutual Funds, ...; NULL when not printed (a scrip account).';
COMMENT ON COLUMN ownership_one_holder.type_group IS 'INDIVIDUAL, CORPORATE, FUND, FINANCIAL, GOVERNMENT or OTHER.';
COMMENT ON COLUMN ownership_one_holder.investor_status IS 'L = local, A = foreign (LOCAL_FOREIGN F or A); NULL when not printed.';
COMMENT ON COLUMN ownership_one_holder.shares IS 'TOTAL_HOLDING_SHARES (scripless + scrip; when the sheet''s total does not add up, the one the percentage confirms).';
COMMENT ON COLUMN ownership_one_holder.percent IS 'PERCENTAGE of the shares listed, as printed.';
COMMENT ON COLUMN ownership_one_holder.previous_date IS 'The list it is compared with (ownership_one_report.previous_date); NULL: none stored.';
COMMENT ON COLUMN ownership_one_holder.previous_holder_id IS 'The investor of the list before it that it is: the same name (SAME), the name spelled otherwise (RENAMED) or the same block of shares under another name (MOVED); NULL: not in that list (ENTERED).';
COMMENT ON COLUMN ownership_one_holder.change_shares IS 'shares - shares_previous: bought (+) or sold (-); NULL when it was not in the list before.';
COMMENT ON COLUMN ownership_one_holder.continued IS 'An investor of the next stored list is matched with it (its previous_holder_id); FALSE: it is not in that list (EXITED there), or no list after it is stored.';
COMMENT ON COLUMN ownership_one_holder.movement IS 'BUY, SELL, UNCHANGED, ENTERED (new above 1%); NULL when no list before it is stored; the investors of the list before that are not matched are EXITED in v_ownership_one_movement.';

-- ---------------------------------------------------------------------
-- v_ownership_one_movement : every investor of every list, and those that left it
-- ---------------------------------------------------------------------
DROP VIEW IF EXISTS v_ownership_one_movement;

CREATE VIEW v_ownership_one_movement AS
SELECT h.holder_id, h.report_id, r.document_type, h.exchange, h.report_date, h.previous_date, h.ticker,
       h.issuer_name, h.list_number, h.holder_name, h.holder_key, h.other_names, h.source_rows, h.investor_type,
       h.type_group, h.investor_status, h.nationality, h.domicile, h.shares_scripless, h.shares_scrip,
       h.previous_name, h.match_kind, h.previous_holder_id, h.shares_previous, h.percent_previous,
       h.shares AS shares_current,
       h.percent AS percent_current, h.change_shares, h.movement, FALSE AS left_list
FROM ownership_one_holder h
JOIN ownership_one_report r ON r.report_id = h.report_id
UNION ALL
-- an investor of the list before that no investor of the list is (not continued): below 1% now, or sold out
SELECT p.holder_id, r.report_id, r.document_type, r.exchange, r.report_date, r.previous_date, p.ticker,
       p.issuer_name, p.list_number, p.holder_name, p.holder_key, p.other_names, p.source_rows, p.investor_type,
       p.type_group, p.investor_status, p.nationality, p.domicile, p.shares_scripless, p.shares_scrip,
       p.holder_name, NULL, NULL, p.shares, p.percent, NULL, NULL, NULL, 'EXITED', TRUE
FROM ownership_one_report r
JOIN ownership_one_report pr ON pr.exchange = r.exchange AND pr.document_type = r.document_type
                            AND pr.report_date = r.previous_date
JOIN ownership_one_holder p ON p.report_id = pr.report_id AND NOT p.continued;

COMMENT ON VIEW v_ownership_one_movement IS 'Every investor of every list of ownership_one_report, and (left_list) each investor of the list before it that no investor of the list is matched with: EXITED, its shares then as shares_previous; holder_id is then the row of the list before (V1.0.34).';
