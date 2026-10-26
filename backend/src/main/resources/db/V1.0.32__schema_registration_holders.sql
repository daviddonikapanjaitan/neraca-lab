-- =====================================================================
-- Neraca Lab - the shareholders of 5 % or more and the shares by type of holder in the monthly registration reports
--
--   registration_holder   : each shareholder of 5 % or more of a report (Pemegang saham 5% (lima persen) atau lebih:
--                           the controller and the other large holders) at the end of the previous month and of the
--                           report's month, with the change between them; shown on Companies > Registration
--   registration_report   : + the shares by type of holder at the end of the month (Jumlah Saham Scrip dan Scripless
--                           berdasarkan Tipe dan Klasifikasi Investor): the controller, its affiliates, the directors
--                           and commissioners together, the treasury shares; + the number of holders read
--
-- A report has them once it is uploaded again: the reports stored before (V1.0.29) keep NULL / no holder rows.
-- Executed on every application start by Spring SQL init, after V1.0.30. Every statement is idempotent.
-- =====================================================================

ALTER TABLE registration_report
    ADD COLUMN IF NOT EXISTS holders                      INTEGER,
    ADD COLUMN IF NOT EXISTS controller_shares            BIGINT,
    ADD COLUMN IF NOT EXISTS controller_affiliate_shares  BIGINT,
    ADD COLUMN IF NOT EXISTS insider_shares               BIGINT,
    ADD COLUMN IF NOT EXISTS treasury_shares              BIGINT;

ALTER TABLE registration_report
    DROP CONSTRAINT IF EXISTS ck_registration_report_ownership,
    ADD  CONSTRAINT ck_registration_report_ownership CHECK ((holders IS NULL OR holders >= 0)
        AND (controller_shares IS NULL OR controller_shares >= 0)
        AND (controller_affiliate_shares IS NULL OR controller_affiliate_shares >= 0)
        AND (insider_shares IS NULL OR insider_shares >= 0)
        AND (treasury_shares IS NULL OR treasury_shares >= 0));

COMMENT ON COLUMN registration_report.holders IS 'Shareholders of 5 % or more read (registration_holder rows); NULL: stored before V1.0.32.';
COMMENT ON COLUMN registration_report.controller_shares IS 'Shares of the controller at the end of the month (summary by type of holder: Pengendali); NULL when not printed.';
COMMENT ON COLUMN registration_report.controller_affiliate_shares IS 'Shares of the affiliates of the controller (Afiliasi dari pengendali); NULL when not printed.';
COMMENT ON COLUMN registration_report.insider_shares IS 'Shares of the directors and commissioners together (Direksi dan Dewan Komisaris), as the summary prints them; NULL when not printed.';
COMMENT ON COLUMN registration_report.treasury_shares IS 'Treasury shares (Saham Tresuri); NULL when not printed.';

-- ---------------------------------------------------------------------
-- registration_holder
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS registration_holder (
    holder_id               BIGSERIAL PRIMARY KEY,

    report_id               BIGINT NOT NULL
        REFERENCES registration_report(report_id) ON DELETE CASCADE,
    -- repeated from the report for filtering without a join
    company_id              BIGINT NOT NULL
        REFERENCES company(company_id) ON DELETE CASCADE,
    period                  DATE NOT NULL,

    row_index               INTEGER NOT NULL,
    holder_name             VARCHAR(300) NOT NULL,
    shares_previous         BIGINT,
    percent_previous        NUMERIC(9, 4),
    shares_current          BIGINT,
    percent_current         NUMERIC(9, 4),
    change_shares           BIGINT GENERATED ALWAYS AS (shares_current - shares_previous) STORED,
    controller              BOOLEAN NOT NULL DEFAULT FALSE,
    controller_affiliate    BOOLEAN NOT NULL DEFAULT FALSE,

    CONSTRAINT uq_registration_holder UNIQUE (report_id, row_index),
    CONSTRAINT ck_registration_holder_shares CHECK ((shares_previous IS NULL OR shares_previous >= 0)
        AND (shares_current IS NULL OR shares_current >= 0)
        AND (shares_previous IS NOT NULL OR shares_current IS NOT NULL)),
    CONSTRAINT ck_registration_holder_row CHECK (row_index >= 1)
);

CREATE INDEX IF NOT EXISTS ix_registration_holder_company ON registration_holder (company_id, period DESC);

COMMENT ON TABLE  registration_holder IS 'A shareholder of 5 % or more in a registration report (Pemegang saham 5% atau lebih), V1.0.32.';
COMMENT ON COLUMN registration_holder.percent_previous IS 'As printed: of the shares issued or of the shares listed, as the company reports it.';
COMMENT ON COLUMN registration_holder.change_shares IS 'shares_current - shares_previous: bought (+) or sold (-) during the month; NULL when either is missing.';
COMMENT ON COLUMN registration_holder.controller IS 'Marked as the controlling shareholder (Pengendali).';
COMMENT ON COLUMN registration_holder.controller_affiliate IS 'Marked as an affiliate of the controlling shareholder (Afiliasi Pengendali).';
