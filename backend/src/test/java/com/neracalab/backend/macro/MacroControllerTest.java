package com.neracalab.backend.macro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import com.neracalab.backend.auth.TestLogins;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ingestion › Macro against the Docker Postgres (the catalog of V1.0.26): the series with their state, the history of
 * one, the refusals (a manual series has no source, an unknown series), the CSV upload with its checks. No source is
 * called; the observations and jobs the tests write are removed again.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MacroControllerTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private JsonMapper json;
    @Autowired
    private JdbcClient jdbc;

    private MockMvc mvc;
    private String token;
    private Instant started;

    @BeforeEach
    void setUp() {
        token = TestLogins.rootToken(context);
        mvc = TestLogins.mockMvc(context, token);
        started = Instant.now();
    }

    @AfterEach
    void cleanUp() {
        // the test uploads months of 1999, which no real series of the PMI holds (it starts in 2010)
        jdbc.sql("DELETE FROM macro_series WHERE series_code = 'CN_PMI_MFG' AND obs_date < DATE '2000-01-01'").update();
        jdbc.sql("DELETE FROM ingestion_job WHERE job_type = 'MACRO' AND requested_at >= :t AND created_by_username = 'admin'")
                .param("t", java.sql.Timestamp.from(started)).update();
        TestLogins.logout(context, token);
    }

    @Test
    void everySeriesWithItsState() throws Exception {
        String body = mvc.perform(get("/api/v1/macro/series")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode status = json.readTree(body);
        assertThat(status.get("series").asInt()).isEqualTo(status.get("items").size()).isGreaterThanOrEqualTo(25);
        assertThat(status.get("upToDate").asInt() + status.get("behind").asInt() + status.get("neverFetched").asInt()
                + status.get("failing").asInt() + status.get("manual").asInt()).isEqualTo(status.get("series").asInt());
        JsonNode first = status.get("items").get(0);
        assertThat(first.get("code").asString()).isEqualTo("BI_RATE");
        assertThat(first.get("sourceLabel").asString()).isEqualTo("Bank Indonesia");
        assertThat(first.get("usedBy").size()).isEqualTo(2);
        JsonNode pmi = null;
        for (JsonNode item : status.get("items")) {
            if (item.get("code").asString().equals("CN_PMI_MFG")) {
                pmi = item;
            }
        }
        assertThat(pmi).isNotNull();
        assertThat(pmi.get("state").asString()).isEqualTo("MANUAL");

        mvc.perform(get("/api/v1/macro/series/bi_rate")).andExpect(status().isOk())
                .andExpect(jsonPath("$.series.code").value("BI_RATE"));
        mvc.perform(get("/api/v1/macro/series/NOPE")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Macro series not found"));
        mvc.perform(get("/api/v1/macro/series/BI_RATE").param("from", "yesterday")).andExpect(status().isBadRequest());
    }

    @Test
    void aManualSeriesIsNotFetched() throws Exception {
        mvc.perform(post("/api/v1/macro/ingestions").param("code", "CN_PMI_MFG"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("upload a CSV")));
        mvc.perform(post("/api/v1/macro/ingestions").param("code", "NOPE")).andExpect(status().isNotFound());
    }

    @Test
    void aCsvIsCheckedThenStored() throws Exception {
        // out of the PMI range 20 .. 80: rejected, nothing stored
        mvc.perform(multipart("/api/v1/macro/series/CN_PMI_MFG/upload").file(csv("obs_date,value\n1999-01-01,49.5\n1999-02-01,495\n")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("outside the range")));
        assertThat(stored()).isZero();
        // a monthly series is dated on the 1st
        mvc.perform(multipart("/api/v1/macro/series/CN_PMI_MFG/upload").file(csv("1999-01-15,49.5\n")))
                .andExpect(status().isUnprocessableContent());

        mvc.perform(multipart("/api/v1/macro/series/CN_PMI_MFG/upload").file(csv("obs_date,value\r\n1999-01-01,49.5\r\n1999-02-01,50.1\r\n")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inserted").value(2))
                .andExpect(jsonPath("$.source").value("manual"))
                .andExpect(jsonPath("$.requests").value(0));
        assertThat(stored()).isEqualTo(2);
        // the release date follows the series' lag: January's PMI is public on 1 February
        assertThat(jdbc.sql("SELECT release_date FROM macro_series WHERE series_code = 'CN_PMI_MFG' AND obs_date = DATE '1999-01-01'")
                .query(java.time.LocalDate.class).single()).isEqualTo(java.time.LocalDate.of(1999, 2, 1));
        // the upload is a MACRO job
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM ingestion_job WHERE job_type = 'MACRO' AND ticker = 'CN_PMI_MFG'
                          AND exchange = 'MACRO' AND status = 'SUCCEEDED' AND requested_at >= :t""")
                .param("t", java.sql.Timestamp.from(started)).query(Long.class).single()).isEqualTo(1);
    }

    private long stored() {
        return jdbc.sql("SELECT count(*) FROM macro_series WHERE series_code = 'CN_PMI_MFG' AND obs_date < DATE '2000-01-01'")
                .query(Long.class).single();
    }

    private static MockMultipartFile csv(String text) {
        return new MockMultipartFile("file", "pmi.csv", "text/csv", text.getBytes(StandardCharsets.UTF_8));
    }
}
