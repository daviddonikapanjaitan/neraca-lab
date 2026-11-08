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
import com.neracalab.backend.company.score.QuantScoreBoard.Board;
import com.neracalab.backend.company.score.QuantScoreBoard.Score;
import com.neracalab.backend.company.score.ScorecardService.AgentCard;
import com.neracalab.backend.company.score.ScorecardService.Scorecards;

/**
 * The overall Quant Score of every IDX company (the company list's column) against the Docker Postgres: the same score as
 * each company's Quant Score tab, kept in memory. Read only.
 */
@SpringBootTest
@AutoConfigureMockMvc
class QuantScoreBoardTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private QuantScoreBoard board;

    @Autowired
    private ScorecardService scorecards;

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
    void everyCompanyHasTheScoreOfItsQuantScoreTab() {
        Board b = board.board("idx");
        int companies = jdbc.sql("SELECT count(*) FROM company WHERE exchange = 'IDX'").query(Integer.class).single();
        assertThat(b.exchange()).isEqualTo("IDX");
        assertThat(b.cached()).isFalse();
        assertThat(b.companies()).isEqualTo(companies).isEqualTo(b.scores().size());
        assertThat(b.scored()).isEqualTo((int) b.scores().stream().filter(s -> s.overall() != null).count());
        assertThat(b.scored()).isGreaterThan(companies / 2);

        Map<String, Score> byTicker = b.scores().stream().collect(Collectors.toMap(Score::ticker, Function.identity()));
        for (String ticker : List.of("HRTA", "BBCA", "GOTO", "AADI", "TLKM")) {
            if (!byTicker.containsKey(ticker)) {
                continue;
            }
            Scorecards tab = scorecards.scorecards("IDX", ticker);
            Score s = byTicker.get(ticker);
            assertThat(s.overall()).as(ticker).isEqualTo(tab.overall());
            assertThat(s.verdict()).as(ticker).isEqualTo(tab.verdict());
            assertThat(s.financial()).as(ticker).isEqualTo(tab.financial());
            assertThat(s.agentsCounted()).as(ticker)
                    .isEqualTo((int) tab.agents().stream().filter(AgentCard::inOverall).count());
        }
        assertThat(byTicker.get("BBCA").financial()).isTrue();
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
        mvc.perform(get("/api/v1/companies/quant-scores?exchange=IDX"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exchange").value("IDX"))
                .andExpect(jsonPath("$.scores[0].ticker").exists())
                .andExpect(jsonPath("$.minCoverage").isNumber());
        mvc.perform(get("/api/v1/companies/quant-scores?exchange=XYZ")).andExpect(status().isBadRequest());
    }
}
