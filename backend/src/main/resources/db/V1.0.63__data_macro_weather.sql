-- =====================================================================
-- Neraca Lab - the weather and climate series of Ingestion > Macro (catalog rows of macro_series_def)
--
-- Two groups, kept apart by their code and country (docs/v1_docs/MACRO_DOCS.md, section 1a):
--   GL_*  global condition: El Nino / La Nina (ENSO), the Indian Ocean Dipole (IOD), the SOI, the ENSO forecast,
--         and the rainfall and drought of the other producers Indonesia competes or trades with (Malaysia's palm
--         oil, Australia's coal basins, India's monsoon)            -> country GLOBAL
--   ID_*  Indonesia's condition: the rainfall and drought of its palm oil, coal and rice regions -> country ID
-- Categories: WEATHER_ENSO, WEATHER_IOD, WEATHER_RAIN, WEATHER_DROUGHT.
--
-- Date conventions (point in time: release_date = period end + release_lag_days):
--   RONI / ONI      a 3-month season is dated on the 1st of its LAST month (ASO 2026 -> 2026-10-01), so it is
--                   public only after the season ended (NOAA updates the files about the 3rd of the next month)
--   weekly Nino 3.4 the centre day of the week (a Wednesday), as NOAA labels it; the file is updated the Monday after
--   IOD (JAMSTEC)   the 1st of the month; JAMSTEC posts a month about ten days after it ends
--   SOI 30-day      the last day of the 30-day window (the mean of Long Paddock's daily SOI)
-- The Bureau of Meteorology's weekly IOD and daily SOI files answer HTTP 403 to a crawler that names itself (checked
-- 2026-11-16), so the IOD comes from JAMSTEC and the SOI from Long Paddock (the same BoM scaling).
--   probabilities   the second Thursday of the month NOAA issued them
--   rainfall, SPI   the 1st of the month; CHIRPS posts a month about two weeks after it ends
-- The rainfall regions are lists of boxes chosen to avoid foreign land (ChirpsSource.REGIONS); CHIRPS has no value
-- over the sea, so only land pixels are averaged (weighted by the cosine of the latitude).
--
-- Executed on every application start by Spring SQL init (after V1.0.26). Every statement is idempotent.
-- =====================================================================

INSERT INTO macro_series_def (series_code, name, country, category, unit, frequency, source, source_ref, source_url,
                              release_lag_days, priority, used_by, min_value, max_value, max_change, history_from,
                              sort_order, description)
