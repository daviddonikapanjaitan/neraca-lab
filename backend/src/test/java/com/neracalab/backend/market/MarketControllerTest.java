package com.neracalab.backend.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import com.neracalab.backend.auth.TestLogins;
import com.neracalab.backend.price.InstrumentIngestion;
import com.neracalab.backend.price.InstrumentIngestion.InstrumentRef;
import com.neracalab.backend.price.PriceIngestionQueue;
import com.neracalab.backend.price.PriceIngestionService.Result;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Forex and Commodities on Ingestion › Price Ingestion against the Docker Postgres (the catalog of V1.0.26): the markets
 * with their instruments, one instrument queued, "Update all". The instrument ingestion is a mock: nothing is fetched and
 * no bar is written; the jobs the tests create are removed again.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext     // a context of its own (the mocked ingestion): closed afterwards, so its connections do not pile up
class MarketControllerTest {

    @MockitoBean
    private InstrumentIngestion ingestion;

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private JsonMapper json;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PriceIngestionQueue queue;

    private MockMvc mvc;
    private String token;
    private Instant started;

    @BeforeEach
    void setUp() {
        token = TestLogins.rootToken(context);
        mvc = TestLogins.mockMvc(context, token);
        started = Instant.now();
        when(ingestion.lastCompleted(any())).thenReturn(LocalDate.of(2026, 10, 15));
        when(ingestion.instrument(anyString(), anyString())).thenReturn(Optional.empty());
        when(ingestion.instrument("FOREX", "USDIDR")).thenReturn(Optional.of(
                new InstrumentRef("USDIDR", "FOREX", "USD/IDR", "IDR=X", "yahoo", "DAILY")));
        when(ingestion.ingest(any(), anyBoolean())).thenReturn(new Result("yahoo", 1, LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 16), false, false, 10, 1, 2, 0, 7, LocalDate.of(2026, 10, 15), null, null));
    }

    @AfterEach
    void cleanUp() throws InterruptedException {
        Instant until = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(until)
                && queue.jobs().stream().anyMatch(j -> j.view().status().name().matches("QUEUED|RUNNING|WAITING_RATE_LIMIT"))) {
            Thread.sleep(50);
        }
        jdbc.sql("""
                        DELETE FROM ingestion_job WHERE job_type = 'PRICE' AND exchange IN ('FOREX', 'COMMODITY')
                          AND requested_at >= :t AND created_by_username = 'admin'""")
                .param("t", java.sql.Timestamp.from(started)).update();
        TestLogins.logout(context, token);
    }

    @Test
    void theMarketsWithTheirInstruments() throws Exception {
        mvc.perform(get("/api/v1/markets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].market").value("FOREX"))
                .andExpect(jsonPath("$[0].itemLabel").value("Currency"))
                .andExpect(jsonPath("$[0].items[0].code").value("USDIDR"))
                .andExpect(jsonPath("$[0].items[0].unit").value("IDR per USD"))
                .andExpect(jsonPath("$[0].items[1].code").value("USDJPY"))
                .andExpect(jsonPath("$[1].market").value("COMMODITY"))
                .andExpect(jsonPath("$[1].itemLabel").value("Commodity"));
        String body = mvc.perform(get("/api/v1/markets/commodity")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode commodities = json.readTree(body);
        assertThat(commodities.get("items").size()).isEqualTo(26);
        assertThat(commodities.get("upToDate").asInt() + commodities.get("behind").asInt()
                + commodities.get("withoutPrices").asInt()).isEqualTo(26);
        // energy, metals, agriculture and materials (sort_order): Brent, WTI, natural gas, LNG, coal, gold, ...
        assertThat(commodities.get("items").get(4).get("code").asString()).isEqualTo("COAL");
        assertThat(commodities.get("items").get(4).get("frequency").asString()).isEqualTo("MONTHLY");
        assertThat(commodities.get("items").get(13).get("code").asString()).isEqualTo("COBALT");
        assertThat(commodities.get("items").get(13).get("source").asString()).isEqualTo("imf");
        assertThat(commodities.get("items").get(15).get("code").asString()).isEqualTo("PALM_OIL_MYR");
        assertThat(commodities.get("items").get(15).get("unit").asString()).isEqualTo("MYR per metric ton");
        mvc.perform(get("/api/v1/markets/NYSE")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Unsupported market"));
    }

    @Test
    void oneInstrumentIsQueuedInThePriceQueue() throws Exception {
        String body = mvc.perform(post("/api/v1/markets/FOREX/ingestions").param("code", "usdidr"))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.exchange").value("FOREX"))
                .andExpect(jsonPath("$.ticker").value("USDIDR"))
                .andExpect(jsonPath("$.instrument").value(true))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(body).get("id").asString());
        Instant until = Instant.now().plusSeconds(15);
        String stage = null;
        while (Instant.now().isBefore(until) && stage == null) {
            stage = jdbc.sql("SELECT stage FROM ingestion_job WHERE job_id = :id AND status = 'SUCCEEDED'").param("id", id)
                    .query(String.class).optional().orElse(null);
            Thread.sleep(50);
        }
        assertThat(stage).isEqualTo("Stored 2 new and 0 updated price days, latest 2026-10-15");
        mvc.perform(post("/api/v1/markets/FOREX/ingestions").param("code", "EURUSD"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Instrument not found"));
    }

    @Test
    void updateAllQueuesOnlyTheInstrumentsBehind() throws Exception {
        String body = mvc.perform(post("/api/v1/markets/COMMODITY/updates"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.market").value("COMMODITY"))
                .andReturn().getResponse().getContentAsString();
        JsonNode update = json.readTree(body);
        assertThat(update.get("queued").asInt() + update.get("alreadyActive").asInt() + update.get("upToDate").asInt())
                .isEqualTo(update.get("instruments").asInt());
    }
}
