-- =====================================================================
-- Neraca Lab - catalog of the Forex pairs, commodities and macro series (PostgreSQL 15+)
--
-- Every source was checked on 2026-10-15:
--   Yahoo Finance chart API   IDR=X (2001-), JPY=X (1996-), BZ=F Brent (2007-), GC=F gold (2000-),
--                             CPO=F USD Malaysian crude palm oil (2010-), DX-Y.NYB (1971-). The currency history has
--                             bad prints (USD/IDR 888.11 on 2010-11-01, 9612.45 on several days of 2013, 15069.40 on
--                             2024-12-26): max_spike drops a close that jumps away and comes back (USD/IDR 3 %, a managed
--                             currency; USD/JPY 5 %, its real 2008 moves are larger)
--   World Bank Pink Sheet     coal: Yahoo has no current coal future (MTF=F stopped in 2025), so coal is the
--                             monthly "Coal, Australian" (Newcastle, USD/mt) of CMO-Historical-Data-Monthly.xlsx
--
-- Commodities added on 2026-10-17 (every source checked that day):
--   Yahoo Finance chart API   HG=F copper, SI=F silver, NG=F natural gas (Henry Hub), ZC=F corn, ZM=F soybean meal,
--                             ZW=F wheat, SB=F sugar #11, ZL=F soybean oil (all 2000-), and MYR=X (2003-). ZC, ZW, SB
--                             and ZL are quoted in US cents (Yahoo currency USX) and stored as quoted. max_spike is
--                             set so that no real day of the whole history is touched (the cleaner was run on each):
--                             NG=F 27 Jan 2022 (the February contract's expiry squeeze, 6.265 between 4.277 and 4.639),
--                             SB=F 28 Apr 2000 (the May expiry, 5.16 between 6.18 and 6.53), COMEX copper's 30 Jul
--                             2025 low of 4.49 (the tariff exemption), CBOT wheat on 27 Feb 2008 (1,065 - 1,334.5).
--   World Bank Pink Sheet     no free daily source: nickel and tin (LME; Yahoo has no NI=F / TIN=F), rubber (TSR 20,
--                             the grade Indonesia exports; no rubber future on Yahoo), LNG (Japan; JKM=F on Yahoo is
--                             an alternative symbol with a fifth of its days empty and almost no volume)
--   Palm oil in ringgit       Bursa Malaysia's FCPO has no free source a program may read (not on Yahoo; MPOB's prices
--                             are a paid subscription). PALM_OIL_MYR is CPO=F (CME, cash-settled on Bursa Malaysia's
--                             FCPO) x MYR=X on the same day: an approximation of FCPO in ringgit (2022 peak RM 6,913,
--                             2024 average RM 4,110), not Bursa's own price
--
-- Commodities added on 2026-10-17, second batch (every source checked that day):
--   Yahoo Finance chart API   CL=F WTI (2000-), ALI=F COMEX aluminium (2014-; a month missing in Sep 2016), TIO=F iron
--                             ore 62% Fe CFR China (2010-), HRC=F US Midwest hot-rolled coil steel (2008-). WTI settled
--                             at -37.63 on 20 Apr 2020: a close must be positive, so that day is not stored and the
--                             negative open / low of 21 Apr are cut back by the cleaner; max_spike 30 % keeps every
--                             other real day (18 Mar 2020: 20.37 between 26.95 and 25.22)
--   World Bank Pink Sheet     urea, DAP and potassium chloride (fertilizers): monthly averages
--   IMF PCPS                  cobalt (LME spot, minimum 99.80 %, USD per tonne, 1992-): the SDMX API of the IMF Primary
--                             Commodity Price System (G001.PCOBA.USD.M); not in the Pink Sheet, not on Yahoo (COB=F has
--                             one day) nor on FRED
--   FRED                      wood pulp: no free price exists (the IMF's wood pulp series is empty, the Pink Sheet has
--                             none, the price indexes FOEX / Fastmarkets are paid), so PULP is the US producer price index
--                             of wood pulp (BLS WPU0911, 1982 = 100): an index, not a price
--   Bauxite                   no free source: no exchange trades it, the Pink Sheet and the IMF have no bauxite price,
--                             and FRED's producer price index of bauxite (PCU2122992122992) stopped in 1990. Not added
-- Currency pairs added on 2026-10-23 (every source checked that day; Yahoo Finance chart API, London close):
--   CNY=X (2001-), AUDJPY=X, INR=X, PHP=X, MYR=X, KRW=X and EURUSD=X (2003-12-). max_spike was set on each whole
--                             history with the cleaner, to drop only prints that jump away and come straight back and
--                             never a real day: USD/CNY 2 % (a managed currency; 5.863 on 2011-07-18, 7.1587 on
--                             2023-10-30), USD/INR 3.5 % (the stale 52.493 of 2012-01-27 and -31; lower would drop the
--                             real 49.388 between them), USD/PHP 2.7 % (2016-11-17, 2017-03-06, 2021-12-13, 2025-06-13;
--                             2012-01-30's 42.619 between two stale 43.714 is kept), USD/MYR 4 % (four prints of
--                             2006 - 2007), USD/KRW 6 % (none: the real 1,160 of 2008-09-16, Lehman week, comes back
--                             5.6 %), EUR/USD 4 % (five prints of 2008, e.g. 1.4918 between 1.2717 and 1.2926 on
--                             2008-12-08), AUD/JPY 10 % (none: its real October 2008 crash and rebounds exceed 9 %)
--   FRED                      fredgraph.csv?id=... (no API key)
--   Bank Indonesia            BI-Rate and inflation pages (their "Download" export), SEKI tables (.xls)
--   CNBC                      ID10Y-ID daily bars (2011-)
--   CFTC                      Commitments of Traders, legacy futures only (publicreporting.cftc.gov 6dca-aqww)
--   manual                    no free source a program may read: IDX foreign flows (the site answers a bot
--                             challenge), SBN foreign ownership, Indonesia CDS, China PMI; CSV upload
--
-- Runs on every start. Idempotent: a row is inserted or brought back to this definition.
-- =====================================================================

