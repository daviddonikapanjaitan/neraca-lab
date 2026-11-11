package com.neracalab.backend.portfolio.intraday;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.portfolio.intraday.IdxSession.Phase;
import com.neracalab.backend.portfolio.intraday.YahooIntradayClient.Session;
import com.neracalab.backend.price.provider.PriceProviderException;

import tools.jackson.databind.json.JsonMapper;

/** The IDX trading day and the reading of Yahoo's intraday chart. No database, no network. */
class IdxSessionAndYahooParseTest {

    private static Phase at(String dateTime) {
        return IdxSession.phase(ZonedDateTime.of(java.time.LocalDateTime.parse(dateTime), IdxSession.ZONE));
    }

    @Test
    void mondayToThursday() {
        // 2026-11-11 is a Wednesday
        assertThat(at("2026-11-11T08:30")).isEqualTo(Phase.CLOSED);
        assertThat(at("2026-11-11T08:50")).isEqualTo(Phase.PRE_OPENING);
        assertThat(at("2026-11-11T09:00")).isEqualTo(Phase.SESSION_1);
        assertThat(at("2026-11-11T11:59")).isEqualTo(Phase.SESSION_1);
        assertThat(at("2026-11-11T12:00")).isEqualTo(Phase.BREAK);
        assertThat(at("2026-11-11T13:30")).isEqualTo(Phase.SESSION_2);
        assertThat(at("2026-11-11T15:49")).isEqualTo(Phase.SESSION_2);
        assertThat(at("2026-11-11T15:50")).isEqualTo(Phase.PRE_CLOSING);
        assertThat(at("2026-11-11T16:05")).isEqualTo(Phase.POST_TRADING);
        assertThat(at("2026-11-11T16:15")).isEqualTo(Phase.CLOSED);
        assertThat(Phase.SESSION_2.trading()).isTrue();
        assertThat(Phase.BREAK.trading()).isFalse();
    }

    @Test
    void fridayHasTheLongerBreak() {
        // 2026-11-13 is a Friday
        assertThat(at("2026-11-13T11:29")).isEqualTo(Phase.SESSION_1);
        assertThat(at("2026-11-13T11:30")).isEqualTo(Phase.BREAK);
        assertThat(at("2026-11-13T13:45")).isEqualTo(Phase.BREAK);
        assertThat(at("2026-11-13T14:00")).isEqualTo(Phase.SESSION_2);
    }

    @Test
    void weekendsAreClosed() {
        assertThat(at("2026-11-14T10:00")).isEqualTo(Phase.CLOSED);
        assertThat(at("2026-11-15T14:00")).isEqualTo(Phase.CLOSED);
    }

    @Test
    void aChartIsTheLatestSession() {
        // 2026-11-11 09:00 WIB = 1794362400; a bar of the day before and one without a close are left out
        String json = """
                {"meta":{"currency":"IDR","regularMarketPrice":1490.0,"regularMarketTime":1794388281,
                         "chartPreviousClose":1465.0,"regularMarketDayHigh":1510.0,"regularMarketDayLow":1430.0,
                         "regularMarketVolume":4553400,
                         "currentTradingPeriod":{"regular":{"start":1794362400,"end":1794388500}}},
                 "timestamp":[1794276000,1794362400,1794362700,1794363000],
                 "indicators":{"quote":[{"open":[1400,1445,1430,null],"high":[1400,1450,1445,null],
                                         "low":[1400,1440,1425,null],"close":[1400,1445,1430,null],
                                         "volume":[10,1000,2000,null]}]}}""";
        Session s = YahooIntradayClient.parse("HRTA.JK", JsonMapper.builder().build().readTree(json));
        assertThat(s.sessionDate()).isEqualTo(LocalDate.of(2026, 11, 11));
        assertThat(s.price()).isEqualByComparingTo("1490");
        assertThat(s.previousClose()).isEqualByComparingTo("1465");
        assertThat(s.dayHigh()).isEqualByComparingTo("1510");
        assertThat(s.volume()).isEqualTo(4553400L);
        assertThat(s.bars()).hasSize(2);
        assertThat(s.bars().getFirst().close()).isEqualByComparingTo("1445");
        assertThat(s.dayOpen()).isEqualByComparingTo(new BigDecimal("1445"));
        assertThat(s.periodEnd().getEpochSecond()).isEqualTo(1794388500L);
    }

    @Test
    void withoutALastPriceThereIsNoSession() {
        String json = """
                {"meta":{"currency":"IDR"},"timestamp":[],"indicators":{"quote":[{}]}}""";
        assertThatThrownBy(() -> YahooIntradayClient.parse("X.JK", JsonMapper.builder().build().readTree(json)))
                .isInstanceOf(PriceProviderException.class);
    }
}
