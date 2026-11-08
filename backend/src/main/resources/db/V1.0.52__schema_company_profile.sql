-- =====================================================================
-- Neraca Lab - IDX company profiles (Ingestion > Screening Data IDX > IDX company profiles)
--
--   company                : the listing date (ipo_date), the listing board and the IDX-IC classification of the
--                            IDX company profile (GetCompanyProfiles), its main business activity and share registrar
--   company_profile_upload : one row per uploaded JSON file (SHA-256, counts, the result); the same file uploaded
--                            again is a no-op
--   company_dividend       : the cash dividends of the IDX company profile detail (GetCompanyProfilesDetail,
--                            section Dividen): fiscal year, amount per share, cum / ex / recording / payment dates.
--                            Kept apart from corporate_event (Yahoo's dividends, the chart markers), so a dividend
--                            both sources know is not drawn twice; Companies > company > Profile shows both
--
-- idx.co.id blocks automated clients (Cloudflare), so the backend never fetches it: the JSON is collected in a
-- browser session (scripts/idx_company_profiles.js) and uploaded (POST /api/v1/companies/profiles/upload).
-- Executed on every application start by Spring SQL init, after V1.0.46. Every statement is idempotent.
-- =====================================================================

ALTER TABLE company ADD COLUMN IF NOT EXISTS listing_board       VARCHAR(40);
ALTER TABLE company ADD COLUMN IF NOT EXISTS idx_sector          VARCHAR(150);
ALTER TABLE company ADD COLUMN IF NOT EXISTS idx_sub_sector      VARCHAR(150);
ALTER TABLE company ADD COLUMN IF NOT EXISTS idx_industry        VARCHAR(150);
ALTER TABLE company ADD COLUMN IF NOT EXISTS idx_sub_industry    VARCHAR(150);
ALTER TABLE company ADD COLUMN IF NOT EXISTS business_activity   TEXT;
ALTER TABLE company ADD COLUMN IF NOT EXISTS share_registrar     VARCHAR(255);
ALTER TABLE company ADD COLUMN IF NOT EXISTS idx_name            VARCHAR(255);
ALTER TABLE company ADD COLUMN IF NOT EXISTS profile_updated_at  TIMESTAMPTZ;

COMMENT ON COLUMN company.ipo_date IS 'Listing date on the exchange (IDX: TanggalPencatatan of the IDX company profile, V1.0.52). Set when empty; a different date of a later upload is reported as a conflict, never overwritten.';
COMMENT ON COLUMN company.listing_board IS 'IDX listing board (PapanPencatatan): MAIN (Utama), DEVELOPMENT (Pengembangan), ACCELERATION (Akselerasi), NEW_ECONOMY (Ekonomi Baru), WATCHLIST (Pemantauan Khusus); another value upper case as filed.';
COMMENT ON COLUMN company.idx_sector IS 'IDX-IC sector of the IDX company profile (Sektor), as IDX names it.';
COMMENT ON COLUMN company.idx_sub_sector IS 'IDX-IC sub-sector (SubSektor).';
COMMENT ON COLUMN company.idx_industry IS 'IDX-IC industry (Industri).';
COMMENT ON COLUMN company.idx_sub_industry IS 'IDX-IC sub-industry (SubIndustri).';
COMMENT ON COLUMN company.business_activity IS 'Main business activity of the IDX company profile (KegiatanUsahaUtama).';
COMMENT ON COLUMN company.share_registrar IS 'Share registrar (BAE) of the IDX company profile.';
COMMENT ON COLUMN company.idx_name IS 'Company name of the IDX company profile (NamaEmiten), to check against legal_name.';
COMMENT ON COLUMN company.profile_updated_at IS 'When an IDX company profile upload last wrote the profile columns of the company.';

CREATE TABLE IF NOT EXISTS company_profile_upload (
    upload_id       BIGSERIAL PRIMARY KEY,
    exchange        VARCHAR(50) NOT NULL,
    file_name       VARCHAR(255),
    file_sha256     CHAR(64) NOT NULL,
    profiles        INTEGER NOT NULL,
    details         INTEGER NOT NULL,
    updated         INTEGER NOT NULL,
    unchanged       INTEGER NOT NULL,
    dividends       INTEGER NOT NULL,
    result          JSONB NOT NULL,
    uploaded_by     VARCHAR(100),
    uploaded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_company_profile_upload_file UNIQUE (exchange, file_sha256)
);

COMMENT ON TABLE  company_profile_upload IS 'Uploaded IDX company profile files (V1.0.52): counts and the full result (unknown tickers, conflicts, warnings). The same file again is answered from here, nothing is written.';
COMMENT ON COLUMN company_profile_upload.profiles IS 'Company profiles in the file (GetCompanyProfiles rows and the Profiles of the detail responses, one per ticker).';
COMMENT ON COLUMN company_profile_upload.details IS 'Detail responses in the file (GetCompanyProfilesDetail, one per ticker): their dividend history replaced the stored one.';
COMMENT ON COLUMN company_profile_upload.updated IS 'Companies whose profile columns or dividends changed.';
COMMENT ON COLUMN company_profile_upload.dividends IS 'Cash dividends stored from the detail responses.';

CREATE INDEX IF NOT EXISTS ix_company_profile_upload_exchange ON company_profile_upload (exchange, uploaded_at DESC);

CREATE TABLE IF NOT EXISTS company_dividend (
    dividend_id       BIGSERIAL PRIMARY KEY,
    company_id        BIGINT NOT NULL REFERENCES company(company_id),
    source            VARCHAR(20) NOT NULL DEFAULT 'idx',
    fiscal_year       INTEGER,
    dividend_type     VARCHAR(40),
    cash_per_share    NUMERIC(24,8) NOT NULL,
    currency          CHAR(3),
    cum_date          DATE,
    ex_date           DATE,
    recording_date    DATE,
    payment_date      DATE,
    total_amount      NUMERIC(28,4),
    total_currency    CHAR(3),
    upload_id         BIGINT REFERENCES company_profile_upload(upload_id) ON DELETE SET NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_company_dividend UNIQUE NULLS NOT DISTINCT (company_id, source, fiscal_year, cum_date, ex_date,
                                                              cash_per_share),
    CONSTRAINT ck_company_dividend_amount CHECK (cash_per_share > 0),
    CONSTRAINT ck_company_dividend_currency CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$')
);

COMMENT ON TABLE  company_dividend IS 'Cash dividends of the IDX company profile detail (V1.0.52, source idx): the whole history of a company is replaced by each detail upload of it. Yahoo''s dividends stay in corporate_event.';
COMMENT ON COLUMN company_dividend.fiscal_year IS 'Fiscal year the dividend is paid from (TahunBuku): an interim dividend in its own year, a final dividend usually in the year before its payment.';
COMMENT ON COLUMN company_dividend.dividend_type IS 'IDX''s type of the dividend (Jenis) as filed, e.g. interim / final.';
COMMENT ON COLUMN company_dividend.cash_per_share IS 'Cash dividend per share (CashDividenPerSaham) in currency (CashDividenPerSahamMU), as announced (not split-adjusted).';
COMMENT ON COLUMN company_dividend.cum_date IS 'Last trading day with the right to the dividend (regular and negotiated market, TanggalCum).';
COMMENT ON COLUMN company_dividend.ex_date IS 'First trading day without the right to the dividend (TanggalExRegulerDanNegosiasi).';
COMMENT ON COLUMN company_dividend.recording_date IS 'Recording date of the shareholders entitled (TanggalDPS).';
COMMENT ON COLUMN company_dividend.payment_date IS 'Payment date (TanggalPembayaran).';
COMMENT ON COLUMN company_dividend.total_amount IS 'Total cash dividend (CashDividenTotal) in total_currency.';

CREATE INDEX IF NOT EXISTS ix_company_dividend_company ON company_dividend (company_id, ex_date);
