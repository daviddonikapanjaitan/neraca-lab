-- =====================================================================
-- Neraca Lab - document type of a company PDF, and the monthly registration reports of securities holders
--
--   rag_document          : a company PDF is a FINANCIAL_STATEMENT (every company PDF stored before, the default) or a
--                           REGISTRATION report (Laporan Bulanan Registrasi Pemegang Efek, LBRE, filed every month on
--                           idx.co.id); chosen on Ingestion > PDF Documents (RAG)
--   registration_report   : one per company and month: the report read from a REGISTRATION PDF (letter, correction,
--                           filing time, shares listed); a later filing of the same month replaces it
--   registration_insider  : the shares of each director and commissioner at the end of the previous month and of the
--                           report's month (Pemegang Saham Direksi dan Komisaris), the change between them (bought: +,
--                           sold: -); shown on Companies > Registration
--
-- The shareholders of 5 % or more and the holders of more than 1 % with their broker are not stored yet (to be
-- planned). Executed on every application start by Spring SQL init, after V1.0.28. Every statement is idempotent.
-- =====================================================================

-- ---------------------------------------------------------------------
-- rag_document : the type of a company PDF
-- ---------------------------------------------------------------------
ALTER TABLE rag_document
    DROP CONSTRAINT IF EXISTS ck_rag_document_type,
    ADD  CONSTRAINT ck_rag_document_type
        CHECK (document_type IS NULL OR document_type IN ('MONETARY_POLICY', 'FINANCIAL_STABILITY', 'FISCAL',
                                                          'EXTERNAL_SECTOR', 'ECONOMIC_OUTLOOK', 'STATISTICS',
                                                          'DEVELOPMENT_PLAN', 'RESEARCH', 'OTHER',
                                                          'FINANCIAL_STATEMENT', 'REGISTRATION'));

-- the company PDFs stored before the type existed are financial statements
UPDATE rag_document SET document_type = 'FINANCIAL_STATEMENT'
WHERE company_id IS NOT NULL AND source_type = 'PDF' AND document_type IS NULL;

-- a company PDF has a company type, a macro PDF a macro type, a news article none; an IDX XBRL workbook (source type
-- XBRL of V1.0.46: this script runs first on every start, so it must accept it too) is a FINANCIAL_STATEMENT
ALTER TABLE rag_document
    DROP CONSTRAINT IF EXISTS ck_rag_document_company_type,
    ADD  CONSTRAINT ck_rag_document_company_type
        CHECK (CASE
                   WHEN company_id IS NULL THEN document_type IS NULL
                                                OR document_type NOT IN ('FINANCIAL_STATEMENT', 'REGISTRATION')
                   WHEN source_type = 'PDF' THEN document_type IN ('FINANCIAL_STATEMENT', 'REGISTRATION')
                   WHEN source_type = 'XBRL' THEN document_type = 'FINANCIAL_STATEMENT'
                   ELSE document_type IS NULL
               END);

COMMENT ON COLUMN rag_document.document_type IS
    'Company PDF: FINANCIAL_STATEMENT (default) or REGISTRATION (monthly registration report of securities holders, V1.0.29); macro document: MONETARY_POLICY, FINANCIAL_STABILITY, FISCAL, EXTERNAL_SECTOR, ECONOMIC_OUTLOOK, STATISTICS, DEVELOPMENT_PLAN, RESEARCH (opinion) or OTHER; news: NULL.';

-- ---------------------------------------------------------------------
-- registration_report
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS registration_report (
    report_id               BIGSERIAL PRIMARY KEY,

    company_id              BIGINT NOT NULL
        REFERENCES company(company_id) ON DELETE CASCADE,
    -- the month the report closes: its first day (holdings at the end of the month)
    period                  DATE NOT NULL,

    -- the PDF in the RAG vector store it was read from
    document_id             BIGINT
        REFERENCES rag_document(document_id) ON DELETE CASCADE,
    file_id                 BIGINT
        REFERENCES ingestion_file(file_id),
    file_name               VARCHAR(255),

    letter_number           VARCHAR(200),
    subject                 VARCHAR(500),
    correction              BOOLEAN NOT NULL DEFAULT FALSE,
    filed_at                TIMESTAMPTZ,
    registrar               VARCHAR(200),
    listed_shares_previous  BIGINT,
    listed_shares_current   BIGINT,
    insiders                INTEGER NOT NULL,
    warnings                TEXT,

    job_id                  UUID,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_registration_report UNIQUE (company_id, period),
    CONSTRAINT ck_registration_report_period CHECK (EXTRACT(DAY FROM period) = 1),
    CONSTRAINT ck_registration_report_counts CHECK (insiders >= 0
        AND (listed_shares_previous IS NULL OR listed_shares_previous >= 0)
        AND (listed_shares_current IS NULL OR listed_shares_current >= 0))
);

