package com.neracalab.backend.macro.outlook;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.technical.engine.Candle;

/**
 * The commodities and currency pairs read for Indonesia: an export price up is supportive, an import price up a
 * headwind, a weaker rupiah a headwind, stale data neutral; the terms of trade sum them.
 */
class MarketBoardTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 21);

    @Test
    void exportsImportsAndTheRupiahAreReadForIndonesia() {
        List<MarketBoard.Input> inputs = List.of(
                daily("COAL", "Coal (Newcastle)", "COMMODITY", 100, 110),         // +10% in 3 months: supportive
                daily("NICKEL", "Nickel", "COMMODITY", 100, 107),                  // +7%: supportive
                daily("BRENT", "Crude Oil (Brent)", "COMMODITY", 100, 112),        // an import up 12%: a headwind
                daily("GOLD", "Gold", "COMMODITY", 100, 120),                      // mixed: neutral
                daily("USDIDR", "USD/IDR", "FOREX", 16000, 16500));                // rupiah 3% weaker: a headwind
        Map<String, Object> board = MarketBoard.compute(inputs, AS_OF);
        List<Map<String, Object>> commodities = rows(board, "commodities");
        assertThat(commodities).extracting(r -> r.get("code")).containsExactly("COAL", "NICKEL", "BRENT", "GOLD");
        assertThat(commodities.get(0).get("tone")).isEqualTo("POSITIVE");
        assertThat(commodities.get(0).get("role")).isEqualTo("EXPORT");
        assertThat((Double) commodities.get(0).get("change3mPct")).isCloseTo(10.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(commodities.get(2).get("tone")).isEqualTo("NEGATIVE");
        assertThat(commodities.get(3).get("tone")).isEqualTo("NEUTRAL");
        assertThat((String) commodities.get(0).get("summary")).contains("supportive").contains("thermal coal");
        assertThat(commodities.get(0).get("points")).isNotNull();
        List<Map<String, Object>> forex = rows(board, "forex");
        assertThat(forex.getFirst().get("tone")).isEqualTo("NEGATIVE");
        assertThat((String) forex.getFirst().get("summary")).contains("a weaker rupiah");

        @SuppressWarnings("unchecked")
        Map<String, Object> terms = (Map<String, Object>) board.get("termsOfTrade");
        // coal +1, nickel +1, Brent -1 (gold and the rupiah do not vote)
        assertThat(terms.get("score")).isEqualTo(1);
        assertThat(terms.get("signals")).isEqualTo(3);
        assertThat(terms.get("state")).isEqualTo("NEUTRAL");
        assertThat((String) terms.get("summary")).contains("For: Coal (Newcastle) +10.0%").contains("Against: Crude Oil");
    }

    @Test
    void staleDataIsNeutralAndAnEmptyBoardSaysSo() {
        // the last daily bar 30 days old
        List<Candle> old = new ArrayList<>();
        for (int d = 200; d >= 30; d--) {
            old.add(Candle.daily(AS_OF.minusDays(d), 100 + d, 100 + d, 100 + d, 100 + d, 100 + d, 0));
        }
        Map<String, Object> board = MarketBoard.compute(List.of(new MarketBoard.Input("COAL", "Coal", "COMMODITY",
                "USD", "DAILY", old)), AS_OF);
        Map<String, Object> coal = rows(board, "commodities").getFirst();
        assertThat(coal.get("stale")).isEqualTo(true);
        assertThat(coal.get("tone")).isEqualTo("NEUTRAL");
        assertThat((String) coal.get("summary")).contains("too old");

        Map<String, Object> empty = MarketBoard.compute(List.of(), AS_OF);
        @SuppressWarnings("unchecked")
        Map<String, Object> terms = (Map<String, Object>) empty.get("termsOfTrade");
        assertThat(terms.get("state")).isNull();
        assertThat((String) terms.get("summary")).startsWith("No commodity prices are stored yet");
    }

    /** Daily bars from {@code start} 6 months ago, linear to {@code end} today. */
    private static MarketBoard.Input daily(String code, String name, String market, double start, double end) {
        List<Candle> bars = new ArrayList<>();
        LocalDate first = AS_OF.minusMonths(6);
        long days = first.until(AS_OF, java.time.temporal.ChronoUnit.DAYS);
        for (long d = 0; d <= days; d++) {
            LocalDate date = first.plusDays(d);
            // flat for 3 months, then the move over the last 3 months
            double close = date.isBefore(AS_OF.minusMonths(3)) ? start
                    : start + (end - start) * (double) AS_OF.minusMonths(3).until(date, java.time.temporal.ChronoUnit.DAYS)
                    / AS_OF.minusMonths(3).until(AS_OF, java.time.temporal.ChronoUnit.DAYS);
            bars.add(Candle.daily(date, close, close, close, close, close, 0));
        }
        return new MarketBoard.Input(code, name, market, "USD", "DAILY", bars);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> board, String key) {
        return (List<Map<String, Object>>) board.get(key);
    }
}