-- instrument_id is a SMALLSERIAL: an INSERT ... ON CONFLICT draws a sequence value for every listed row, also for the
-- rows that exist, so the 35 instruments used 35 values at every start (test contexts included) until the sequence ran
-- out at 32,767 on 2026-10-31 and every start failed ("nextval: reached maximum value"). The sequence is set back to the
-- highest id in use, the existing rows are updated where their definition changed, and only missing ones are inserted
-- (a value is drawn only for them).
SELECT setval(pg_get_serial_sequence('market_instrument', 'instrument_id'),
              (SELECT COALESCE(MAX(instrument_id), 1) FROM market_instrument),
              (SELECT COUNT(*) > 0 FROM market_instrument));

WITH v (instrument_code, market, name, source, source_symbol, quote_currency, unit, frequency,
        tick_size, max_spike, history_from, sort_order, description) AS (
VALUES
    ('USDIDR',   'FOREX',     'USD/IDR',             'yahoo',     'IDR=X',            'IDR', 'IDR per USD',          'DAILY',   1,     0.03, DATE '2001-01-01', 10,
     'US dollar in Indonesian rupiah (Yahoo Finance IDR=X, London close)'),
    ('USDJPY',   'FOREX',     'USD/JPY',             'yahoo',     'JPY=X',            'JPY', 'JPY per USD',          'DAILY',   0.001, 0.05, DATE '1996-01-01', 20,
     'US dollar in Japanese yen (Yahoo Finance JPY=X, London close)'),
    ('USDCNY',   'FOREX',     'USD/CNY',             'yahoo',     'CNY=X',            'CNY', 'CNY per USD',          'DAILY',   0.0001, 0.02, DATE '2001-01-01', 30,
     'US dollar in Chinese yuan, onshore (Yahoo Finance CNY=X, London close)'),
    ('USDMYR',   'FOREX',     'USD/MYR',             'yahoo',     'MYR=X',            'MYR', 'MYR per USD',          'DAILY',   0.0001, 0.04, DATE '2003-12-01', 40,
     'US dollar in Malaysian ringgit (Yahoo Finance MYR=X, London close)'),
    ('USDPHP',   'FOREX',     'USD/PHP',             'yahoo',     'PHP=X',            'PHP', 'PHP per USD',          'DAILY',   0.001, 0.027, DATE '2003-12-01', 50,
     'US dollar in Philippine peso (Yahoo Finance PHP=X, London close)'),
    ('USDINR',   'FOREX',     'USD/INR',             'yahoo',     'INR=X',            'INR', 'INR per USD',          'DAILY',   0.001, 0.035, DATE '2003-12-01', 60,
     'US dollar in Indian rupee (Yahoo Finance INR=X, London close)'),
    ('USDKRW',   'FOREX',     'USD/KRW',             'yahoo',     'KRW=X',            'KRW', 'KRW per USD',          'DAILY',   0.01,  0.06, DATE '2003-12-01', 70,
     'US dollar in South Korean won (Yahoo Finance KRW=X, London close)'),
    ('EURUSD',   'FOREX',     'EUR/USD',             'yahoo',     'EURUSD=X',         'USD', 'USD per EUR',          'DAILY',   0.0001, 0.04, DATE '2003-12-01', 80,
     'Euro in US dollars (Yahoo Finance EURUSD=X, London close): a rise is a weaker dollar'),
    ('AUDJPY',   'FOREX',     'AUD/JPY',             'yahoo',     'AUDJPY=X',         'JPY', 'JPY per AUD',          'DAILY',   0.001, 0.10, DATE '2003-12-01', 90,
     'Australian dollar in Japanese yen (Yahoo Finance AUDJPY=X, London close): a gauge of risk appetite and carry trades'),
    -- energy
    ('BRENT',        'COMMODITY', 'Crude Oil (Brent)',       'yahoo',     'BZ=F',             'USD', 'USD per barrel',       'DAILY',   0.01,   0.15, DATE '2007-01-01', 10,
     'Brent crude oil front-month future (ICE / NYMEX BZ=F); the benchmark of Indonesian crude (ICP)'),
    ('WTI',          'COMMODITY', 'Crude Oil (WTI)',         'yahoo',     'CL=F',             'USD', 'USD per barrel',       'DAILY',   0.01,   0.30, DATE '2000-09-01', 15,
     'West Texas Intermediate crude oil front-month future (NYMEX CL=F); the US benchmark'),
    ('NATURAL_GAS',  'COMMODITY', 'Natural Gas (Henry Hub)', 'yahoo',     'NG=F',             'USD', 'USD per MMBtu',        'DAILY',   0.001,  0.40, DATE '2000-09-01', 20,
     'US natural gas front-month future (NYMEX NG=F, Henry Hub)'),
    ('LNG',          'COMMODITY', 'LNG (Japan)',             'worldbank', 'Liquefied natural gas, Japan', 'USD', 'USD per MMBtu', 'MONTHLY', 0.01, NULL, DATE '1980-01-01', 30,
     'Liquefied natural gas imported by Japan, the benchmark of Asian LNG (Indonesia''s export market): monthly average of the World Bank Pink Sheet (no free daily source)'),
    ('COAL',         'COMMODITY', 'Coal (Newcastle)',        'worldbank', 'Coal, Australian', 'USD', 'USD per metric ton',   'MONTHLY', 0.01,   NULL, DATE '1979-01-01', 40,
     'Australian thermal coal, Newcastle 6,000 kcal/kg: monthly average of the World Bank Pink Sheet (no free daily source)'),
    -- metals
    ('GOLD',         'COMMODITY', 'Gold',                    'yahoo',     'GC=F',             'USD', 'USD per troy ounce',   'DAILY',   0.1,    0.08, DATE '2000-01-01', 50,
     'Gold front-month future (COMEX GC=F)'),
    ('SILVER',       'COMMODITY', 'Silver',                  'yahoo',     'SI=F',             'USD', 'USD per troy ounce',   'DAILY',   0.005,  0.15, DATE '2000-09-01', 60,
     'Silver front-month future (COMEX SI=F)'),
    ('COPPER',       'COMMODITY', 'Copper',                  'yahoo',     'HG=F',             'USD', 'USD per pound',        'DAILY',   0.0005, 0.25, DATE '2000-09-01', 70,
     'Copper front-month future (COMEX HG=F)'),
    ('ALUMINIUM',    'COMMODITY', 'Aluminium',               'yahoo',     'ALI=F',            'USD', 'USD per metric ton',   'DAILY',   0.25,   0.10, DATE '2014-05-01', 72,
     'Aluminium front-month future (COMEX ALI=F), tracks the LME price'),
    ('IRON_ORE',     'COMMODITY', 'Iron Ore (62% Fe)',       'yahoo',     'TIO=F',            'USD', 'USD per dry metric ton', 'DAILY', 0.01,   0.15, DATE '2010-11-01', 74,
     'Iron ore 62% Fe, CFR China (TSI) front-month future (CME / SGX TIO=F), the seaborne benchmark'),
    ('STEEL_HRC',    'COMMODITY', 'Steel (Hot-Rolled Coil)', 'yahoo',     'HRC=F',            'USD', 'USD per short ton',    'DAILY',   1,      0.15, DATE '2008-11-01', 76,
     'US Midwest domestic hot-rolled coil steel front-month future (COMEX HRC=F)'),
    ('NICKEL',       'COMMODITY', 'Nickel',                  'worldbank', 'Nickel',           'USD', 'USD per metric ton',   'MONTHLY', 0.01,   NULL, DATE '1980-01-01', 80,
     'Nickel (LME cash): monthly average of the World Bank Pink Sheet (no free daily source)'),
    ('TIN',          'COMMODITY', 'Tin',                     'worldbank', 'Tin',              'USD', 'USD per metric ton',   'MONTHLY', 0.01,   NULL, DATE '1980-01-01', 90,
     'Tin (LME cash): monthly average of the World Bank Pink Sheet (no free daily source)'),
    ('COBALT',       'COMMODITY', 'Cobalt',                  'imf',       'PCOBA',            'USD', 'USD per metric ton',   'MONTHLY', 0.01,   NULL, DATE '1992-01-01', 95,
     'Cobalt, minimum 99.80% purity, LME spot: monthly average of the IMF Primary Commodity Prices (no free daily source)'),
    -- agriculture
    ('PALM_OIL',     'COMMODITY', 'Palm Oil (USD)',          'yahoo',     'CPO=F',            'USD', 'USD per metric ton',   'DAILY',   0.25,   0.10, DATE '2010-01-01', 100,
     'USD Malaysian crude palm oil calendar future (CME CPO=F)'),
    ('PALM_OIL_MYR', 'COMMODITY', 'Palm Oil (MYR)',          'yahoo',     'CPO=F*MYR=X',      'MYR', 'MYR per metric ton',   'DAILY',   1,      0.10, DATE '2010-06-01', 110,
     'Crude palm oil in ringgit: CME CPO=F (cash-settled on Bursa Malaysia''s FCPO) x USD/MYR (MYR=X) of the same day. An approximation of Bursa Malaysia FCPO, which has no free source; not Bursa''s own price'),
    ('SOYBEAN_OIL',  'COMMODITY', 'Soybean Oil',             'yahoo',     'ZL=F',             'USD', 'US cents per pound',   'DAILY',   0.01,   0.10, DATE '2000-04-01', 120,
     'Soybean oil front-month future (CBOT ZL=F), in US cents per pound as quoted'),
    ('SOYBEAN_MEAL', 'COMMODITY', 'Soybean Meal',            'yahoo',     'ZM=F',             'USD', 'USD per short ton',    'DAILY',   0.1,    0.15, DATE '2000-06-01', 130,
     'Soybean meal front-month future (CBOT ZM=F)'),
    ('CORN',         'COMMODITY', 'Corn',                    'yahoo',     'ZC=F',             'USD', 'US cents per bushel',  'DAILY',   0.25,   0.12, DATE '2000-08-01', 140,
     'Corn front-month future (CBOT ZC=F), in US cents per bushel as quoted'),
    ('WHEAT',        'COMMODITY', 'Wheat',                   'yahoo',     'ZW=F',             'USD', 'US cents per bushel',  'DAILY',   0.25,   0.15, DATE '2000-08-01', 150,
     'Chicago soft red winter wheat front-month future (CBOT ZW=F), in US cents per bushel as quoted'),
    ('SUGAR',        'COMMODITY', 'Sugar',                   'yahoo',     'SB=F',             'USD', 'US cents per pound',   'DAILY',   0.01,   0.18, DATE '2000-04-01', 160,
     'Raw sugar #11 front-month future (ICE SB=F), in US cents per pound as quoted'),
    ('RUBBER',       'COMMODITY', 'Rubber (TSR 20)',         'worldbank', 'Rubber, TSR20',    'USD', 'USD per kg',           'MONTHLY', 0.01,   NULL, DATE '1999-01-01', 170,
     'Natural rubber TSR 20, the grade Indonesia exports as SIR 20: monthly average of the World Bank Pink Sheet (no free daily source)'),
    ('PULP',         'COMMODITY', 'Wood Pulp (US PPI)',      'fred',      'WPU0911',          'USD', 'index, 1982 = 100',    'MONTHLY', 0.001,  NULL, DATE '1990-01-01', 180,
     'US producer price index of wood pulp (BLS, FRED WPU0911), 1982 = 100: an index, not a price - no free pulp price exists'),
    -- fertilizers
    ('UREA',         'COMMODITY', 'Urea',                    'worldbank', 'Urea',             'USD', 'USD per metric ton',   'MONTHLY', 0.01,   NULL, DATE '1980-01-01', 190,
     'Urea: monthly average of the World Bank Pink Sheet (no free daily source)'),
    ('DAP',          'COMMODITY', 'DAP (Diammonium Phosphate)', 'worldbank', 'DAP',           'USD', 'USD per metric ton',   'MONTHLY', 0.01,   NULL, DATE '1980-01-01', 200,
     'Diammonium phosphate fertilizer: monthly average of the World Bank Pink Sheet (no free daily source)'),
    ('POTASH',       'COMMODITY', 'Potash (Potassium Chloride)', 'worldbank', 'Potassium chloride', 'USD', 'USD per metric ton', 'MONTHLY', 0.01, NULL, DATE '1980-01-01', 210,
     'Potassium chloride (muriate of potash) fertilizer: monthly average of the World Bank Pink Sheet (no free daily source)')
),
updated AS (
    UPDATE market_instrument m SET
        market = v.market, name = v.name, source = v.source, source_symbol = v.source_symbol,
        quote_currency = v.quote_currency, unit = v.unit, frequency = v.frequency,
        tick_size = v.tick_size, max_spike = v.max_spike, history_from = v.history_from,
        sort_order = v.sort_order,
        description = v.description
    FROM v
    WHERE m.instrument_code = v.instrument_code
      AND (m.market, m.name, m.source, m.source_symbol, m.quote_currency, m.unit, m.frequency, m.tick_size,
           m.max_spike, m.history_from, m.sort_order, m.description)
          IS DISTINCT FROM
          (v.market, v.name, v.source, v.source_symbol, v.quote_currency, v.unit, v.frequency, v.tick_size,
           v.max_spike, v.history_from, v.sort_order, v.description)
    RETURNING m.instrument_code
)
INSERT INTO market_instrument (instrument_code, market, name, source, source_symbol, quote_currency, unit, frequency,
                               tick_size, max_spike, history_from, sort_order, description)
