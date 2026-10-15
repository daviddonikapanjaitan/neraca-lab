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
--   FRED                      fredgraph.csv?id=... (no API key)
--   Bank Indonesia            BI-Rate and inflation pages (their "Download" export), SEKI tables (.xls)
--   CNBC                      ID10Y-ID daily bars (2011-)
--   CFTC                      Commitments of Traders, legacy futures only (publicreporting.cftc.gov 6dca-aqww)
--   manual                    no free source a program may read: IDX foreign flows (the site answers a bot
--                             challenge), SBN foreign ownership, Indonesia CDS, China PMI; CSV upload
--
-- Runs on every start. Idempotent: a row is inserted or brought back to this definition.
-- =====================================================================

INSERT INTO market_instrument (instrument_code, market, name, source, source_symbol, quote_currency, unit, frequency,
                               tick_size, max_spike, history_from, sort_order, description)
VALUES
    ('USDIDR',   'FOREX',     'USD/IDR',             'yahoo',     'IDR=X',            'IDR', 'IDR per USD',          'DAILY',   1,     0.03, DATE '2001-01-01', 10,
     'US dollar in Indonesian rupiah (Yahoo Finance IDR=X, London close)'),
    ('USDJPY',   'FOREX',     'USD/JPY',             'yahoo',     'JPY=X',            'JPY', 'JPY per USD',          'DAILY',   0.001, 0.05, DATE '1996-01-01', 20,
     'US dollar in Japanese yen (Yahoo Finance JPY=X, London close)'),
    ('BRENT',    'COMMODITY', 'Crude Oil (Brent)',   'yahoo',     'BZ=F',             'USD', 'USD per barrel',       'DAILY',   0.01,  0.15, DATE '2007-01-01', 10,
     'Brent crude oil front-month future (ICE / NYMEX BZ=F); the benchmark of Indonesian crude (ICP)'),
    ('GOLD',     'COMMODITY', 'Gold',                'yahoo',     'GC=F',             'USD', 'USD per troy ounce',   'DAILY',   0.1,   0.08, DATE '2000-01-01', 20,
     'Gold front-month future (COMEX GC=F)'),
    ('COAL',     'COMMODITY', 'Coal (Newcastle)',    'worldbank', 'Coal, Australian', 'USD', 'USD per metric ton',   'MONTHLY', 0.01,  NULL, DATE '1979-01-01', 30,
     'Australian thermal coal, Newcastle 6,000 kcal/kg: monthly average of the World Bank Pink Sheet (no free daily source)'),
    ('PALM_OIL', 'COMMODITY', 'Palm Oil',            'yahoo',     'CPO=F',            'USD', 'USD per metric ton',   'DAILY',   0.25,  0.10, DATE '2010-01-01', 40,
     'USD Malaysian crude palm oil calendar future (CME CPO=F)')
ON CONFLICT (instrument_code) DO UPDATE SET
    market = EXCLUDED.market, name = EXCLUDED.name, source = EXCLUDED.source, source_symbol = EXCLUDED.source_symbol,
    quote_currency = EXCLUDED.quote_currency, unit = EXCLUDED.unit, frequency = EXCLUDED.frequency,
    tick_size = EXCLUDED.tick_size, max_spike = EXCLUDED.max_spike, history_from = EXCLUDED.history_from,
    sort_order = EXCLUDED.sort_order,
    description = EXCLUDED.description;

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
