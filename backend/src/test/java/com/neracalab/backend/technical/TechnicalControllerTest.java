package com.neracalab.backend.technical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.neracalab.backend.auth.TestLogins;
import com.neracalab.backend.technical.idx.IdxTickSize;

import tools.jackson.databind.json.JsonMapper;

/**
 * The technical analysis API against the Docker Postgres (the HRTA seed is loaded on every start; the IHSG bars and
 * events need a refresh, which these tests do not call - they never reach Yahoo Finance). Changes are rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TechnicalControllerTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private JsonMapper json;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private TechnicalProperties properties;

    private MockMvc mvc;
    private String token;

    @BeforeEach
    void login() {
        token = TestLogins.rootToken(context);
        mvc = TestLogins.mockMvc(context, token);
    }

    @AfterEach
    void logout() {
        TestLogins.logout(context, token);
    }

    @Test
    void tickerSearchByCodeAndName() throws Exception {
        mvc.perform(get("/api/v1/technical/tickers?exchange=IDX&query=hrta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ticker").value("HRTA"))
                .andExpect(jsonPath("$[0].exchange").value("IDX"));
        mvc.perform(get("/api/v1/technical/tickers?exchange=IDX&query=hartadinata"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ticker").value("HRTA"));
        mvc.perform(get("/api/v1/technical/tickers?exchange=NYSE")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Unsupported exchange"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void dailyAndWeeklyBars() throws Exception {
        Map<String, Object> daily = getJson("/api/v1/technical/IDX/HRTA/ohlcv?interval=D&range=1Y");
        assertThat(daily.get("columns")).isEqualTo(List.of("time", "open", "high", "low", "close", "volume", "value"));
        List<List<Number>> bars = (List<List<Number>>) daily.get("bars");
        assertThat(bars).hasSizeGreaterThan(200);
        for (List<Number> b : bars) {
            assertThat(b.get(2).doubleValue()).isGreaterThanOrEqualTo(b.get(3).doubleValue());       // high >= low
            assertThat(b.get(2).doubleValue()).isGreaterThanOrEqualTo(b.get(4).doubleValue());
        }
        // ascending, one-year window ending at the last trading day
        assertThat(bars.getFirst().get(0).longValue()).isLessThan(bars.getLast().get(0).longValue());
        LocalDate last = LocalDate.parse((String) daily.get("lastDate"));
        assertThat(bars.getLast().get(0).longValue()).isEqualTo(last.atStartOfDay().toEpochSecond(java.time.ZoneOffset.UTC));
        Map<String, Object> weekly = getJson("/api/v1/technical/IDX/HRTA/ohlcv?interval=W&range=1Y");
        assertThat((List<?>) weekly.get("bars")).hasSizeBetween(50, 55);
        Map<String, Object> total = getJson("/api/v1/technical/IDX/HRTA/ohlcv?interval=D&range=1Y&adjustment=total");
        assertThat(total.get("adjustment")).isEqualTo("total");
        mvc.perform(get("/api/v1/technical/IDX/HRTA/ohlcv?adjusted=false")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/technical/IDX/HRTA/ohlcv?interval=H")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Unknown interval 'H': use D, W or M"));
        mvc.perform(get("/api/v1/technical/IDX/HRTA/ohlcv?from=2026-02-01&to=2026-01-01")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/technical/IDX/ZZZZ/ohlcv")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Company not found"));
        mvc.perform(get("/api/v1/technical/IDX/not-a-ticker!/ohlcv")).andExpect(status().isBadRequest());
    }

    @Test
    @SuppressWarnings("unchecked")
    void indicatorSeries() throws Exception {
        Map<String, Object> out = getJson("/api/v1/technical/IDX/HRTA/indicators?interval=D&range=6M&set=sma50,bb,macd,rsi14");
        Map<String, Object> series = (Map<String, Object>) out.get("series");
        assertThat(series.keySet()).containsExactly("sma50", "bb.lower", "bb.middle", "bb.upper", "bb.width", "macd.histogram",
                "macd.line", "macd.signal", "rsi14");
        List<List<Number>> rsi = (List<List<Number>>) series.get("rsi14");
        assertThat(rsi).isNotEmpty().allSatisfy(p -> assertThat(p.get(1).doubleValue()).isBetween(0.0, 100.0));
        mvc.perform(get("/api/v1/technical/IDX/HRTA/indicators?set=sma13")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/technical/IDX/HRTA/indicators")).andExpect(status().isBadRequest());
    }

    @Test
    @SuppressWarnings("unchecked")
    void levelsAreValidIdxPrices() throws Exception {
        Map<String, Object> levels = getJson("/api/v1/technical/IDX/HRTA/levels");
        IdxTickSize ticks = new IdxTickSize(properties.tickSizes());
        List<Map<String, Object>> rows = (List<Map<String, Object>>) levels.get("keyLevels");
        assertThat(rows).isNotEmpty();
        for (Map<String, Object> row : rows) {
            assertThat(ticks.isValid(new BigDecimal(row.get("price").toString()))).as("%s", row).isTrue();
        }
        assertThat((List<?>) ((Map<String, Object>) levels.get("volumeProfile")).get("buckets"))
                .hasSize(properties.volumeProfile().buckets());
    }

    @Test
    void snapshotIsVersionedAndComplete() throws Exception {
        mvc.perform(get("/api/v1/technical/IDX/HRTA/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(3))
                .andExpect(jsonPath("$.macro").doesNotExist())
                .andExpect(jsonPath("$.ticker").value("HRTA"))
                .andExpect(jsonPath("$.trend.sma200").isNumber())
                .andExpect(jsonPath("$.momentum.macd.histogram").isNumber())
                .andExpect(jsonPath("$.structure.week52.high").isNumber())
                .andExpect(jsonPath("$.risk.lotSize").doesNotExist())
                .andExpect(jsonPath("$.price.lotSize").value(100))
                .andExpect(jsonPath("$.notes").isArray());
    }

    @Test
    void eventsIncludeSplitsFromTheFilings() throws Exception {
        mvc.perform(get("/api/v1/technical/IDX/MAPA/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[?(@.type == 'STOCK_SPLIT' && @.source == 'corporate_action')]").isNotEmpty());
    }

    @Test
    void marketPanel() throws Exception {
        mvc.perform(get("/api/v1/technical/market?exchange=IDX"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.index").value("IHSG"))
                .andExpect(jsonPath("$.breadth.universe").isNumber())
                .andExpect(jsonPath("$.regime").exists())
                // technical only: the macro data are on the Macro page
                .andExpect(jsonPath("$.macro").doesNotExist())
                .andExpect(jsonPath("$.macroSignals").doesNotExist());
    }

    /**
     * A USD reporter's bars are converted back to the IDX quote with the rate they were stored with; the 8 decimals
     * of the stored USD price leave a residue (0.03009362 x 17,944 = 539.9999...), so a converted bar is rounded to
     * 2 decimals: the quote itself.
     */
    @Test
    @SuppressWarnings("unchecked")
    void convertedBarsAreTheIdxQuote() throws Exception {
        long id = jdbc.sql("""
                        INSERT INTO company (ticker, exchange, company_name, currency, active)
                        VALUES ('ZZUS', 'IDX', 'USD Reporter Test', 'USD', true) RETURNING company_id""")
                .query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO fx_rate_daily (base_currency, quote_currency, rate_date, rate, source)
                        VALUES ('USD', 'IDR', DATE '2098-01-02', 17944, 'test')""").update();
        jdbc.sql("""
                        INSERT INTO price_daily (company_id, trading_date, open_price, high_price, low_price, close_price,
                                                 adjusted_close, volume)
                        VALUES (:id, DATE '2098-01-05', 0.03065195, 0.03093061, 0.02980597, 0.03009362, 0.03009362, 1000)""")
                .param("id", id).update();

        Map<String, Object> bars = getJson("/api/v1/technical/IDX/ZZUS/ohlcv?interval=D");

        assertThat(bars.get("currency")).isEqualTo("IDR");
        // columns: time, open, high, low, close, volume, value
        List<?> bar = (List<?>) ((List<?>) bars.get("bars")).getFirst();
        assertThat(((Number) bar.get(4)).doubleValue()).isEqualTo(540.0);
        assertThat(((Number) bar.get(1)).doubleValue()).isEqualTo(550.02);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shortHistoryIsFlaggedAndStillServed() throws Exception {
        long id = jdbc.sql("""
                        INSERT INTO company (ticker, exchange, company_name, currency, active)
                        VALUES ('ZZTA', 'IDX', 'Short History Test', 'IDR', true) RETURNING company_id""")
                .query(Long.class).single();
        LocalDate d = LocalDate.of(2026, 8, 3);
        for (int i = 0; i < 30; i++) {
            while (d.getDayOfWeek().getValue() > 5) {
                d = d.plusDays(1);
            }
            jdbc.sql("""
                            INSERT INTO price_daily (company_id, trading_date, open_price, high_price, low_price, close_price,
                                                     adjusted_close, volume)
                            VALUES (:id, :d, :p, :p + 10, :p - 10, :p, :p, 1000)""")
                    .param("id", id).param("d", d).param("p", 500 + i * 2).update();
            d = d.plusDays(1);
        }
        Map<String, Object> bars = getJson("/api/v1/technical/IDX/ZZTA/ohlcv?interval=D");
        assertThat(bars.get("limitedHistory")).isEqualTo(true);
        assertThat((List<?>) bars.get("bars")).hasSize(30);
        Map<String, Object> snapshot = getJson("/api/v1/technical/IDX/ZZTA/snapshot");
        assertThat(((Map<String, Object>) snapshot.get("history")).get("limited")).isEqualTo(true);
        assertThat(((Map<String, Object>) snapshot.get("trend")).get("sma50")).isNull();
        Map<String, Object> ind = getJson("/api/v1/technical/IDX/ZZTA/indicators?set=sma50,sma20");
        assertThat((List<?>) ((Map<String, Object>) ind.get("series")).get("sma50")).isEmpty();
        assertThat((List<?>) ((Map<String, Object>) ind.get("series")).get("sma20")).hasSize(11);
        // a listing without prices
        jdbc.sql("INSERT INTO company (ticker, exchange, company_name, currency, active) VALUES ('ZZTB', 'IDX', 'No Prices', 'IDR', true)")
                .update();
        mvc.perform(get("/api/v1/technical/IDX/ZZTB/ohlcv")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("No prices"));
    }

    /** 300 weekday IHSG bars up to 2026-10-14, rising from 6,500 (the stored bars are replaced; rolled back). */
    private void syntheticIhsg() {
        syntheticIndex("IHSG", 6500, 3.17);
    }

    /** 300 weekday bars of an index up to 2026-10-14, rising from {@code base} by {@code step} a day (rolled back). */
    private void syntheticIndex(String code, double base, double step) {
        jdbc.sql("DELETE FROM index_daily WHERE index_code = :code").param("code", code).update();
        LocalDate d = LocalDate.of(2026, 10, 14);
        for (int i = 0; i < 300; i++) {
            while (d.getDayOfWeek().getValue() > 5) {
                d = d.minusDays(1);
            }
            double close = base + (299 - i) * step + (i % 7) * base / 565;
            jdbc.sql("""
                            INSERT INTO index_daily (index_code, exchange, trading_date, open_price, high_price, low_price,
                                                     close_price, volume, source)
                            VALUES (:code, 'IDX', :d, :p - 0.5, :p + 2.25, :p - 2.25, :p, 300000000, 'test')""")
                    .param("code", code).param("d", d)
                    .param("p", BigDecimal.valueOf(close).setScale(2, java.math.RoundingMode.HALF_UP)).update();
            d = d.minusDays(1);
        }
    }

    /**
     * LQ45 (^JKLQ45) is a listing like IHSG - in index points, levels at 0.01, no events, no lot - found after IHSG, by
     * its code or its Yahoo symbol, and compared with IHSG (relative strength), which IHSG is not with itself.
     */
    @Test
    @SuppressWarnings("unchecked")
    void lq45IsAnIndexListingComparedWithIhsg() throws Exception {
        syntheticIhsg();
        syntheticIndex("LQ45", 900, 0.6);
        mvc.perform(get("/api/v1/technical/tickers?exchange=IDX&query=lq45"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ticker").value("LQ45"))
                .andExpect(jsonPath("$[0].type").value("INDEX"))
                .andExpect(jsonPath("$[0].companyName").value("IDX LQ45 Index (LQ45)"));
        mvc.perform(get("/api/v1/technical/tickers?exchange=IDX&query=index"))
                .andExpect(jsonPath("$[0].ticker").value("IHSG"))
                .andExpect(jsonPath("$[1].ticker").value("LQ45"));
        mvc.perform(get("/api/v1/technical/tickers?exchange=IDX&query=composite"))
                .andExpect(jsonPath("$[0].ticker").value("IHSG"))
                .andExpect(jsonPath("$[?(@.ticker == 'LQ45')]").isEmpty());

        Map<String, Object> bars = getJson("/api/v1/technical/IDX/LQ45/ohlcv?interval=D&range=1Y");
        assertThat(bars.get("type")).isEqualTo("INDEX");
        assertThat(bars.get("ticker")).isEqualTo("LQ45");
        assertThat(bars.get("lotSize")).isNull();
        assertThat((String) bars.get("priceBasis")).contains("^JKLQ45");
        assertThat(bars.get("source")).isEqualTo("Yahoo Finance ^JKLQ45");
        List<List<Object>> rows = (List<List<Object>>) bars.get("bars");
        assertThat(((Number) rows.getLast().get(4)).doubleValue()).as("LQ45's own bars, not IHSG's").isLessThan(1200);
        mvc.perform(get(java.net.URI.create("/api/v1/technical/IDX/%5EJKLQ45/ohlcv?interval=W")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticker").value("LQ45"));

        Map<String, Object> rs = (Map<String, Object>) getJson("/api/v1/technical/IDX/LQ45/indicators?set=rs&range=1Y")
                .get("series");
        assertThat((List<?>) rs.get("rs")).isNotEmpty();
        Map<String, Object> levels = getJson("/api/v1/technical/IDX/LQ45/levels");
        for (Map<String, Object> level : (List<Map<String, Object>>) levels.get("keyLevels")) {
            BigDecimal price = new BigDecimal(String.valueOf(level.get("price")));
            assertThat(price.stripTrailingZeros().scale()).as(level.toString()).isLessThanOrEqualTo(2);
        }
        Map<String, Object> snapshot = getJson("/api/v1/technical/IDX/LQ45/snapshot");
        assertThat(((Map<String, Object>) snapshot.get("relativeStrength")).get("ratio")).as("vs IHSG").isNotNull();
        assertThat(((Map<String, Object>) snapshot.get("price")).get("lotSize")).isNull();
        assertThat((String) snapshot.get("priceBasis")).contains("^JKLQ45");
        assertThat((List<String>) snapshot.get("notes")).noneMatch(n -> n.startsWith("No volume"));
        assertThat(jdbc.sql("SELECT count(*) FROM indicator_snapshot WHERE company_id < 0").query(Long.class).single()).isZero();
        assertThat((List<?>) getJson("/api/v1/technical/IDX/LQ45/events").get("events")).isEmpty();
        Map<String, Object> summary = getJson("/api/v1/technical/IDX/LQ45/summary");
        assertThat(summary.get("type")).isEqualTo("INDEX");
        assertThat(summary.get("ticker")).isEqualTo("LQ45");
        assertSummaries(summary, null);
        // IHSG is still compared with nothing
        Map<String, Object> ihsg = getJson("/api/v1/technical/IDX/IHSG/snapshot");
        assertThat(((Map<String, Object>) ihsg.get("relativeStrength")).get("ratio")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theIndexIsAListingInIndexPoints() throws Exception {
        syntheticIhsg();
        mvc.perform(get("/api/v1/technical/tickers?exchange=IDX&query=ihsg"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ticker").value("IHSG"))
                .andExpect(jsonPath("$[0].type").value("INDEX"))
                .andExpect(jsonPath("$[0].companyName").value("IDX Composite Index (IHSG)"))
                .andExpect(jsonPath("$[0].tradingDays").value(300));
        mvc.perform(get("/api/v1/technical/tickers?exchange=IDX&query=hrta"))
                .andExpect(jsonPath("$[0].ticker").value("HRTA"))
                .andExpect(jsonPath("$[0].type").value("STOCK"));

        Map<String, Object> bars = getJson("/api/v1/technical/IDX/IHSG/ohlcv?interval=D&range=1Y");
        assertThat(bars.get("type")).isEqualTo("INDEX");
        assertThat(bars.get("lotSize")).isNull();
        assertThat(bars.get("lastDate")).isEqualTo("2026-10-14");
        assertThat((List<?>) bars.get("autoRejection")).isEmpty();
        assertThat((String) bars.get("priceBasis")).contains("index points");
        // the Yahoo symbol works too (encoded as the browser sends it)
        mvc.perform(get(java.net.URI.create("/api/v1/technical/IDX/%5EJKSE/ohlcv?interval=W")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticker").value("IHSG"));

        Map<String, Object> levels = getJson("/api/v1/technical/IDX/IHSG/levels");
        for (Map<String, Object> level : (List<Map<String, Object>>) levels.get("keyLevels")) {
            BigDecimal price = new BigDecimal(String.valueOf(level.get("price")));
            assertThat(price.stripTrailingZeros().scale()).as(level.toString()).isLessThanOrEqualTo(2);
        }
        Map<String, Object> snapshot = getJson("/api/v1/technical/IDX/IHSG/snapshot");
        assertThat(((Map<String, Object>) snapshot.get("relativeStrength")).get("ratio")).isNull();
        assertThat(((Map<String, Object>) snapshot.get("price")).get("lotSize")).isNull();
        assertThat(((Map<String, Object>) snapshot.get("risk")).get("araCount60")).isNull();
        assertThat(jdbc.sql("SELECT count(*) FROM indicator_snapshot WHERE company_id = -1").query(Long.class).single()).isZero();
        Map<String, Object> events = getJson("/api/v1/technical/IDX/IHSG/events");
        assertThat((List<?>) events.get("events")).isEmpty();

        Map<String, Object> summary = getJson("/api/v1/technical/IDX/IHSG/summary");
        assertThat(summary.get("type")).isEqualTo("INDEX");
        assertSummaries(summary, null);
    }

    /**
     * A synthetic currency pair ZZFX (FOREX, tick 0.001, no volume, 300 weekday bars) and a monthly commodity ZZMON (40
     * months), rolled back.
     */
    private void syntheticInstruments() {
        jdbc.sql("""
                        INSERT INTO market_instrument (instrument_code, market, name, source, source_symbol, quote_currency,
                                                       unit, frequency, tick_size, history_from, sort_order)
                        VALUES ('ZZFX', 'FOREX', 'ZZZ/FX', 'yahoo', 'ZZFX=X', 'JPY', 'JPY per ZZZ', 'DAILY', 0.001,
                                DATE '2020-01-01', 99),
                               ('ZZMON', 'COMMODITY', 'Monthly Test', 'worldbank', 'Test, monthly', 'USD', 'USD per ton',
                                'MONTHLY', 0.01, DATE '2020-01-01', 99)""").update();
        LocalDate d = LocalDate.of(2026, 10, 14);
        for (int i = 0; i < 300; i++) {
            while (d.getDayOfWeek().getValue() > 5) {
                d = d.minusDays(1);
            }
            double close = 140 + (299 - i) * 0.0617 + (i % 7) * 0.2113;
            jdbc.sql("""
                            INSERT INTO instrument_daily (instrument_code, trading_date, open_price, high_price, low_price,
                                                          close_price, volume, source)
                            VALUES ('ZZFX', :d, :p - 0.05, :p + 0.311, :p - 0.311, :p, 0, 'test')""")
                    .param("d", d).param("p", BigDecimal.valueOf(close).setScale(3, java.math.RoundingMode.HALF_UP)).update();
            d = d.minusDays(1);
        }
        LocalDate month = LocalDate.of(2026, 9, 1);
        for (int i = 0; i < 40; i++) {
            jdbc.sql("""
                            INSERT INTO instrument_daily (instrument_code, trading_date, open_price, high_price, low_price,
                                                          close_price, volume, source)
                            VALUES ('ZZMON', :d, :p, :p, :p, :p, 0, 'test')""")
                    .param("d", month).param("p", BigDecimal.valueOf(100 + i * 1.5)).update();
            month = month.minusMonths(1);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void aCurrencyPairIsAListingInItsUnit() throws Exception {
        syntheticInstruments();
        mvc.perform(get("/api/v1/technical/markets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("IDX"))
                .andExpect(jsonPath("$[1].code").value("FOREX"))
                .andExpect(jsonPath("$[1].itemLabel").value("Currency"))
                .andExpect(jsonPath("$[2].code").value("COMMODITY"));
        mvc.perform(get("/api/v1/technical/tickers?exchange=forex&query=zz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ticker").value("ZZFX"))
                .andExpect(jsonPath("$[0].type").value("FOREX"))
                .andExpect(jsonPath("$[0].tradingDays").value(300))
                .andExpect(jsonPath("$[0].unit").value("JPY per ZZZ"));

        Map<String, Object> bars = getJson("/api/v1/technical/FOREX/ZZFX/ohlcv?interval=D&range=1Y");
        assertThat(bars.get("type")).isEqualTo("FOREX");
        assertThat(bars.get("currency")).isEqualTo("JPY");
        assertThat(bars.get("unit")).isEqualTo("JPY per ZZZ");
        assertThat(bars.get("lotSize")).isNull();
        // its tick sets the decimals of the chart
        assertThat(((Number) bars.get("tickSize")).doubleValue()).isEqualTo(0.001);
        assertThat((List<?>) bars.get("autoRejection")).isEmpty();
        assertThat((String) bars.get("priceBasis")).contains("JPY per ZZZ");

        Map<String, Object> levels = getJson("/api/v1/technical/FOREX/ZZFX/levels");
        assertThat(((Number) levels.get("tickSize")).doubleValue()).isEqualTo(0.001);
        for (Map<String, Object> level : (List<Map<String, Object>>) levels.get("keyLevels")) {
            BigDecimal price = new BigDecimal(String.valueOf(level.get("price")));
            assertThat(price.stripTrailingZeros().scale()).as(level.toString()).isLessThanOrEqualTo(3);
        }
        // no volume: no volume node
        assertThat((List<?>) ((Map<String, Object>) levels.get("volumeProfile")).get("topNodes")).isEmpty();

        Map<String, Object> snapshot = getJson("/api/v1/technical/FOREX/ZZFX/snapshot");
        assertThat(snapshot.get("schemaVersion")).isEqualTo(3);
        assertThat(((Map<String, Object>) snapshot.get("relativeStrength")).get("ratio")).isNull();
        assertThat(((Map<String, Object>) snapshot.get("relativeStrength")).get("percentile6m")).isNull();
        assertThat(((Map<String, Object>) snapshot.get("risk")).get("araCount60")).isNull();
        assertThat(snapshot).doesNotContainKey("macro");
        assertThat((List<String>) snapshot.get("notes")).anyMatch(n -> n.contains("No traded volume"));
        Map<String, Object> events = getJson("/api/v1/technical/FOREX/ZZFX/events");
        assertThat((List<?>) events.get("events")).isEmpty();

        Map<String, Object> summary = getJson("/api/v1/technical/FOREX/ZZFX/summary");
        assertThat(summary.get("type")).isEqualTo("FOREX");
        assertThat(summary.get("priceDecimals")).isEqualTo(3);
        assertThat(summary.get("unit")).isEqualTo("JPY per ZZZ");
        assertSummaries(summary, new IdxTickSize(List.of(new TechnicalProperties.TickBand(null, new BigDecimal("0.001")))));

        // a code that is not in the catalog (EUR/USD is since 2026-10-23)
        mvc.perform(get("/api/v1/technical/FOREX/ZZNONE/ohlcv")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Instrument not found"));
    }

    @Test
    void aMonthlySeriesIsChartedMonthlyOnly() throws Exception {
        syntheticInstruments();
        Map<String, Object> bars = getJson("/api/v1/technical/COMMODITY/ZZMON/ohlcv?interval=D&range=MAX");
        assertThat(bars.get("interval")).isEqualTo("M");
        assertThat(bars.get("frequency")).isEqualTo("MONTHLY");
        assertThat((List<?>) bars.get("bars")).hasSize(40);
        for (String resource : List.of("levels", "snapshot", "summary")) {
            mvc.perform(get("/api/v1/technical/COMMODITY/ZZMON/" + resource))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.title").value("Monthly series"));
        }
        mvc.perform(get("/api/v1/technical/COMMODITY/ZZMON/indicators?interval=W&set=sma20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interval").value("M"));
    }

    @Test
    void presetSummariesOfAStock() throws Exception {
        Map<String, Object> summary = getJson("/api/v1/technical/IDX/HRTA/summary");
        assertThat(summary.get("type")).isEqualTo("STOCK");
        assertThat(summary.get("method")).isEqualTo("rules");
        assertThat(summary.get("companyName")).isNotNull();
        assertSummaries(summary, new IdxTickSize(properties.tickSizes()));
        mvc.perform(get("/api/v1/technical/IDX/ZZZZ/summary")).andExpect(status().isNotFound());
    }

    @SuppressWarnings("unchecked")
    private static void assertSummaries(Map<String, Object> out, IdxTickSize ticks) {
        List<Map<String, Object>> summaries = (List<Map<String, Object>>) out.get("summaries");
        assertThat(summaries).extracting(s -> s.get("preset")).containsExactly("murphy", "gill", "ptj", "druckenmiller");
        for (Map<String, Object> s : summaries) {
            assertThat(s.get("stance")).isIn("BUY", "ACCUMULATE", "WAIT", "AVOID");
            double buy = ((Number) ((Map<String, Object>) s.get("buy")).get("price")).doubleValue();
            double sell = ((Number) ((Map<String, Object>) s.get("sell")).get("price")).doubleValue();
            double stop = ((Number) ((Map<String, Object>) s.get("stopLoss")).get("price")).doubleValue();
            assertThat(stop).as(s.get("preset") + " stop < buy < sell").isLessThan(buy);
            assertThat(buy).as(s.get("preset") + " buy < sell").isLessThan(sell);
            if (ticks != null) {
                for (double p : new double[] {buy, sell, stop}) {
                    assertThat(ticks.isValid(BigDecimal.valueOf(p))).as(s.get("preset") + " " + p).isTrue();
                }
            }
            assertThat((String) s.get("thesis")).isNotBlank();
        }
    }

    @Test
    void chartSettingsRoundTrip() throws Exception {
        String body = "{\"ticker\":\"HRTA\",\"interval\":\"W\",\"indicators\":[\"sma50\",\"rsi14\"]}";
        mvc.perform(put("/api/v1/technical/settings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/technical/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interval").value("W"))
                .andExpect(jsonPath("$.indicators[1]").value("rsi14"));
        mvc.perform(put("/api/v1/technical/settings").contentType(MediaType.APPLICATION_JSON).content("[1,2]"))
                .andExpect(status().isBadRequest());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getJson(String url) throws Exception {
        String body = mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readValue(body, Map.class);
    }
}