SELECT v.instrument_code, v.market, v.name, v.source, v.source_symbol, v.quote_currency, v.unit, v.frequency,
       v.tick_size, v.max_spike, v.history_from, v.sort_order, v.description
FROM v
WHERE NOT EXISTS (SELECT 1 FROM market_instrument m WHERE m.instrument_code = v.instrument_code);

INSERT INTO macro_series_def (series_code, name, country, category, unit, frequency, source, source_ref, source_url,
                              release_lag_days, priority, used_by, min_value, max_value, max_change, history_from,
                              sort_order, description)
VALUES
    -- Indonesia
    ('BI_RATE', 'BI Rate (policy rate)', 'ID', 'RATES', '%', 'EVENT', 'bi',
     'https://www.bi.go.id/en/statistik/indikator/bi-rate.aspx', 'https://www.bi.go.id/en/statistik/indikator/bi-rate.aspx',
     0, 1, 'DRUCKENMILLER,TUDOR_JONES', 0, 30, 1.5, DATE '2016-01-01', 10,
     'Bank Indonesia policy rate (BI-Rate, formerly the BI 7-Day Reverse Repo Rate) on each board meeting date'),
    ('ID_CPI_YOY', 'Indonesia CPI inflation (YoY)', 'ID', 'INFLATION', '%', 'MONTHLY', 'bi',
     'https://www.bi.go.id/en/statistik/indikator/data-inflasi.aspx', 'https://www.bi.go.id/en/statistik/indikator/data-inflasi.aspx',
     2, 1, 'DRUCKENMILLER,TUDOR_JONES', -5, 100, 5, DATE '2003-01-01', 20,
     'Consumer price inflation, year on year (BPS, as published by Bank Indonesia); released on the first working day of the next month'),
    ('SBN_10Y', 'Indonesia 10-year government bond yield', 'ID', 'RATES', '%', 'DAILY', 'cnbc',
     'ID10Y-ID', 'https://www.cnbc.com/quotes/ID10Y-ID',
     0, 1, 'DRUCKENMILLER,TUDOR_JONES', 0, 30, 1.5, DATE '2011-01-01', 30,
     'Benchmark 10-year SBN yield, daily close (CNBC)'),
    ('ID_FX_RESERVES', 'BI foreign exchange reserves', 'ID', 'LIQUIDITY', 'USD mn', 'MONTHLY', 'bi_seki',
     'TABEL5_9.xls#Total', 'https://www.bi.go.id/SEKI/tabel/TABEL5_9.xls',
     8, 2, 'DRUCKENMILLER', 1000, 1000000, 20000, DATE '2010-01-01', 40,
     'Official reserve assets at the end of the month (SEKI table V.9); released about a week after'),
    ('ID_M2', 'Indonesia broad money (M2)', 'ID', 'LIQUIDITY', 'IDR bn', 'MONTHLY', 'bi_seki',
     'TABEL1_1_1.xls#Uang Beredar Luas', 'https://www.bi.go.id/SEKI/tabel/TABEL1_1_1.xls',
     31, 2, 'DRUCKENMILLER', 10000, 100000000, 1000000, DATE '2011-01-01', 50,
     'Broad money M2, reclassified series (SEKI table I.1A); released about a month after the month end'),
    ('ID_FOREIGN_NET_BUY', 'Net foreign buy / sell on IDX', 'ID', 'FLOWS', 'IDR bn', 'DAILY', 'manual',
     NULL, 'https://www.idx.co.id/en/market-data/trading-summary/',
     0, 2, 'DRUCKENMILLER,TUDOR_JONES', -100000, 100000, NULL, DATE '2015-01-01', 60,
     'Foreign buy minus foreign sell value of all IDX markets per day. The IDX site answers automated requests with a bot challenge: upload a CSV'),
    ('ID_SBN_FOREIGN', 'Foreign ownership of tradable SBN', 'ID', 'FLOWS', 'IDR tn', 'DAILY', 'manual',
     NULL, 'https://www.djppr.kemenkeu.go.id/',
     1, 2, 'DRUCKENMILLER', 0, 10000, NULL, DATE '2015-01-01', 70,
     'Tradable government securities held by non-residents (DJPPR ownership report): upload a CSV'),
    ('ID_CDS_5Y', 'Indonesia 5-year CDS', 'ID', 'RISK', 'bps', 'DAILY', 'manual',
     NULL, NULL,
     0, 3, 'TUDOR_JONES', 0, 2000, NULL, DATE '2015-01-01', 80,
     'Credit default swap spread of the Republic of Indonesia: no free source, upload a CSV'),
    -- United States
    ('US_FED_FUNDS', 'Effective Fed Funds rate', 'US', 'RATES', '%', 'DAILY', 'fred',
     'DFF', 'https://fred.stlouisfed.org/series/DFF',
     1, 1, 'DRUCKENMILLER,TUDOR_JONES', 0, 25, 1, DATE '2000-01-01', 110, 'Federal funds effective rate'),
    ('US_10Y', 'US Treasury 10-year yield', 'US', 'RATES', '%', 'DAILY', 'fred',
     'DGS10', 'https://fred.stlouisfed.org/series/DGS10',
     1, 1, 'DRUCKENMILLER,TUDOR_JONES', 0, 20, 1, DATE '2000-01-01', 120, 'Market yield on 10-year Treasuries, constant maturity'),
    ('US_2Y', 'US Treasury 2-year yield', 'US', 'RATES', '%', 'DAILY', 'fred',
     'DGS2', 'https://fred.stlouisfed.org/series/DGS2',
     1, 1, 'DRUCKENMILLER,TUDOR_JONES', 0, 20, 1, DATE '2000-01-01', 130, 'Market yield on 2-year Treasuries, constant maturity'),
    ('US_CPI_INDEX', 'US CPI (all items)', 'US', 'INFLATION', 'index 1982-84=100', 'MONTHLY', 'fred',
     'CPIAUCSL', 'https://fred.stlouisfed.org/series/CPIAUCSL',
     15, 1, 'TUDOR_JONES,DRUCKENMILLER', 10, 1000, 10, DATE '1999-01-01', 140,
     'Consumer price index, seasonally adjusted; the year-on-year inflation is derived from it'),
    ('US_FED_ASSETS', 'Fed total assets', 'US', 'LIQUIDITY', 'USD mn', 'WEEKLY', 'fred',
     'WALCL', 'https://fred.stlouisfed.org/series/WALCL',
     1, 1, 'DRUCKENMILLER', 100000, 20000000, 500000, DATE '2002-12-01', 150,
     'Fed balance sheet, Wednesday level (H.4.1, released Thursday)'),
    ('US_HY_SPREAD', 'US high-yield credit spread', 'US', 'CREDIT', '%', 'DAILY', 'fred',
     'BAMLH0A0HYM2', 'https://fred.stlouisfed.org/series/BAMLH0A0HYM2',
     1, 1, 'DRUCKENMILLER,TUDOR_JONES', 0, 30, 3, DATE '2000-01-01', 160,
     'ICE BofA US High Yield Index option-adjusted spread'),
    ('DXY', 'US Dollar Index', 'US', 'FX', 'index', 'DAILY', 'yahoo',
     'DX-Y.NYB', 'https://finance.yahoo.com/quote/DX-Y.NYB',
     1, 1, 'DRUCKENMILLER,TUDOR_JONES', 50, 200, 5, DATE '2000-01-01', 170,
     'ICE US Dollar Index futures continuation (Yahoo Finance DX-Y.NYB)'),
    ('US_TGA', 'Treasury General Account', 'US', 'LIQUIDITY', 'USD mn', 'WEEKLY', 'fred',
     'WTREGEN', 'https://fred.stlouisfed.org/series/WTREGEN',
     1, 2, 'DRUCKENMILLER', 0, 5000000, 500000, DATE '2002-12-01', 180,
     'Treasury deposits at the Fed (Wednesday level); drains liquidity when it rises'),
    ('US_RRP', 'Fed overnight reverse repo', 'US', 'LIQUIDITY', 'USD bn', 'DAILY', 'fred',
     'RRPONTSYD', 'https://fred.stlouisfed.org/series/RRPONTSYD',
     1, 2, 'DRUCKENMILLER', 0, 5000, 1000, DATE '2003-01-01', 190,
     'Overnight reverse repurchase agreements; net liquidity = Fed assets - TGA - RRP'),
    ('US_BREAKEVEN_10Y', '10-year breakeven inflation', 'US', 'INFLATION', '%', 'DAILY', 'fred',
     'T10YIE', 'https://fred.stlouisfed.org/series/T10YIE',
     1, 2, 'TUDOR_JONES', -5, 10, 1, DATE '2003-01-01', 200,
     'Inflation the bond market expects over 10 years (10Y nominal - 10Y TIPS)'),
    ('US_CORE_CPI_INDEX', 'US core CPI', 'US', 'INFLATION', 'index 1982-84=100', 'MONTHLY', 'fred',
     'CPILFESL', 'https://fred.stlouisfed.org/series/CPILFESL',
     15, 2, 'TUDOR_JONES', 10, 1000, 10, DATE '1999-01-01', 210,
     'CPI less food and energy, seasonally adjusted; the year-on-year rate is derived from it'),
    ('US_M2', 'US money supply (M2)', 'US', 'LIQUIDITY', 'USD bn', 'MONTHLY', 'fred',
     'M2SL', 'https://fred.stlouisfed.org/series/M2SL',
     25, 2, 'DRUCKENMILLER', 100, 1000000, 2000, DATE '1999-01-01', 220, 'M2, seasonally adjusted'),
    ('VIX', 'CBOE Volatility Index', 'US', 'RISK', 'index', 'DAILY', 'fred',
     'VIXCLS', 'https://fred.stlouisfed.org/series/VIXCLS',
     1, 2, 'TUDOR_JONES', 1, 150, 40, DATE '2000-01-01', 230, 'Implied volatility of the S&P 500, daily close'),
    -- global / positioning
    ('COT_GOLD_NET', 'Speculative net position: gold', 'GLOBAL', 'POSITIONING', 'contracts', 'WEEKLY', 'cftc',
     '088691', 'https://www.cftc.gov/MarketReports/CommitmentsofTraders/index.htm',
     3, 3, 'TUDOR_JONES', -10000000, 10000000, NULL, DATE '2000-01-01', 310,
     'Non-commercial long minus short positions, COMEX gold (Tuesday positions, released Friday)'),
    ('COT_CRUDE_NET', 'Speculative net position: crude oil', 'GLOBAL', 'POSITIONING', 'contracts', 'WEEKLY', 'cftc',
     '067651', 'https://www.cftc.gov/MarketReports/CommitmentsofTraders/index.htm',
     3, 3, 'TUDOR_JONES', -10000000, 10000000, NULL, DATE '2000-01-01', 320,
     'Non-commercial long minus short positions, NYMEX WTI crude oil'),
    ('COT_USD_NET', 'Speculative net position: US dollar index', 'GLOBAL', 'POSITIONING', 'contracts', 'WEEKLY', 'cftc',
     '098662', 'https://www.cftc.gov/MarketReports/CommitmentsofTraders/index.htm',
     3, 3, 'TUDOR_JONES', -10000000, 10000000, NULL, DATE '2000-01-01', 330,
     'Non-commercial long minus short positions, ICE US Dollar Index'),
    ('CN_PMI_MFG', 'China manufacturing PMI', 'CN', 'ACTIVITY', 'index', 'MONTHLY', 'manual',
     NULL, 'https://www.stats.gov.cn/english/',
     1, 3, 'DRUCKENMILLER', 20, 80, NULL, DATE '2010-01-01', 340,
     'Official NBS manufacturing PMI (demand for Indonesian exports): upload a CSV')
ON CONFLICT (series_code) DO UPDATE SET
    name = EXCLUDED.name, country = EXCLUDED.country, category = EXCLUDED.category, unit = EXCLUDED.unit,
    frequency = EXCLUDED.frequency, source = EXCLUDED.source, source_ref = EXCLUDED.source_ref,
    source_url = EXCLUDED.source_url, release_lag_days = EXCLUDED.release_lag_days, priority = EXCLUDED.priority,
    used_by = EXCLUDED.used_by, min_value = EXCLUDED.min_value, max_value = EXCLUDED.max_value,
    max_change = EXCLUDED.max_change, history_from = EXCLUDED.history_from, sort_order = EXCLUDED.sort_order,
    description = EXCLUDED.description;
