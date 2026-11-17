-- =====================================================================
-- Neraca Lab - weather and climate series of Ingestion > Macro (PostgreSQL 15+)
--
--   macro_series_def            the sources of the weather series (V1.0.63__data_macro_weather.sql):
--                                 noaa_cpc     NOAA Climate Prediction Center: RONI, ONI, weekly Nino 3.4, ENSO probabilities
--                                 jamstec      JAMSTEC SINTEX-F: the monthly Indian Ocean Dipole (DMI)
--                                 longpaddock  Queensland Government (Long Paddock): monthly and 30-day SOI
--                                              (the Bureau of Meteorology's own files refuse a crawler that names itself)
--                                 chirps       UCSB Climate Hazards Center CHIRPS v3.0: monthly rainfall of a region
--                                 spi          derived: 3-month Standardized Precipitation Index of a stored rainfall
--                                              series (gamma fit per calendar month, 1991 - 2020)
--   macro_analysis_agent_score  gains climate: how the macro agent reads the weather (El Nino / La Nina, the IOD,
--                               rainfall, drought) for its call (Macro > AI Analysis)
--
-- Executed on every application start by Spring SQL init, after V1.0.25 and V1.0.27. Every statement is idempotent.
-- =====================================================================

ALTER TABLE macro_series_def DROP CONSTRAINT IF EXISTS ck_macro_series_def_source;
ALTER TABLE macro_series_def ADD CONSTRAINT ck_macro_series_def_source
    CHECK (source IN ('fred', 'yahoo', 'bi', 'bi_seki', 'cnbc', 'cftc', 'manual',
                      'noaa_cpc', 'jamstec', 'longpaddock', 'chirps', 'spi'));

ALTER TABLE macro_analysis_agent_score ADD COLUMN IF NOT EXISTS climate TEXT;

COMMENT ON COLUMN macro_analysis_agent_score.climate IS 'How the agent reads the weather (El Nino / La Nina, the IOD, rainfall and drought of Indonesia and of the other producers) for its call; null in analyses of before 2026-11-16';
