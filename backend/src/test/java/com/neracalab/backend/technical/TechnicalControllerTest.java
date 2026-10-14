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
                .andExpect(jsonPath("$.schemaVersion").value(1))
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
                .andExpect(jsonPath("$.macro.USD_IDR.points").isArray());
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
