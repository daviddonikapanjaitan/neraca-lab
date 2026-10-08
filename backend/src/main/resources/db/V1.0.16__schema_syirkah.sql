-- =====================================================================
-- Neraca Lab - temporary syirkah funds of banks (PostgreSQL 15+)
--
--   balance_sheet.temporary_syirkah_funds   non-equity investment funds of sharia depositors (mudharabah),
--                                           reported by a bank with a sharia unit or subsidiary between its
--                                           liabilities and its equity ("Total temporary syirkah funds",
--                                           Financial and Sharia Industry taxonomy). Under PSAK they are
--                                           neither liabilities nor equity, so total_liabilities stays the
--                                           filing's "Total liabilities" and
--                                           total assets = total_liabilities + temporary_syirkah_funds + total_equity.
--                                           NULL for companies without them.
--
-- Executed on every application start by Spring SQL init, after V1.0.15. Every statement is idempotent.
-- =====================================================================

ALTER TABLE balance_sheet ADD COLUMN IF NOT EXISTS temporary_syirkah_funds NUMERIC(24,4);

COMMENT ON COLUMN balance_sheet.temporary_syirkah_funds IS
    'Temporary syirkah funds (sharia depositors, neither liability nor equity); assets = liabilities + this + equity';
