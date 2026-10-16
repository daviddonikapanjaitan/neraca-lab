package com.neracalab.backend.macro.outlook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.neracalab.backend.auth.TestLogins;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Macro page's API against the Docker Postgres (its stored index and macro data): the countries and their indexes,
 * the outlook's sections, the refusals (a country not available yet, an unknown index or range), a login required.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MacroOutlookControllerTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private JsonMapper json;
    @Autowired
    private org.springframework.jdbc.core.simple.JdbcClient jdbc;

    private MockMvc mvc;
    private String token;

    @BeforeEach
    void setUp() {
        token = TestLogins.rootToken(context);
        mvc = TestLogins.mockMvc(context, token);
    }

    @AfterEach
    void cleanUp() {
        TestLogins.logout(context, token);
    }

    @Test
    void indonesiaWithItsIndexesTheOthersLater() throws Exception {
        JsonNode countries = json.readTree(mvc.perform(get("/api/v1/macro/outlook/countries")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(countries.size()).isEqualTo(3);
        assertThat(countries.get(0).get("code").asString()).isEqualTo("ID");
        assertThat(countries.get(0).get("available").asBoolean()).isTrue();
        assertThat(countries.get(0).get("indexes").get(0).get("code").asString()).isEqualTo("IHSG");
        assertThat(countries.get(0).get("indexes").get(1).get("code").asString()).isEqualTo("LQ45");
        assertThat(countries.get(1).get("code").asString()).isEqualTo("US");
        assertThat(countries.get(1).get("available").asBoolean()).isFalse();
        assertThat(countries.get(2).get("code").asString()).isEqualTo("CN");
    }

    @Test
    void theOutlookHasEverySection() throws Exception {
        JsonNode out = json.readTree(mvc.perform(get("/api/v1/macro/outlook?country=id&index=ihsg&range=1y"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(out.get("country").get("code").asString()).isEqualTo("ID");
        assertThat(out.get("range").asString()).isEqualTo("1Y");
        assertThat(out.get("index").get("code").asString()).isEqualTo("IHSG");
        assertThat(out.get("panels").isArray()).isTrue();
        assertThat(out.get("presets").size()).isEqualTo(2);
        assertThat(out.get("presets").get(0).get("preset").asString()).isEqualTo("ptj");
        assertThat(out.get("presets").get(1).get("preset").asString()).isEqualTo("druckenmiller");
        assertThat(out.get("regimes").has("liquidity")).isTrue();
        JsonNode conclusion = out.get("conclusion");
        assertThat(conclusion.get("action").asString()).isIn("BUY", "ACCUMULATE", "WAIT", "SELL");
        assertThat(conclusion.get("components").size()).isEqualTo(6);
        assertThat(out.get("rulesVersion").asString()).isEqualTo(MacroOutlook.RULES_VERSION);
        // the default index is the benchmark
        mvc.perform(get("/api/v1/macro/outlook")).andExpect(status().isOk()).andExpect(jsonPath("$.index.code").value("IHSG"));
    }

    /**
     * USD/IDR is the Forex price ingestion's (Yahoo Finance IDR=X): the panel shows its latest stored close the day it is
     * stored, not a day later and not the ECB rate.
     */
    @Test
    void usdIdrIsTheLatestForexClose() throws Exception {
        var latest = jdbc.sql("""
                        SELECT trading_date, close_price FROM instrument_daily WHERE instrument_code = 'USDIDR'
                        ORDER BY trading_date DESC LIMIT 1""")
                .query((rs, i) -> java.util.Map.entry(rs.getObject(1, java.time.LocalDate.class), rs.getBigDecimal(2)))
                .optional();
        org.junit.jupiter.api.Assumptions.assumeTrue(latest.isPresent(), "no USD/IDR from the Forex price ingestion stored");
        JsonNode out = json.readTree(mvc.perform(get("/api/v1/macro/outlook?country=ID&index=IHSG&range=6M"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode usdIdr = null;
        for (JsonNode p : out.get("panels")) {
            if (p.get("code").asString().equals("USD_IDR")) {
                usdIdr = p;
            }
        }
        assertThat(usdIdr).isNotNull();
        assertThat(usdIdr.get("lastDate").asString()).isEqualTo(latest.get().getKey().toString());
        assertThat(usdIdr.get("lastValue").decimalValue()).isEqualByComparingTo(latest.get().getValue());
        assertThat(usdIdr.get("source").asString()).isEqualTo("Forex price ingestion (Yahoo Finance IDR=X)");
        assertThat(out.get("asOfDate").asString()).isGreaterThanOrEqualTo(latest.get().getKey().toString());
    }

    @Test
    void refusals() throws Exception {
        mvc.perform(get("/api/v1/macro/outlook?country=US")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("United States is not available yet: only Indonesia (ID) for now"));
        mvc.perform(get("/api/v1/macro/outlook?country=XX")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/macro/outlook?index=KOMPAS100")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Unknown index 'KOMPAS100' for Indonesia: use IHSG or LQ45"));
        mvc.perform(get("/api/v1/macro/outlook?range=2W")).andExpect(status().isBadRequest());
    }

    @Test
    void aLoginIsRequired() throws Exception {
        MockMvc anonymous = MockMvcBuilders.webAppContextSetup(context).build();
        anonymous.perform(get("/api/v1/macro/outlook")).andExpect(status().isUnauthorized());
    }
}
