-- =====================================================================
-- Neraca Lab - the EBITDA an EV / EBITDA of valuation_snapshot is built on (PostgreSQL 15+)
--
-- The trailing twelve months of an interim period (H1 2026 + FY2025 - H1 2025) need the comparative period's
-- depreciation, which an interim filing gives only when its profit or loss states it: most IDX companies' H1 2025 has
-- no EBITDA until their own H1 2025 filing is uploaded, so their latest EV / EBITDA was empty. It then takes the full
-- year inside the trailing window (FY2025 for H1 2026), and says so:
--
--   ebitda_basis  'TTM' (ebitda_ttm, the trailing twelve months), 'FY 2025' (the full year's EBITDA, ebitda_ttm
--                 empty), NULL (no EBITDA: no EV / EBITDA)
--
-- Written by V1.0.6__data_metrics_valuation.sql and ValuationRepository. Runs on every start after V1.0.35;
-- idempotent.
-- =====================================================================

ALTER TABLE valuation_snapshot ADD COLUMN IF NOT EXISTS ebitda_basis VARCHAR(20);

COMMENT ON COLUMN valuation_snapshot.ebitda_basis IS 'The EBITDA of ev_ebitda: TTM (ebitda_ttm), or FY <year> (the full year inside the trailing window, when the trailing twelve months are not derivable: the comparative interim period has no depreciation); NULL when there is none (V1.0.36).';
