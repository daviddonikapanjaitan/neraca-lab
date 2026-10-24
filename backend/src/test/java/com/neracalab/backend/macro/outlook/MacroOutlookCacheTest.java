package com.neracalab.backend.macro.outlook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import com.neracalab.backend.auth.TestLogins;
import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.market.MarketDataChanged;

import tools.jackson.databind.json.JsonMapper;

/**
 * The Macro page's outlook in Redis (the Docker Redis and Postgres): a second request is read from Redis and equal to
 * the computed outlook, a write of the data read clears it only after its commit, refused requests are not cached.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MacroOutlookCacheTest {

    private static final String URL = "/api/v1/macro/outlook?country=ID&index=IHSG&range=1Y";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private MacroOutlookService service;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private ApplicationEventPublisher events;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private JsonMapper json;

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
    void theSecondRequestIsReadFromRedisAndEqualsTheComputedOutlook() throws Exception {
        events.publishEvent(new MarketDataChanged("index_daily"));
        String first = mvc.perform(get(URL)).andExpect(status().isOk()).andExpect(header().string("X-Cache", "MISS"))
                .andReturn().getResponse().getContentAsString();
        String second = mvc.perform(get(URL)).andExpect(status().isOk()).andExpect(header().string("X-Cache", "HIT"))
                .andReturn().getResponse().getContentAsString();
        assertThat(second).isEqualTo(first);
        // lower case and the default index name the same outlook
        mvc.perform(get("/api/v1/macro/outlook?country=id&range=1y")).andExpect(header().string("X-Cache", "HIT"));
        String computed = json.writeValueAsString(service.outlook("ID", "IHSG", "1Y",
                LocalDate.now(Exchange.of("IDX").zone())));
        assertThat(json.readTree(second)).isEqualTo(json.readTree(computed));
    }

    @Test
    void aWriteOfTheDataReadClearsTheOutlookAfterItsCommit() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isOk());
        mvc.perform(get(URL)).andExpect(header().string("X-Cache", "HIT"));
        String before = redis.opsForValue().get(MacroOutlookCache.GENERATION);
        transactions.executeWithoutResult(tx -> {
            events.publishEvent(new MarketDataChanged("macro_series"));
            // not cleared before the commit: a request now would still read the committed data
            assertThat(redis.opsForValue().get(MacroOutlookCache.GENERATION)).isEqualTo(before);
        });
        assertThat(Long.parseLong(redis.opsForValue().get(MacroOutlookCache.GENERATION)))
                .isGreaterThan(Long.parseLong(before));
        mvc.perform(get(URL)).andExpect(header().string("X-Cache", "MISS"));
        mvc.perform(get(URL)).andExpect(header().string("X-Cache", "HIT"));
    }

    @Test
    void aRolledBackWriteDoesNotClear() throws Exception {
        String before = redis.opsForValue().get(MacroOutlookCache.GENERATION);
        transactions.executeWithoutResult(tx -> {
            events.publishEvent(new MarketDataChanged("instrument_daily"));
            tx.setRollbackOnly();
        });
        assertThat(redis.opsForValue().get(MacroOutlookCache.GENERATION)).isEqualTo(before);
    }

    @Test
    void refusedRequestsAreNotCached() throws Exception {
        mvc.perform(get("/api/v1/macro/outlook?country=ID&range=2Y")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/macro/outlook?country=ID&index=XYZ")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/macro/outlook?country=US")).andExpect(status().isBadRequest());
    }

    @Test
    void aBatchWithAnyCountButZeroIsAWrite() {
        assertThat(MarketDataChanged.written(new int[] {0, 0}, new int[] {0})).isFalse();
        assertThat(MarketDataChanged.written(new int[] {0, 1})).isTrue();
        assertThat(MarketDataChanged.written(new int[] {-2})).isTrue();   // Statement.SUCCESS_NO_INFO
        assertThat(MarketDataChanged.written()).isFalse();
    }
}
