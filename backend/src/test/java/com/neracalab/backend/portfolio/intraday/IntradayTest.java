package com.neracalab.backend.portfolio.intraday;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.WebApplicationContext;

import com.neracalab.backend.auth.Permission;
import com.neracalab.backend.auth.TestAccounts;
import com.neracalab.backend.auth.TestLogins;
import com.neracalab.backend.portfolio.PortfolioService;
import com.neracalab.backend.portfolio.PortfolioViews.Portfolio;
import com.neracalab.backend.portfolio.PortfolioViews.Position;
import com.neracalab.backend.portfolio.PortfolioViews.PositionRequest;
import com.neracalab.backend.portfolio.intraday.YahooIntradayClient.Bar;
import com.neracalab.backend.portfolio.intraday.YahooIntradayClient.Session;

/**
 * The intraday prices of Portfolio &gt; Position against the Docker Postgres, Yahoo Finance stubbed: a refresh stores the
 * session, the page shows it as the last price with the day's change, a fresh quote is not fetched again, an older
 * session leaves the daily close, the schedule does nothing outside the session - and neither the position nor the
 * daily prices are ever changed. A company no portfolio holds and without a stored quote is used, and its intraday rows
 * are deleted afterwards.
 */
@SpringBootTest
class IntradayTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private PortfolioService portfolios;

    @Autowired
    private IntradayService intraday;

    @Autowired
    private JdbcClient jdbc;

    @MockitoBean
    private YahooIntradayClient yahoo;

    private TestAccounts accounts;
    private long owner;
    private long companyId;
    private String ticker;
    private LocalDate lastDaily;
    private BigDecimal lastClose;

    @BeforeEach
    void setUp() {
        accounts = new TestAccounts(context);
        owner = accounts.user("intraday", accounts.role("intraday", Permission.COMPANIES));
        Map<String, Object> c = jdbc.sql("""
                        SELECT c.company_id, c.ticker FROM company c
                        WHERE c.exchange = 'IDX' AND c.active AND c.currency = 'IDR'
                          AND NOT EXISTS (SELECT 1 FROM intraday_quote q WHERE q.company_id = c.company_id)
                          AND NOT EXISTS (SELECT 1 FROM portfolio_position p WHERE p.company_id = c.company_id)
                          AND (SELECT count(*) FROM price_daily d WHERE d.company_id = c.company_id) > 300
                        ORDER BY (c.ticker = 'BBCA') DESC, c.ticker LIMIT 1""").query().singleRow();
        companyId = ((Number) c.get("company_id")).longValue();
        ticker = (String) c.get("ticker");
        Map<String, Object> last = jdbc.sql("""
                        SELECT trading_date, close_price FROM price_daily
                        WHERE company_id = :c AND close_price IS NOT NULL ORDER BY trading_date DESC LIMIT 1""")
                .param("c", companyId).query().singleRow();
        lastDaily = ((java.sql.Date) last.get("trading_date")).toLocalDate();
        lastClose = (BigDecimal) last.get("close_price");
        portfolios.add(owner, new PositionRequest("IDX", ticker, lastClose, new BigDecimal("1000"),
                LocalDate.of(2024, 1, 2)));
        intraday.clock(Clock.system(IdxSession.ZONE));
    }

    @AfterEach
    void cleanup() {
        jdbc.sql("DELETE FROM intraday_bar WHERE company_id = :c").param("c", companyId).update();
        jdbc.sql("DELETE FROM intraday_quote WHERE company_id = :c").param("c", companyId).update();
        accounts.cleanup();
        intraday.clock(Clock.system(IdxSession.ZONE));
    }

    /** A session the day after the last stored close: the price 2% up. */
    private Session session(LocalDate date, BigDecimal price, BigDecimal previous) {
        Instant open = date.atTime(9, 0).atZone(IdxSession.ZONE).toInstant();
        List<Bar> bars = List.of(new Bar(open, previous, price, previous, previous, 100L),
                new Bar(open.plusSeconds(300), previous, price, previous, price, 200L),
                new Bar(open.plusSeconds(600), price, price, price, price, 300L));
        return new Session(ticker + ".JK", "IDR", date, price, previous, previous, price, previous, 600L,
                open.plusSeconds(660), open, open.plusSeconds(26_100), bars);
    }

    private Map<String, Object> position() {
        return jdbc.sql("""
                        SELECT position_id, average_price, shares, date_in, updated_at FROM portfolio_position
                        WHERE user_id = :u""").param("u", owner).query().singleRow();
    }

    private Map<String, Object> daily() {
        return jdbc.sql("""
                        SELECT count(*) AS n, sum(close_price) AS closes, max(trading_date) AS last,
                               sum(volume) AS volume FROM price_daily WHERE company_id = :c""")
                .param("c", companyId).query().singleRow();
    }

    @Test
    void aRefreshShowsTheSessionAndChangesNothingElse() {
        Map<String, Object> positionBefore = position();
        Map<String, Object> dailyBefore = daily();
        LocalDate today = lastDaily.plusDays(1);
        BigDecimal price = lastClose.multiply(new BigDecimal("1.02")).setScale(0, java.math.RoundingMode.HALF_UP);
        when(yahoo.fetch(anyString())).thenReturn(session(today, price, lastClose));

        IntradayService.Refresh r = intraday.refreshUser(owner);
        assertThat(r.companies()).isEqualTo(1);
        assertThat(r.fetched()).isEqualTo(1);
        assertThat(r.failed()).isEmpty();

        Portfolio p = portfolios.portfolio(owner);
        Position pos = p.positions().getFirst();
        assertThat(pos.priceSource()).isEqualTo("INTRADAY");
        assertThat(pos.lastPrice()).isEqualTo(price.doubleValue());
        assertThat(pos.priceDate()).isEqualTo(today);
        assertThat(pos.change()).isCloseTo(price.subtract(lastClose).doubleValue(), within(1e-9));
        assertThat(pos.marketValue()).isCloseTo(price.doubleValue() * 1000, within(1e-6));
        assertThat(pos.dayGain()).isCloseTo(pos.change() * 1000, within(1e-6));
        assertThat(pos.intraday().points()).hasSize(3);
        assertThat(pos.intraday().sessionDate()).isEqualTo(today);
        assertThat(p.summary().dayGain()).isCloseTo(pos.dayGain(), within(1e-6));
        assertThat(p.summary().dayGainPct()).isCloseTo(pos.change() / lastClose.doubleValue(), within(1e-9));
        assertThat(p.live().sessionDate()).isEqualTo(today);
        assertThat(p.live().everyMinutes()).isEqualTo(5);

        // fetched a moment ago: not again
        IntradayService.Refresh again = intraday.refreshUser(owner);
        assertThat(again.fresh()).isEqualTo(1);
        assertThat(again.fetched()).isZero();
        verify(yahoo, times(1)).fetch(anyString());

        // the position and the daily prices are untouched
        assertThat(position()).isEqualTo(positionBefore);
        assertThat(daily()).isEqualTo(dailyBefore);
    }

    @Test
    void anOlderSessionLeavesTheDailyClose() {
        when(yahoo.fetch(anyString())).thenReturn(session(lastDaily.minusDays(3), new BigDecimal("1"), lastClose));
        intraday.refreshUser(owner);
        Position pos = portfolios.portfolio(owner).positions().getFirst();
        assertThat(pos.priceSource()).isEqualTo("DAILY");
        assertThat(pos.lastPrice()).isEqualTo(lastClose.doubleValue());
        assertThat(pos.priceDate()).isEqualTo(lastDaily);
        // the stored session is still shown as the latest one Yahoo had
        assertThat(pos.intraday()).isNotNull();
    }

    @Test
    void theScheduleFetchesDuringTheSessionOnly() {
        when(yahoo.fetch(anyString())).thenReturn(session(lastDaily.plusDays(1), lastClose, lastClose));
        // a Saturday, and a Wednesday evening
        intraday.clock(Clock.fixed(ZonedDateTime.of(2026, 11, 14, 10, 0, 0, 0, IdxSession.ZONE).toInstant(),
                IdxSession.ZONE));
        assertThat(intraday.refreshScheduled()).isNull();
        intraday.clock(Clock.fixed(ZonedDateTime.of(2026, 11, 11, 19, 0, 0, 0, IdxSession.ZONE).toInstant(),
                IdxSession.ZONE));
        assertThat(intraday.refreshScheduled()).isNull();
        verify(yahoo, never()).fetch(anyString());
        assertThat(intraday.phase()).isEqualTo(IdxSession.Phase.CLOSED);
    }

    @Test
    void theApi() throws Exception {
        when(yahoo.fetch(anyString())).thenReturn(session(lastDaily.plusDays(1), lastClose, lastClose));
        TestLogins.mockMvc(context, accounts.token(owner)).perform(post("/api/v1/portfolio/intraday/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positions[0].priceSource").value("INTRADAY"))
                .andExpect(jsonPath("$.positions[0].intraday.points.length()").value(3))
                .andExpect(jsonPath("$.live.phase").isString())
                .andExpect(jsonPath("$.summary.dayGain").isNumber());
    }
}