VALUES
    -- ---------------------------------------------------------------- global: El Nino / La Nina (ENSO)
    ('GL_ENSO_RONI', 'El Nino / La Nina: Relative Oceanic Nino Index (RONI)', 'GLOBAL', 'WEATHER_ENSO', 'degC', 'MONTHLY',
     'noaa_cpc', 'RONI.ascii.txt', 'https://www.cpc.ncep.noaa.gov/data/indices/RONI.ascii.txt',
     7, 1, 'TUDOR_JONES,DRUCKENMILLER,SOROS,DALIO', -5, 5, 1, DATE '1950-01-01', 400,
     'NOAA''s official ENSO index since 2026: the 3-month mean sea surface temperature anomaly of the Nino 3.4 region '
     || 'minus that of the whole tropics. El Nino at +0.5 or more, La Nina at -0.5 or less (weak 0.5, moderate 1.0, '
     || 'strong 1.5, very strong 2.0). Dated on the last month of its season. El Nino dries Sumatra, Kalimantan and Java '
     || 'from June to November (palm oil output down 6 - 12 months later, rice and food inflation up); La Nina wets them'),
    ('GL_ENSO_ONI', 'El Nino / La Nina: Oceanic Nino Index (ONI, classic)', 'GLOBAL', 'WEATHER_ENSO', 'degC', 'MONTHLY',
     'noaa_cpc', 'oni.ascii.txt', 'https://www.cpc.ncep.noaa.gov/data/indices/oni.ascii.txt',
     7, 2, 'TUDOR_JONES,DRUCKENMILLER,SOROS,DALIO', -5, 5, 1, DATE '1950-01-01', 410,
     'The classic ENSO index: the 3-month mean sea surface temperature anomaly of the Nino 3.4 region (ERSST). Same '
     || 'thresholds as RONI; kept for comparison with older studies. Dated on the last month of its season'),
    ('GL_NINO34_WK', 'El Nino / La Nina: Nino 3.4 weekly anomaly', 'GLOBAL', 'WEATHER_ENSO', 'degC', 'WEEKLY',
     'noaa_cpc', 'wksst9120.for#NINO34', 'https://www.cpc.ncep.noaa.gov/data/indices/wksst9120.for',
     5, 2, 'TUDOR_JONES,DRUCKENMILLER,SOROS,DALIO', -5, 6, 1, DATE '1982-01-01', 420,
     'Weekly sea surface temperature anomaly of the Nino 3.4 region (OISST, 1991 - 2020 base): the fastest ENSO reading, '
     || 'about a week behind. Dated on the centre (Wednesday) of the week'),
    ('GL_SOI', 'El Nino / La Nina: Southern Oscillation Index (monthly)', 'GLOBAL', 'WEATHER_ENSO', 'SOI', 'MONTHLY',
     'longpaddock', 'MonthlySOI1887-1989Base.txt',
     'https://data.longpaddock.qld.gov.au/SeasonalClimateOutlook/SouthernOscillationIndex/SOIDataFiles/MonthlySOI1887-1989Base.txt',
     3, 2, 'TUDOR_JONES,DRUCKENMILLER,SOROS,DALIO', -60, 60, 35, DATE '1991-01-01', 430,
     'The atmosphere''s side of ENSO: the standardised pressure difference Tahiti minus Darwin, as the Australian Bureau '
     || 'of Meteorology scales it (Troup SOI). Below -7 for months: El Nino; above +7: La Nina. Queensland Government '
     || '(Long Paddock) file, from June 1991'),
    ('GL_SOI_30D', 'El Nino / La Nina: SOI 30-day (daily)', 'GLOBAL', 'WEATHER_ENSO', 'SOI', 'DAILY',
     'longpaddock', 'DailySOI1887-1989Base.txt#30D',
     'https://data.longpaddock.qld.gov.au/SeasonalClimateOutlook/SouthernOscillationIndex/SOIDataFiles/DailySOI1887-1989Base.txt',
     1, 2, 'TUDOR_JONES,DRUCKENMILLER,SOROS,DALIO', -60, 60, 10, DATE '1991-07-01', 440,
     'The SOI averaged over the last 30 days, every day: a fast confirmation of ENSO (Long Paddock''s daily SOI, the '
     || 'Bureau of Meteorology''s scaling, from June 1991; their "average SOI for last 30 days"). Below -7: El Nino; '
     || 'above +7: La Nina. Dated on the last day of the window'),
    ('GL_IOD_DMI', 'Indian Ocean Dipole (DMI, monthly)', 'GLOBAL', 'WEATHER_IOD', 'degC', 'MONTHLY',
     'jamstec', 'SINTEX_DMI.csv#Obs', 'https://www.jamstec.go.jp/virtualearth/data/SINTEX/SINTEX_DMI.csv',
     10, 1, 'TUDOR_JONES,DRUCKENMILLER,SOROS,DALIO', -4, 4, 1.5, DATE '1982-01-01', 450,
     'Dipole Mode Index: the sea surface temperature anomaly of the western Indian Ocean minus that of the eastern pole '
     || 'off Sumatra and Java (JAMSTEC, observed, monthly from 1982). Positive at +0.4 or more: a cool sea off western '
     || 'Indonesia and a dry Sumatra and Java (2019; 1997, 2006, 2023 with El Nino); negative at -0.4 or less: wet. '
     || 'Matters from July to November'),
    ('GL_PROB_ELNINO_3M', 'ENSO forecast: chance of El Nino in 3 months', 'GLOBAL', 'WEATHER_ENSO', '%', 'EVENT',
     'noaa_cpc', 'probabilities#ELNINO#3', 'https://www.cpc.ncep.noaa.gov/products/analysis_monitoring/enso/roni/probabilities/',
     0, 1, 'TUDOR_JONES,DRUCKENMILLER,SOROS,DALIO', 0, 100, 60, DATE '2026-04-01', 460,
     'NOAA''s official chance of El Nino (RONI +0.5 or more) for the 3-month season centred three months after the issue '
     || '(issued November: February, the JFM season). Dated on the second Thursday of the issue month, the day NOAA '
     || 'publishes it. The change from one issue to the next is the surprise markets react to. NOAA''s archive starts in '
     || 'April 2026'),
    ('GL_PROB_LANINA_3M', 'ENSO forecast: chance of La Nina in 3 months', 'GLOBAL', 'WEATHER_ENSO', '%', 'EVENT',
     'noaa_cpc', 'probabilities#LANINA#3', 'https://www.cpc.ncep.noaa.gov/products/analysis_monitoring/enso/roni/probabilities/',
     0, 1, 'TUDOR_JONES,DRUCKENMILLER,SOROS,DALIO', 0, 100, 60, DATE '2026-04-01', 470,
     'NOAA''s official chance of La Nina (RONI -0.5 or less) for the 3-month season centred three months after the issue. '
     || 'Dated on the second Thursday of the issue month'),
    ('GL_PROB_ELNINO_6M', 'ENSO forecast: chance of El Nino in 6 months', 'GLOBAL', 'WEATHER_ENSO', '%', 'EVENT',
     'noaa_cpc', 'probabilities#ELNINO#6', 'https://www.cpc.ncep.noaa.gov/products/analysis_monitoring/enso/roni/probabilities/',
     0, 2, 'TUDOR_JONES,DRUCKENMILLER,SOROS,DALIO', 0, 100, 60, DATE '2026-04-01', 480,
     'NOAA''s official chance of El Nino for the 3-month season centred six months after the issue (issued November: '
     || 'May, the AMJ season). Dated on the second Thursday of the issue month'),
    ('GL_PROB_LANINA_6M', 'ENSO forecast: chance of La Nina in 6 months', 'GLOBAL', 'WEATHER_ENSO', '%', 'EVENT',
     'noaa_cpc', 'probabilities#LANINA#6', 'https://www.cpc.ncep.noaa.gov/products/analysis_monitoring/enso/roni/probabilities/',
     0, 2, 'TUDOR_JONES,DRUCKENMILLER,SOROS,DALIO', 0, 100, 60, DATE '2026-04-01', 490,
     'NOAA''s official chance of La Nina for the 3-month season centred six months after the issue. Dated on the second '
     || 'Thursday of the issue month'),
    -- ---------------------------------------------------------------- global: rainfall of the other producers
    ('GL_RAIN_MY_PALM', 'Rainfall: Malaysia palm oil regions', 'GLOBAL', 'WEATHER_RAIN', 'mm', 'MONTHLY',
     'chirps', 'MY_PALM', 'https://data.chc.ucsb.edu/products/CHIRPS/v3.0/monthly/global/cogs/',
     15, 2, 'TUDOR_JONES,DRUCKENMILLER,SOROS,DALIO', 0, 3000, NULL, DATE '1981-01-01', 500,
     'Monthly rainfall (CHIRPS v3.0, land mean) over Peninsular Malaysia, Sabah and Sarawak: the other half of the '
     || 'world''s palm oil. A drought there and in Indonesia together lifts CPO prices'),
    ('GL_RAIN_AU_COAL', 'Rainfall: Australia coal basins', 'GLOBAL', 'WEATHER_RAIN', 'mm', 'MONTHLY',
     'chirps', 'AU_COAL', 'https://data.chc.ucsb.edu/products/CHIRPS/v3.0/monthly/global/cogs/',
     15, 2, 'TUDOR_JONES,DRUCKENMILLER,SOROS,DALIO', 0, 3000, NULL, DATE '1981-01-01', 510,
     'Monthly rainfall (CHIRPS v3.0, land mean) over Queensland''s Bowen Basin and New South Wales'' Hunter Valley: '
     || 'Australia''s coal. La Nina floods there (2011, 2022) cut coal supply and lift the Newcastle price Indonesian '
     || 'coal follows'),
    ('GL_RAIN_IN_MONSOON', 'Rainfall: India monsoon', 'GLOBAL', 'WEATHER_RAIN', 'mm', 'MONTHLY',
     'chirps', 'IN_MONSOON', 'https://data.chc.ucsb.edu/products/CHIRPS/v3.0/monthly/global/cogs/',
     15, 3, 'TUDOR_JONES,DRUCKENMILLER,SOROS,DALIO', 0, 3000, NULL, DATE '1981-01-01', 520,
     'Monthly rainfall (CHIRPS v3.0, land mean) over central and peninsular India: the monsoon decides India''s rice '
     || 'exports (bans in 2023) and its demand for palm oil and coal'),
    -- ---------------------------------------------------------------- Indonesia: rainfall
    ('ID_RAIN_SUM_PALM', 'Rainfall: Sumatra palm oil regions', 'ID', 'WEATHER_RAIN', 'mm', 'MONTHLY',
     'chirps', 'ID_SUM_PALM', 'https://data.chc.ucsb.edu/products/CHIRPS/v3.0/monthly/global/cogs/',
     15, 1, 'TUDOR_JONES,DRUCKENMILLER,SOROS,DALIO', 0, 3000, NULL, DATE '1981-01-01', 530,
     'Monthly rainfall (CHIRPS v3.0, land mean) over North Sumatra''s east coast, Riau, Jambi and South Sumatra: the '
     || 'plantations of LSIP, SGRO, SIMP and much of AALI. A dry season well below normal cuts palm oil output 6 - 12 '
     || 'months later'),
    ('ID_RAIN_KAL_PALM', 'Rainfall: Kalimantan palm oil regions', 'ID', 'WEATHER_RAIN', 'mm', 'MONTHLY',
     'chirps', 'ID_KAL_PALM', 'https://data.chc.ucsb.edu/products/CHIRPS/v3.0/monthly/global/cogs/',
     15, 1, 'TUDOR_JONES,DRUCKENMILLER,SOROS,DALIO', 0, 3000, NULL, DATE '1981-01-01', 540,
     'Monthly rainfall (CHIRPS v3.0, land mean) over West, Central and East Kalimantan (south of the Malaysian border): '
     || 'the plantations of DSNG, SSMS, TAPG and part of AALI; also the fire and haze season (2015, 2019)'),
    ('ID_RAIN_KAL_COAL', 'Rainfall: Kalimantan coal regions', 'ID', 'WEATHER_RAIN', 'mm', 'MONTHLY',
     'chirps', 'ID_KAL_COAL', 'https://data.chc.ucsb.edu/products/CHIRPS/v3.0/monthly/global/cogs/',
     15, 1, 'TUDOR_JONES,DRUCKENMILLER,SOROS,DALIO', 0, 3000, NULL, DATE '1981-01-01', 550,
     'Monthly rainfall (CHIRPS v3.0, land mean) over South and East Kalimantan: the coal mines and river barging of ADRO, '
     || 'BUMI, INDY and ITMG. Heavy rain floods pits and stops output; a drought lowers the rivers barges need'),
    ('ID_RAIN_JAVA_RICE', 'Rainfall: Java rice regions', 'ID', 'WEATHER_RAIN', 'mm', 'MONTHLY',
     'chirps', 'ID_JAVA_RICE', 'https://data.chc.ucsb.edu/products/CHIRPS/v3.0/monthly/global/cogs/',
     15, 1, 'TUDOR_JONES,DRUCKENMILLER,SOROS,DALIO', 0, 3000, NULL, DATE '1981-01-01', 560,
     'Monthly rainfall (CHIRPS v3.0, land mean) over West, Central and East Java: most of Indonesia''s rice. A failed '
     || 'planting season raises rice prices, food inflation and the chance that Bank Indonesia cannot cut'),
    -- ---------------------------------------------------------------- drought (derived: SPI-3 of the rainfall above)
    ('GL_SPI3_MY_PALM', 'Drought: SPI-3 Malaysia palm oil regions', 'GLOBAL', 'WEATHER_DROUGHT', 'SPI', 'MONTHLY',
     'spi', 'GL_RAIN_MY_PALM', 'https://data.chc.ucsb.edu/products/CHIRPS/v3.0/monthly/global/cogs/',
     15, 2, 'TUDOR_JONES,DRUCKENMILLER,SOROS,DALIO', -5, 5, 3, DATE '1981-03-01', 600,
     '3-month Standardized Precipitation Index of GL_RAIN_MY_PALM: the 3-month rainfall as a z-score of a gamma '
     || 'distribution fitted to the same calendar months of 1991 - 2020. -1 or less moderate drought, -1.5 severe, -2 '
     || 'extreme; +1.5 or more very wet'),
    ('GL_SPI3_AU_COAL', 'Drought: SPI-3 Australia coal basins', 'GLOBAL', 'WEATHER_DROUGHT', 'SPI', 'MONTHLY',
     'spi', 'GL_RAIN_AU_COAL', 'https://data.chc.ucsb.edu/products/CHIRPS/v3.0/monthly/global/cogs/',
     15, 2, 'TUDOR_JONES,DRUCKENMILLER,SOROS,DALIO', -5, 5, 3, DATE '1981-03-01', 610,
     '3-month Standardized Precipitation Index of GL_RAIN_AU_COAL (1991 - 2020 base). +1.5 or more: the flooding that '
     || 'disrupts Australian coal supply'),
    ('GL_SPI3_IN_MONSOON', 'Drought: SPI-3 India monsoon', 'GLOBAL', 'WEATHER_DROUGHT', 'SPI', 'MONTHLY',
     'spi', 'GL_RAIN_IN_MONSOON', 'https://data.chc.ucsb.edu/products/CHIRPS/v3.0/monthly/global/cogs/',
     15, 3, 'TUDOR_JONES,DRUCKENMILLER,SOROS,DALIO', -5, 5, 3, DATE '1981-03-01', 620,
     '3-month Standardized Precipitation Index of GL_RAIN_IN_MONSOON (1991 - 2020 base). A weak monsoon (-1 or less from '
     || 'July to September) has led India to curb rice exports'),
    ('ID_SPI3_SUM_PALM', 'Drought: SPI-3 Sumatra palm oil regions', 'ID', 'WEATHER_DROUGHT', 'SPI', 'MONTHLY',
     'spi', 'ID_RAIN_SUM_PALM', 'https://data.chc.ucsb.edu/products/CHIRPS/v3.0/monthly/global/cogs/',
     15, 1, 'TUDOR_JONES,DRUCKENMILLER,SOROS,DALIO', -5, 5, 3, DATE '1981-03-01', 630,
     '3-month Standardized Precipitation Index of ID_RAIN_SUM_PALM (1991 - 2020 base): -1 or less moderate drought, -1.5 '
     || 'severe, -2 extreme'),
    ('ID_SPI3_KAL_PALM', 'Drought: SPI-3 Kalimantan palm oil regions', 'ID', 'WEATHER_DROUGHT', 'SPI', 'MONTHLY',
     'spi', 'ID_RAIN_KAL_PALM', 'https://data.chc.ucsb.edu/products/CHIRPS/v3.0/monthly/global/cogs/',
     15, 1, 'TUDOR_JONES,DRUCKENMILLER,SOROS,DALIO', -5, 5, 3, DATE '1981-03-01', 640,
     '3-month Standardized Precipitation Index of ID_RAIN_KAL_PALM (1991 - 2020 base)'),
    ('ID_SPI3_KAL_COAL', 'Drought: SPI-3 Kalimantan coal regions', 'ID', 'WEATHER_DROUGHT', 'SPI', 'MONTHLY',
     'spi', 'ID_RAIN_KAL_COAL', 'https://data.chc.ucsb.edu/products/CHIRPS/v3.0/monthly/global/cogs/',
     15, 1, 'TUDOR_JONES,DRUCKENMILLER,SOROS,DALIO', -5, 5, 3, DATE '1981-03-01', 650,
     '3-month Standardized Precipitation Index of ID_RAIN_KAL_COAL (1991 - 2020 base): +1.5 or more is the heavy rain '
     || 'that floods coal pits'),
    ('ID_SPI3_JAVA_RICE', 'Drought: SPI-3 Java rice regions', 'ID', 'WEATHER_DROUGHT', 'SPI', 'MONTHLY',
     'spi', 'ID_RAIN_JAVA_RICE', 'https://data.chc.ucsb.edu/products/CHIRPS/v3.0/monthly/global/cogs/',
     15, 1, 'TUDOR_JONES,DRUCKENMILLER,SOROS,DALIO', -5, 5, 3, DATE '1981-03-01', 660,
     '3-month Standardized Precipitation Index of ID_RAIN_JAVA_RICE (1991 - 2020 base)')
ON CONFLICT (series_code) DO UPDATE SET
    name = EXCLUDED.name, country = EXCLUDED.country, category = EXCLUDED.category, unit = EXCLUDED.unit,
    frequency = EXCLUDED.frequency, source = EXCLUDED.source, source_ref = EXCLUDED.source_ref,
    source_url = EXCLUDED.source_url, release_lag_days = EXCLUDED.release_lag_days, priority = EXCLUDED.priority,
    used_by = EXCLUDED.used_by, min_value = EXCLUDED.min_value, max_value = EXCLUDED.max_value,
    max_change = EXCLUDED.max_change, history_from = EXCLUDED.history_from, sort_order = EXCLUDED.sort_order,
    description = EXCLUDED.description;
