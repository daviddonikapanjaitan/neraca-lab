package com.neracalab.backend.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import com.neracalab.backend.auth.Permission;
import com.neracalab.backend.auth.TestAccounts;
import com.neracalab.backend.auth.TestLogins;
import com.neracalab.backend.portfolio.PortfolioViews.Portfolio;
import com.neracalab.backend.portfolio.PortfolioViews.Position;
import com.neracalab.backend.portfolio.PortfolioViews.PositionRequest;
import com.neracalab.backend.portfolio.PortfolioViews.SettingsRequest;
import com.neracalab.backend.portfolio.PortfolioViews.Slice;

/**
 * Portfolio &gt; Position against the Docker Postgres, with users made for the test (their portfolios go with them):
 * the sheet's formulas, the Fundamentals figures it shows, USD reporters in IDR, one portfolio per user, the API's
 * answers to wrong input.
 */
@SpringBootTest
class PortfolioTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private PortfolioService service;

    @Autowired
    private JdbcClient jdbc;

    private TestAccounts accounts;
    private long owner;
    private long other;
    private long noAccess;

    @BeforeEach
    void users() {
        accounts = new TestAccounts(context);
        long role = accounts.role("portfolio", Permission.COMPANIES);
        long ingestion = accounts.role("no-companies", Permission.INGESTION);
        owner = accounts.user("owner", role);
        other = accounts.user("other", role);
        noAccess = accounts.user("none", ingestion);
    }

    @AfterEach
    void cleanup() {
        accounts.cleanup();
    }

    private static Position of(Portfolio p, String ticker) {
        return p.positions().stream().filter(x -> x.ticker().equals(ticker)).findFirst().orElseThrow();
    }

    @Test
    void theSheetsColumnsAndMetrics() {
        Portfolio empty = service.portfolio(owner);
        assertThat(empty.positions()).isEmpty();
        assertThat(empty.summary().cash()).isZero();
        assertThat(empty.summary().totalProfitPct()).isNull();

        service.saveSettings(owner, new SettingsRequest(new BigDecimal("4852232"), new BigDecimal("150000000")));
        service.add(owner, new PositionRequest("IDX", "hrta", new BigDecimal("317.11"), new BigDecimal("80600"),
                LocalDate.of(2022, 5, 3)));
        service.add(owner, new PositionRequest("IDX", "LSIP", new BigDecimal("1537.92"), new BigDecimal("6600"), null));
        Portfolio p = service.add(owner, new PositionRequest("IDX", "SMDR", new BigDecimal("396.95"),
                new BigDecimal("50600"), LocalDate.of(2022, 12, 26)));

        assertThat(p.positions()).extracting(Position::ticker).containsExactly("HRTA", "LSIP", "SMDR");

        // HRTA against Companies > Fundamentals
        Position h = of(p, "HRTA");
        Map<String, Object> km = jdbc.sql("""
                        SELECT k.book_value_per_share, k.net_income_to_parent_annualized / k.shares AS eps,
                               k.debt_to_equity, k.ebitda_annualized / 1e9 AS ebitda,
                               k.operating_income_annualized / 1e9 AS op, b.total_liabilities / 1e9 AS debt,
                               b.cash_and_equivalents / 1e9 AS cash, k.period_type || ' ' || k.fiscal_year AS filing
                        FROM v_key_metrics k JOIN balance_sheet b ON b.period_id = k.period_id
                        WHERE k.ticker = 'HRTA' ORDER BY k.period_end DESC LIMIT 1""").query().singleRow();
        double price = jdbc.sql("""
                        SELECT close_price FROM price_daily p JOIN company c USING (company_id)
                        WHERE c.ticker = 'HRTA' AND close_price IS NOT NULL ORDER BY trading_date DESC LIMIT 1""")
                .query(Double.class).single();
        assertThat(h.companyName()).isNotBlank();
        assertThat(h.lastPrice()).isEqualTo(price);
        assertThat(h.bookValuePerShare()).isCloseTo(num(km, "book_value_per_share"), within(1e-6));
        assertThat(h.forwardEps()).isCloseTo(num(km, "eps"), within(1e-6));
        assertThat(h.debtToEquity()).isCloseTo(num(km, "debt_to_equity"), within(1e-9));
        assertThat(h.ebitdaForward()).isCloseTo(num(km, "ebitda"), within(1e-6));
        assertThat(h.operatingProfit()).isCloseTo(num(km, "op"), within(1e-6));
        assertThat(h.totalDebt()).isCloseTo(num(km, "debt"), within(1e-6));
        assertThat(h.cash()).isCloseTo(num(km, "cash"), within(1e-6));
        assertThat(h.lastFiling()).isEqualTo(km.get("filing"));
        assertThat(h.convertedFrom()).isNull();
        assertThat(h.dateIn()).isEqualTo(LocalDate.of(2022, 5, 3));

        // the sheet's formulas
        assertThat(h.averageValue()).isCloseTo(317.11 * 80600, within(1e-6));
        assertThat(h.marketValue()).isCloseTo(price * 80600, within(1e-6));
        assertThat(h.gainLoss()).isCloseTo(h.marketValue() - h.averageValue(), within(1e-6));
        assertThat(h.gainLossPct()).isCloseTo(h.gainLoss() / h.averageValue(), within(1e-12));
        assertThat(h.marketCap()).isCloseTo(price * h.sharesOutstanding(), within(1e-6));
        assertThat(h.ev()).isCloseTo(h.totalDebt() + h.marketCap() - h.cash(), within(1e-6));
        assertThat(h.evEbitda()).isCloseTo(h.ev() / h.ebitdaForward(), within(1e-9));
        assertThat(h.evOp()).isCloseTo(h.ev() / h.operatingProfit(), within(1e-9));
        assertThat(h.per()).isCloseTo(price / h.forwardEps(), within(1e-9));
        assertThat(h.pbv()).isCloseTo(price / h.bookValuePerShare(), within(1e-9));
        if (h.dividend() != null) {
            assertThat(h.dividendYear()).isBetween(LocalDate.now().getYear() - 1, LocalDate.now().getYear());
            assertThat(h.dividendYield()).isCloseTo(h.dividend() / price, within(1e-12));
        }

        // a USD reporter in IDR: its total liabilities are trillions of rupiah, not under one billion dollars
        Position s = of(p, "SMDR");
        assertThat(s.convertedFrom()).isEqualTo("USD");
        assertThat(s.totalDebt()).isGreaterThan(1_000);
        assertThat(s.bookValuePerShare()).isGreaterThan(50);
        assertThat(s.lastPrice()).isGreaterThan(50);

        // the metrics
        var m = p.summary();
        double average = p.positions().stream().mapToDouble(Position::averageValue).sum();
        double market = p.positions().stream().mapToDouble(Position::marketValue).sum();
        assertThat(m.positions()).isEqualTo(3);
        assertThat(m.priced()).isEqualTo(3);
        assertThat(m.cash()).isEqualTo(4852232);
        assertThat(m.totalInvestment()).isEqualTo(150_000_000);
        assertThat(m.averageTotal()).isCloseTo(average, within(1e-6));
        assertThat(m.marketTotal()).isCloseTo(market, within(1e-6));
        assertThat(m.profitMarket()).isCloseTo(market - average, within(1e-6));
        assertThat(m.profitMarketPct()).isCloseTo((market - average) / average, within(1e-12));
        assertThat(m.totalProfit()).isCloseTo(market + 4852232 - 150_000_000, within(1e-6));
        assertThat(m.totalProfitPct()).isCloseTo((market + 4852232 - 150_000_000) / 150_000_000, within(1e-12));
        assertThat(h.portfolioMarketPct()).isCloseTo(h.marketValue() / (market + 4852232), within(1e-12));
        assertThat(h.portfolioAveragePct()).isCloseTo(h.averageValue() / (average + 4852232), within(1e-12));

        // the pie: every sector and the cash, adding up to the whole
        assertThat(p.allocation()).last().satisfies(c -> {
            assertThat(c.cash()).isTrue();
            assertThat(c.value()).isEqualTo(4852232);
        });
        assertThat(p.allocation().stream().mapToDouble(Slice::share).sum()).isCloseTo(1, within(1e-9));
        assertThat(p.allocation().stream().mapToDouble(Slice::value).sum()).isCloseTo(market + 4852232, within(1e-6));

        // another user's portfolio is their own
        assertThat(service.portfolio(other).positions()).isEmpty();
        assertThat(service.portfolio(other).summary().cash()).isZero();
    }

    @Test
    void theApi() throws Exception {
        String token = accounts.token(owner);
        MockMvc mvc = TestLogins.mockMvc(context, token);
        mvc.perform(post("/api/v1/portfolio/positions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"exchange\":\"IDX\",\"ticker\":\"HRTA\",\"averagePrice\":317.11,\"shares\":80600,"
                                + "\"dateIn\":\"2022-05-03\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.positions[0].ticker").value("HRTA"))
                .andExpect(jsonPath("$.positions[0].lastFiling").isString())
                .andExpect(jsonPath("$.summary.positions").value(1));
        // held already, unknown, wrong numbers, a future date
        mvc.perform(post("/api/v1/portfolio/positions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"exchange\":\"IDX\",\"ticker\":\"HRTA\",\"averagePrice\":300,\"shares\":100}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/portfolio/positions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"exchange\":\"IDX\",\"ticker\":\"ZZZZ\",\"averagePrice\":300,\"shares\":100}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/portfolio/positions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"exchange\":\"IDX\",\"ticker\":\"LSIP\",\"averagePrice\":-1,\"shares\":1.5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.averagePrice").exists())
                .andExpect(jsonPath("$.errors.shares").exists());
        mvc.perform(post("/api/v1/portfolio/positions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"exchange\":\"IDX\",\"ticker\":\"LSIP\",\"averagePrice\":1500,\"shares\":100,"
                                + "\"dateIn\":\"" + LocalDate.now().plusDays(5) + "\"}"))
                .andExpect(status().isBadRequest());

        long id = service.portfolio(owner).positions().getFirst().positionId();
        mvc.perform(put("/api/v1/portfolio/positions/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"averagePrice\":320,\"shares\":100000,\"dateIn\":\"2022-05-04\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positions[0].averagePrice").value(320.0))
                .andExpect(jsonPath("$.positions[0].shares").value(100000.0))
                .andExpect(jsonPath("$.positions[0].dateIn").value("2022-05-04"));
        mvc.perform(put("/api/v1/portfolio/settings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cash\":1000000,\"totalInvestment\":20000000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.cash").value(1000000.0))
                .andExpect(jsonPath("$.allocation[-1:].name").value("Cash"));
        mvc.perform(put("/api/v1/portfolio/settings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cash\":-5,\"totalInvestment\":0}"))
                .andExpect(status().isBadRequest());

        // another user cannot touch it, a user without COMPANIES cannot open the page
        MockMvc stranger = TestLogins.mockMvc(context, accounts.token(other));
        stranger.perform(delete("/api/v1/portfolio/positions/" + id)).andExpect(status().isNotFound());
        stranger.perform(get("/api/v1/portfolio")).andExpect(status().isOk())
                .andExpect(jsonPath("$.positions").isEmpty());
        TestLogins.mockMvc(context, accounts.token(noAccess)).perform(get("/api/v1/portfolio"))
                .andExpect(status().isForbidden());

        mvc.perform(delete("/api/v1/portfolio/positions/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.positions").isEmpty());
        mvc.perform(delete("/api/v1/portfolio/positions/" + id)).andExpect(status().isNotFound());
    }

    private static double num(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).doubleValue();
    }
}
