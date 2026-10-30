package com.neracalab.backend.devcontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.neracalab.backend.auth.AuthenticatedUser;
import com.neracalab.backend.rag.EmbeddingClient;
import com.neracalab.backend.web.ApiExceptionHandler;

import tools.jackson.databind.json.JsonMapper;

/**
 * The development context API against the Docker Postgres (dev_context, V1.0.40), with a scripted embedding: a text
 * about cash points one way, anything else another, so a search by meaning finds the cash entry without its words.
 * The controller runs in a standalone MockMvc over the shared application context's repository - no bean is
 * overridden, so no extra application context (and connection pool) is started: one more had the suite exceed
 * Postgres' connections ("too many clients already"). Every row it writes carries the ticker ZZTST and is deleted.
 */
@SpringBootTest
class DevContextControllerTest {

    private static final String TICKER = "ZZTST";

    @Autowired
    private DevContextRepository repository;

    @Autowired
    private JdbcClient jdbc;

    private final JsonMapper json = JsonMapper.builder().build();
    private EmbeddingClient embeddings;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        embeddings = mock(EmbeddingClient.class);
        when(embeddings.model()).thenReturn("test-embedding");
        when(embeddings.embed(anyString())).thenAnswer(inv -> vector(inv.getArgument(0)));
        mvc = MockMvcBuilders.standaloneSetup(new DevContextController(new DevContextService(repository, embeddings)))
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.getParameterType() == AuthenticatedUser.class;
                    }

                    @Override
                    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                                                  NativeWebRequest request, WebDataBinderFactory binders) {
                        return new AuthenticatedUser(1, "admin", java.util.Set.of());
                    }
                })
                .build();
    }

    @AfterEach
    void cleanup() {
        jdbc.sql("DELETE FROM dev_context WHERE ticker = :t").param("t", TICKER).update();
    }

    /** 1536 dimensions: texts about cash (or bank balances) along axis 0, the rest along axis 1. */
    private static float[] vector(String text) {
        float[] v = new float[1536];
        String t = text.toLowerCase();
        v[t.contains("cash") || t.contains("bank balance") ? 0 : 1] = 1f;
        return v;
    }

    private long save(String body) throws Exception {
        String response = mvc.perform(post("/api/v1/dev-context").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asLong();
    }

    @Test
    void savesSearchesUpdatesAndDeletesContext() throws Exception {
        long cash = save("""
                {"kind": "issue", "status": "open", "ticker": "zztst", "period": "2025 FY",
                 "title": "Balance-sheet cash is cash alone",
                 "body": "The cash flow counts current accounts with banks in cash; the balance sheet row does not.",
                 "tags": ["cash", "financing"], "refs": ["ingestion_error 1251"]}""");
        long gap = save("""
                {"kind": "DATA_GAP", "ticker": "ZZTST", "title": "No FY2025 workbook",
                 "body": "FinancialStatement-2025-Tahunan-ZZTST.xlsx is not in data/IDX_XBRL.", "tags": ["missing-file"]}""");

        mvc.perform(get("/api/v1/dev-context/" + cash))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("ISSUE"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.ticker").value(TICKER))
                .andExpect(jsonPath("$.tags[1]").value("financing"))
                .andExpect(jsonPath("$.refs[0]").value("ingestion_error 1251"))
                .andExpect(jsonPath("$.embedded").value(true))
                .andExpect(jsonPath("$.createdBy").value("admin"));
        mvc.perform(get("/api/v1/dev-context/" + gap)).andExpect(jsonPath("$.status").value("INFO"));

        // by meaning: none of the query's words is in the entry, its embedding is
        mvc.perform(get("/api/v1/dev-context/search").param("q", "bank balance treatment").param("ticker", TICKER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].entry.id").value(cash))
                .andExpect(jsonPath("$[0].similarity").value(1.0));
        // by keyword
        mvc.perform(get("/api/v1/dev-context/search").param("q", "workbook").param("ticker", TICKER))
                .andExpect(jsonPath("$[0].entry.id").value(gap))
                .andExpect(jsonPath("$[0].keywordRank").isNumber());
        // filters
        mvc.perform(get("/api/v1/dev-context").param("ticker", TICKER).param("kind", "DATA_GAP"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(gap));
        mvc.perform(get("/api/v1/dev-context").param("ticker", TICKER).param("tag", "cash"))
                .andExpect(jsonPath("$[0].id").value(cash));

        mvc.perform(put("/api/v1/dev-context/" + cash).contentType(MediaType.APPLICATION_JSON).content("""
                        {"kind": "FIX", "status": "RESOLVED", "ticker": "ZZTST", "period": "2025 FY",
                         "title": "Balance-sheet cash is cash alone",
                         "body": "FilingMapper.cashWithBankBalances stores the cash flow's cash.", "tags": ["cash"]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.refs.length()").value(0));
        mvc.perform(get("/api/v1/dev-context").param("ticker", TICKER).param("status", "OPEN"))
                .andExpect(jsonPath("$.length()").value(0));

        mvc.perform(delete("/api/v1/dev-context/" + gap)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/dev-context/" + gap)).andExpect(status().isNotFound());
    }

    @Test
    void anEntryWithoutEmbeddingIsKeptAndEmbeddedLater() throws Exception {
        when(embeddings.embed(anyString())).thenThrow(new EmbeddingClient.EmbeddingException("provider down"));
        long id = save("""
                {"kind": "NOTE", "ticker": "ZZTST", "title": "Saved while the provider is down", "body": "keyword findable"}""");
        mvc.perform(get("/api/v1/dev-context/" + id)).andExpect(jsonPath("$.embedded").value(false));
        mvc.perform(get("/api/v1/dev-context/search").param("q", "findable").param("ticker", TICKER))
                .andExpect(jsonPath("$[0].entry.id").value(id));

        doAnswer(inv -> vector(inv.getArgument(0))).when(embeddings).embed(anyString());
        mvc.perform(post("/api/v1/dev-context/reembed")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/dev-context/" + id)).andExpect(jsonPath("$.embedded").value(true));
        assertThat(repository.find(id)).isPresent();
    }

    @Test
    void anInvalidEntryIsRefused() throws Exception {
        mvc.perform(post("/api/v1/dev-context").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\": \"OTHER\", \"title\": \"x\", \"body\": \"y\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/dev-context").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\": \"NOTE\", \"title\": \" \", \"body\": \"y\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/dev-context/search").param("q", " ")).andExpect(status().isBadRequest());
    }
}
