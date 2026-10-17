package com.neracalab.backend.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.crawl.SourceException;
import com.neracalab.backend.market.WorldBankPinkSheetClient.MonthlyValue;

import tools.jackson.databind.json.JsonMapper;

/**
 * The IMF PCPS parser on an answer shaped like {@code /data/dataflow/IMF.RES/PCPS/+/G001.PCOBA.USD.M} (SDMX-JSON: one
 * series, observations keyed by the index of their TIME_PERIOD).
 */
class ImfPcpsClientTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static String answer(String observations, String periods) {
        return """
                {"meta":{},"data":{"dataSets":[{"structure":0,"action":"Replace","series":{"0:0:0:0":{"attributes":[0,null,null],
                 "observations":{%s}}}}],
                 "structures":[{"dimensions":{"series":[{"id":"COUNTRY"},{"id":"INDICATOR"}],
                 "observation":[{"id":"TIME_PERIOD","values":[%s]}]}}]}}""".formatted(observations, periods);
    }

    @Test
    void readsTheMonthsInOrder() {
        List<MonthlyValue> cobalt = ImfPcpsClient.parse(answer(
                "\"2\":[\"42212.61904761905\",null,0],\"0\":[\"30920.0019836426\",null,0],\"1\":[null,null,0]",
                "{\"value\":\"1992-M01\"},{\"value\":\"1992-M02\"},{\"value\":\"2026-M09\"}"), "PCOBA", JSON);

        // February has no price: left out
        assertThat(cobalt).extracting(MonthlyValue::month).containsExactly(LocalDate.of(1992, 1, 1), LocalDate.of(2026, 9, 1));
        assertThat(cobalt.getLast().value()).isEqualTo(42212.61904761905);
    }

    @Test
    void anUnexpectedAnswerFails() {
        assertThatThrownBy(() -> ImfPcpsClient.parse("<html>", "PCOBA", JSON)).isInstanceOf(SourceException.class)
                .hasMessageContaining("not JSON");
        assertThatThrownBy(() -> ImfPcpsClient.parse(answer("\"0\":[\"1\",null,0]", "{\"value\":\"1992-Q1\"}"), "PCOBA", JSON))
                .isInstanceOf(SourceException.class).hasMessageContaining("is not a month");
        assertThatThrownBy(() -> ImfPcpsClient.parse(answer("\"0\":[\"-5\",null,0]", "{\"value\":\"1992-M01\"}"), "PCOBA", JSON))
                .isInstanceOf(SourceException.class).hasMessageContaining("is -5.0");
        assertThatThrownBy(() -> ImfPcpsClient.parse(answer("", "{\"value\":\"1992-M01\"}"), "PWPULP", JSON))
                .isInstanceOf(SourceException.class).hasMessageContaining("has no monthly value");
        assertThatThrownBy(() -> ImfPcpsClient.parse("{\"data\":{\"dataSets\":[{\"series\":{}}],\"structures\":[{\"dimensions\":"
                + "{\"observation\":[{\"id\":\"TIME_PERIOD\",\"values\":[]}]}}]}}", "PWPULP", JSON))
                .isInstanceOf(SourceException.class).hasMessageContaining("expected one series, found 0");
    }
}
