package com.neracalab.backend.macro.outlook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import com.neracalab.backend.auth.TestLogins;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Macro › Data against the Docker Postgres's stored macro series (read only). */
@SpringBootTest
@AutoConfigureMockMvc
class MacroDataControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JsonMapper json;

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

    private JsonNode getJson(String url) throws Exception {
        String body = mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    @Test
    void everyActiveSeriesIsListedInCatalogOrderWithItsKind() throws Exception {
        JsonNode list = getJson("/api/v1/macro/data/series");
        int active = jdbc.sql("SELECT count(*) FROM macro_series_def WHERE active").query(Integer.class).single();
        assertThat(list.get("series")).hasSize(active);
        assertThat(list.get("series").get(0).get("code").asString()).isEqualTo(jdbc.sql(
                "SELECT series_code FROM macro_series_def WHERE active ORDER BY sort_order, series_code LIMIT 1")
                .query(String.class).single());
        for (JsonNode s : list.get("series")) {
            String code = s.get("code").asString();
            String expected = code.equals("ID_FOREIGN_NET_BUY") ? "FLOW"
                    : java.util.Set.of("%", "bps", "points").contains(s.get("unit").asString()) ? "RATE" : "LEVEL";
            assertThat(s.get("kind").asString()).as(code).isEqualTo(expected);
            // nothing of the ingestion jobs is shown here
            assertThat(s.has("lastJobMessage")).isFalse();
        }
    }

    @Test
    void aRateSeriesOverOneYear() throws Exception {
        LocalDate last = jdbc.sql("SELECT max(obs_date) FROM macro_series WHERE series_code = 'US_10Y'")
                .query(LocalDate.class).single();
        int inWindow = jdbc.sql("""
                        SELECT count(*) FROM macro_series WHERE series_code = 'US_10Y' AND value IS NOT NULL
                          AND obs_date >= :from""").param("from", last.minusYears(1)).query(Integer.class).single();
        JsonNode body = getJson("/api/v1/macro/data/series/us_10y?range=1y");
        JsonNode chart = body.get("chart");
        assertThat(body.get("series").get("code").asString()).isEqualTo("US_10Y");
        assertThat(chart.get("kind").asString()).isEqualTo("RATE");
        assertThat(chart.get("range").asString()).isEqualTo("1Y");
        assertThat(chart.get("to").asString()).isEqualTo(last.toString());
        assertThat(chart.get("from").asString()).isEqualTo(last.minusYears(1).toString());
        assertThat(chart.get("points")).hasSize(inWindow);
        assertThat(chart.get("sampled").isNull()).isTrue();
        assertThat(chart.get("stats").get("observations").asInt()).isEqualTo(inWindow);
        // a rate has no percent changes
        assertThat(chart.get("stats").get("changePct").isNull()).isTrue();
        assertThat(chart.get("recent")).hasSize(12);
        assertThat(chart.get("recent").get(0).get("date").asString()).isEqualTo(last.toString());
    }

    @Test
    void theFlowsWholeHistoryIsDrawnWeekly() throws Exception {
        int rows = jdbc.sql("SELECT count(*) FROM macro_series WHERE series_code = 'ID_FOREIGN_NET_BUY' AND value IS NOT NULL")
                .query(Integer.class).single();
        JsonNode chart = getJson("/api/v1/macro/data/series/ID_FOREIGN_NET_BUY?range=MAX").get("chart");
        assertThat(chart.get("kind").asString()).isEqualTo("FLOW");
        assertThat(chart.get("from").isNull()).isTrue();
        assertThat(chart.get("stats").get("observations").asInt()).isEqualTo(rows);
        if (rows > MacroSeriesChart.MAX_POINTS) {
            assertThat(chart.get("sampled").asString()).isEqualTo("WEEKLY");
            assertThat(chart.get("points").size()).isLessThan(rows);
        }
    }

    @Test
    void anUnknownSeriesOrRange() throws Exception {
        mvc.perform(get("/api/v1/macro/data/series/NOPE")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/macro/data/series/US_10Y?range=2W")).andExpect(status().isBadRequest());
    }
}
