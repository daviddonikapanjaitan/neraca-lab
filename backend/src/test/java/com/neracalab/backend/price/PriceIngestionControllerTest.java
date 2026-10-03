package com.neracalab.backend.price;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import com.neracalab.backend.auth.TestLogins;
import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.job.IngestionJob;
import com.neracalab.backend.job.IngestionJobType;
import com.neracalab.backend.price.PriceIngestionJob.Status;
import com.neracalab.backend.price.provider.PriceHistory;
import com.neracalab.backend.price.provider.PriceProvider;
import com.neracalab.backend.price.provider.SymbolNotFoundException;

import tools.jackson.databind.json.JsonMapper;

/**
 * Ingestion API. The provider is a stub that has no data, so a queued job never writes prices
 * (it fails, or succeeds without a request when the stored prices are already up to date).
 */
@SpringBootTest(properties = "neracalab.prices.provider=stub")
@AutoConfigureMockMvc
class PriceIngestionControllerTest {

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

    @Autowired
    private WebApplicationContext context;

    /** Sends the bearer token of a root session with every request. */
    private MockMvc mvc;
    private String token;

    @BeforeEach
    void loginAsRoot() {
        token = TestLogins.rootToken(context);
        mvc = TestLogins.mockMvc(context, token);
    }

    @AfterEach
    void logout() {
        TestLogins.logout(context, token);
    }

    @Autowired
    private JsonMapper json;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void queuesAnIngestionAndReportsTheJob() throws Exception {
        String body = mvc.perform(post("/api/v1/prices/ingestions").param("exchange", "idx").param("ticker", " hrta"))
                .andExpect(status().is2xxSuccessful())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/prices/ingestions/")))
                .andExpect(jsonPath("$.exchange").value("IDX"))
                .andExpect(jsonPath("$.ticker").value("HRTA"))
                .andExpect(jsonPath("$.full").value(false))
                .andReturn().getResponse().getContentAsString();
        UUID id = json.readValue(body, PriceIngestionJob.View.class).id();

        PriceIngestionJob.View finished = awaitFinished(id);
        if (finished.status() == Status.FAILED) {
            assertThat(finished.message()).isEqualTo("stub has no data for HRTA");
        } else {
            assertThat(finished.result().requests()).isZero();
        }

        mvc.perform(get("/api/v1/prices/ingestions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("stub"))
                .andExpect(jsonPath("$.pending").isNumber())
                .andExpect(jsonPath("$.jobs[?(@.id == '" + id + "')]").exists());

        // every state change is also recorded in ingestion_job (GET /api/v1/ingestions)
        IngestionJob recorded = awaitRecorded(id);
        assertThat(recorded.type()).isEqualTo(IngestionJobType.PRICE);
        assertThat(recorded.status().name()).isEqualTo(finished.status().name());
        assertThat(recorded.exchange()).isEqualTo("IDX");
        assertThat(recorded.ticker()).isEqualTo("HRTA");
        assertThat(recorded.fullHistory()).isFalse();
        assertThat(recorded.file()).isNull();
        assertThat(recorded.createdBy().username()).isEqualTo("admin");
        assertThat(finished.requestedBy().username()).isEqualTo("admin");
        assertThat(recorded.attempts()).isEqualTo(finished.attempts());
        assertThat(recorded.message()).isEqualTo(finished.message());
        assertThat(recorded.finishedAt()).isNotNull();
        if (finished.status() == Status.SUCCEEDED) {
            assertThat(recorded.result().get("requests").asInt()).isZero();
        }
        mvc.perform(get("/api/v1/ingestions").param("type", "PRICE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobs[?(@.id == '" + id + "')]").exists());

        jdbc.sql("DELETE FROM ingestion_job WHERE job_id = :id").param("id", id).update();   // keep only real jobs
    }

    @Test
    void requiresExchangeAndTicker() throws Exception {
        mvc.perform(post("/api/v1/prices/ingestions").param("exchange", "IDX"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/prices/ingestions").param("ticker", "HRTA"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsUnknownExchangeInvalidTickerAndUnknownCompany() throws Exception {
        mvc.perform(post("/api/v1/prices/ingestions").param("exchange", "NYSE").param("ticker", "HRTA"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Unsupported exchange"))
                .andExpect(jsonPath("$.supportedExchanges[0]").value("IDX"));
        mvc.perform(post("/api/v1/prices/ingestions").param("exchange", "IDX").param("ticker", "HR TA"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid ticker"));
        mvc.perform(post("/api/v1/prices/ingestions").param("exchange", "IDX").param("ticker", "ZZZZ"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Company not found"));
    }

    @Test
    void unknownJobIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/prices/ingestions/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Ingestion job not found"));
    }

    private IngestionJob awaitRecorded(UUID id) throws Exception {
        Instant deadline = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(deadline)) {
            String body = mvc.perform(get("/api/v1/ingestions/" + id))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            IngestionJob job = json.readValue(body, IngestionJob.class);
            if (!job.status().active()) {
                return job;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("job " + id + " was not recorded as finished");
    }

    private PriceIngestionJob.View awaitFinished(UUID id) throws Exception {
        Instant deadline = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(deadline)) {
            String body = mvc.perform(get("/api/v1/prices/ingestions/" + id))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            PriceIngestionJob.View view = json.readValue(body, PriceIngestionJob.View.class);
            if (view.status() == Status.SUCCEEDED || view.status() == Status.FAILED) {
                return view;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("job " + id + " did not finish");
    }
}
