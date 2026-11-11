package com.neracalab.backend.company.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

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
import com.neracalab.backend.company.score.TechnicalScoreBoard.Board;
import com.neracalab.backend.company.score.TechnicalScoreBoard.Score;
import com.neracalab.backend.technical.TechnicalService;

/**
 * The overall Technical Score of every IDX company (the company list's column) against the Docker Postgres: the same
 * score as each company's Technical (Score) tab, kept in memory. Reads only (besides the indicator snapshots the
 * summaries keep, as Companies &gt; Technicals does).
 */
@SpringBootTest
@AutoConfigureMockMvc
class TechnicalScoreBoardTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private TechnicalScoreBoard board;

    @Autowired
    private TechnicalService technical;

    @Autowired
    private JdbcClient jdbc;

    private MockMvc mvc;
    private String token;

    @BeforeEach
    void login() {
        token = TestLogins.rootToken(context);
        mvc = TestLogins.mockMvc(context, token);
        board.clear();
    }

    @AfterEach
    void logout() {
        TestLogins.logout(context, token);
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyCompanyHasTheScoreOfItsTechnicalScoreTab() {
        Board b = board.board("idx");
        int companies = jdbc.sql("SELECT count(*) FROM company WHERE exchange = 'IDX'").query(Integer.class).single();
        assertThat(b.exchange()).isEqualTo("IDX");
        assertThat(b.cached()).isFalse();
        assertThat(b.companies()).isEqualTo(companies).isEqualTo(b.scores().size());
        assertThat(b.scored()).isEqualTo((int) b.scores().stream().filter(s -> s.overall() != null).count());
        assertThat(b.scored()).isGreaterThan(companies / 2);
        assertThat(b.scores()).filteredOn(s -> s.overall() == null).allSatisfy(s -> assertThat(s.error()).isNotBlank());

        Map<String, Score> byTicker = b.scores().stream().collect(Collectors.toMap(Score::ticker, Function.identity()));
        for (String ticker : List.of("HRTA", "BBCA", "GOTO", "AADI", "TLKM")) {
            if (!byTicker.containsKey(ticker) || byTicker.get(ticker).overall() == null) {
                continue;
            }
            Map<String, Object> tab = (Map<String, Object>) technical.technicalScore("IDX", ticker).get("technicalScore");
            Score s = byTicker.get(ticker);
            assertThat(s.overall()).as(ticker).isEqualTo(tab.get("overall"));
            assertThat(s.stance()).as(ticker).isEqualTo(tab.get("stance"));
            assertThat(s.agents()).as(ticker).isEqualTo(4);
        }
    }

    @Test
    void theSecondRequestIsServedFromMemory() {
        Board first = board.board("IDX");
        Board second = board.board("IDX");
        assertThat(second.cached()).isTrue();
        assertThat(second.computedAt()).isEqualTo(first.computedAt());
        assertThat(second.scores()).isEqualTo(first.scores());
    }

    @Test
    void theApi() throws Exception {
        mvc.perform(get("/api/v1/companies/technical-scores?exchange=IDX"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exchange").value("IDX"))
                .andExpect(jsonPath("$.scores[0].ticker").exists())
                .andExpect(jsonPath("$.scored").isNumber());
        mvc.perform(get("/api/v1/companies/technical-scores?exchange=XYZ")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/technical/IDX/HRTA/score"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summaries.length()").value(4))
                .andExpect(jsonPath("$.technicalScore.overall").isNumber())
                .andExpect(jsonPath("$.technicalScore.agents.length()").value(4))
                .andExpect(jsonPath("$.technicalScore.summary").isString());
        mvc.perform(get("/api/v1/technical/IDX/ZZZZ/score")).andExpect(status().isNotFound());
    }
}
