package com.neracalab.backend.price;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import com.neracalab.backend.auth.TestLogins;
import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.price.IndexIngestion.IndexRef;
import com.neracalab.backend.price.PriceIngestionService.Result;
import com.neracalab.backend.price.provider.PriceHistory;
import com.neracalab.backend.price.provider.PriceProvider;
import com.neracalab.backend.price.provider.SymbolNotFoundException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The prices of a whole exchange and its index (Ingestion › Price Ingestion): status, "Update all companies" and the
 * IHSG ingestion, against the Docker Postgres. The price provider is a stub without data and the index ingestion a
 * mock: nothing is fetched and no price is written; the jobs the tests create are removed again.
 */
@SpringBootTest(properties = "neracalab.prices.provider=stub")
@AutoConfigureMockMvc
@DirtiesContext     // a context of its own (the mocked index): closed afterwards, so its connections do not pile up
class PriceUpdateControllerTest {

    private static final IndexRef IHSG = new IndexRef("IHSG", "^JKSE", "IDX Composite Index (IHSG)", Exchange.IDX);

    @TestConfiguration
    static class NoDataProviderConfig {

        @Bean
        PriceProvider noDataPriceProvider() {
            return new PriceProvider() {
                @Override
                public String name() {
                    return "stub";
                }

                @Override
                public PriceHistory fetch(Exchange exchange, String ticker, LocalDate from, LocalDate to) {
                    throw new SymbolNotFoundException("stub has no data for " + ticker);
                }
            };
        }
    }

    @MockitoBean
    private IndexIngestion index;

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
        when(index.index(Exchange.IDX)).thenReturn(Optional.of(IHSG));
        when(index.sourceName()).thenReturn("yahoo");
        when(index.stored(IHSG)).thenReturn(new IndexIngestion.Stored(8891, LocalDate.of(1990, 4, 6),
                LocalDate.of(2026, 10, 15)));
        when(index.ingest(any(), anyBoolean())).thenReturn(new Result("yahoo", 1, LocalDate.of(2026, 10, 14),
                LocalDate.of(2026, 10, 16), false, false, 3, 0, 2, 0, 1, LocalDate.of(2026, 10, 16), null, null));
    }

    @AfterEach
    void cleanUp() throws InterruptedException {
        awaitIdle();
        // keep only real jobs in the jobs table
        jdbc.sql("DELETE FROM ingestion_job WHERE job_type = 'PRICE' AND requested_at >= :t AND created_by_username = 'admin'")
                .param("t", java.sql.Timestamp.from(started)).update();
        TestLogins.logout(context, token);
    }

    private void awaitIdle() throws InterruptedException {
        Instant until = Instant.now().plusSeconds(60);
        while (Instant.now().isBefore(until)) {
            if (queue.jobs().stream().noneMatch(j -> j.view().status().name().matches("QUEUED|RUNNING|WAITING_RATE_LIMIT"))) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("price queue did not finish");
    }

    @Test
    void statusOfTheExchangeAndItsIndex() throws Exception {
        String body = mvc.perform(get("/api/v1/prices/status").param("exchange", "idx"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exchange").value("IDX"))
                .andExpect(jsonPath("$.provider").value("stub"))
                .andExpect(jsonPath("$.index.code").value("IHSG"))
                .andExpect(jsonPath("$.index.symbol").value("^JKSE"))
                .andExpect(jsonPath("$.index.indexes[0].code").value("IHSG"))
                .andExpect(jsonPath("$.index.indexes[0].rows").value(8891))
                .andExpect(jsonPath("$.index.rows").value(8891))
                .andReturn().getResponse().getContentAsString();
        JsonNode status = json.readTree(body);
        long active = jdbc.sql("SELECT count(*) FROM company WHERE exchange = 'IDX' AND active").query(Long.class).single();
        assertThat(status.get("companies").asLong()).isEqualTo(active);
        assertThat(status.get("upToDate").asInt() + status.get("behind").asInt() + status.get("withoutPrices").asInt())
                .isEqualTo(status.get("companies").asInt());
        assertThat(status.get("lastCompletedTradingDay").asString()).isNotBlank();
        mvc.perform(get("/api/v1/prices/status").param("exchange", "NYSE")).andExpect(status().isBadRequest());
    }

    @Test
    void theIndexIsIngestedInThePriceQueue() throws Exception {
        String body = mvc.perform(post("/api/v1/prices/index-ingestions").param("exchange", "IDX"))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.ticker").value("IHSG"))
                .andExpect(jsonPath("$.index").value(true))
                .andExpect(jsonPath("$.exchange").value("IDX"))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(body).get("id").asString());
        awaitIdle();

        assertThat(queue.job(id).orElseThrow().view().status()).isEqualTo(PriceIngestionJob.Status.SUCCEEDED);
        Instant until = Instant.now().plusSeconds(10);
        String stage = null;
        while (Instant.now().isBefore(until)) {
            stage = jdbc.sql("SELECT stage FROM ingestion_job WHERE job_id = :id AND status = 'SUCCEEDED'").param("id", id)
                    .query(String.class).optional().orElse(null);
            if (stage != null) {
                break;
            }
            Thread.sleep(50);
        }
        assertThat(stage).isEqualTo("Stored 2 new and 0 updated index days, latest 2026-10-16");
        assertThat(jdbc.sql("SELECT ticker FROM ingestion_job WHERE job_id = :id").param("id", id).query(String.class).single())
                .isEqualTo("IHSG");
    }

    @Test
    void updateAllQueuesOnlyTheCompaniesBehind() throws Exception {
        String body = mvc.perform(post("/api/v1/prices/updates").param("exchange", "IDX").param("index", "true"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.exchange").value("IDX"))
                .andReturn().getResponse().getContentAsString();
        JsonNode update = json.readTree(body);
        assertThat(update.get("queued").asInt() + update.get("alreadyActive").asInt() + update.get("upToDate").asInt())
                .isEqualTo(update.get("companies").asInt());
        // the mocked index is stored up to 2026-10-15: queued only when that is before the last completed day
        assertThat(update.get("index").get("outcome").asString()).isIn("QUEUED", "UP_TO_DATE", "ALREADY_ACTIVE");
        awaitIdle();
    }
}