CREATE INDEX IF NOT EXISTS ix_registration_report_document ON registration_report (document_id);
CREATE INDEX IF NOT EXISTS ix_registration_report_period ON registration_report (period DESC);

COMMENT ON TABLE  registration_report IS 'Monthly registration report of securities holders (LBRE) of a company, read from a REGISTRATION PDF; one per company and month (V1.0.29).';
COMMENT ON COLUMN registration_report.period IS 'First day of the month the report closes.';
COMMENT ON COLUMN registration_report.correction IS 'The report corrects one filed before (Perihal ... (KOREKSI)).';
COMMENT ON COLUMN registration_report.filed_at IS 'Filing time printed in the report (Tanggal dan Waktu, Jakarta time); a later filing of the same month replaces the report, an earlier one does not.';
COMMENT ON COLUMN registration_report.warnings IS 'What did not add up when the report was read (a percentage that does not match its share count), one per line; NULL when none.';

-- ---------------------------------------------------------------------
-- registration_insider
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS registration_insider (
    insider_id              BIGSERIAL PRIMARY KEY,

    report_id               BIGINT NOT NULL
        REFERENCES registration_report(report_id) ON DELETE CASCADE,
    -- repeated from the report for filtering without a join
    company_id              BIGINT NOT NULL
        REFERENCES company(company_id) ON DELETE CASCADE,
    period                  DATE NOT NULL,

    row_index               INTEGER NOT NULL,
    holder_name             VARCHAR(300) NOT NULL,
    position                VARCHAR(200) NOT NULL,
    role                    VARCHAR(20) NOT NULL,
    shares_previous         BIGINT,
    percent_previous        NUMERIC(9, 4),
    shares_current          BIGINT,
    percent_current         NUMERIC(9, 4),
    change_shares           BIGINT GENERATED ALWAYS AS (shares_current - shares_previous) STORED,
    controller              BOOLEAN NOT NULL DEFAULT FALSE,
    controller_affiliate    BOOLEAN NOT NULL DEFAULT FALSE,

    CONSTRAINT uq_registration_insider UNIQUE (report_id, row_index),
    CONSTRAINT ck_registration_insider_role CHECK (role IN ('DIRECTOR', 'COMMISSIONER', 'OTHER')),
    CONSTRAINT ck_registration_insider_shares CHECK ((shares_previous IS NULL OR shares_previous >= 0)
        AND (shares_current IS NULL OR shares_current >= 0)
        AND (shares_previous IS NOT NULL OR shares_current IS NOT NULL)),
    CONSTRAINT ck_registration_insider_row CHECK (row_index >= 1)
);

CREATE INDEX IF NOT EXISTS ix_registration_insider_company ON registration_insider (company_id, period DESC);
CREATE INDEX IF NOT EXISTS ix_registration_insider_period ON registration_insider (period DESC);

COMMENT ON TABLE  registration_insider IS 'Shares of a director or commissioner in a registration report (Pemegang Saham Direksi dan Komisaris), V1.0.29.';
COMMENT ON COLUMN registration_insider.position IS 'Position as filed (Direksi, Komisaris, Direktur Utama, ...); role is read from it.';
COMMENT ON COLUMN registration_insider.role IS 'DIRECTOR, COMMISSIONER or OTHER (a position naming neither).';
COMMENT ON COLUMN registration_insider.shares_previous IS 'Shares at the end of the previous month (Jumlah Saham Sebelumnya); NULL when the cell is empty.';
COMMENT ON COLUMN registration_insider.shares_current IS 'Shares at the end of the report''s month (Jumlah Saham Bulan Ini); NULL when the cell is empty.';
COMMENT ON COLUMN registration_insider.change_shares IS 'shares_current - shares_previous: bought (+) or sold (-) during the month; NULL when either is missing.';
COMMENT ON COLUMN registration_insider.controller IS 'Marked as the controlling shareholder (Pengendali).';
COMMENT ON COLUMN registration_insider.controller_affiliate IS 'Marked as an affiliate of the controlling shareholder (Afiliasi Pengendali).';
