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

    /**
     * The pairs added on 2026-10-23: a weaker yuan is a headwind, a weaker dollar (EUR/USD up) supportive, AUD/JPY
     * falling (risk aversion) a headwind, a peer currency neutral on its own; none of them votes in the terms of trade.
     */
    @Test
    void theYuanTheDollarAndRiskAppetiteAreReadForIndonesia() {
        Map<String, Object> board = MarketBoard.compute(List.of(
                daily("USDCNY", "USD/CNY", "FOREX", 7.0, 7.21),        // +3%: a weaker yuan
                daily("EURUSD", "EUR/USD", "FOREX", 1.10, 1.1275),     // +2.5%: a weaker dollar
                daily("AUDJPY", "AUD/JPY", "FOREX", 100, 94),          // -6%: risk aversion
                daily("USDMYR", "USD/MYR", "FOREX", 4.0, 4.2)), AS_OF); // +5%, a peer: neutral on its own
        List<Map<String, Object>> forex = rows(board, "forex");
        assertThat(forex).extracting(r -> r.get("role")).containsOnly("CURRENCY");
        assertThat(forex).extracting(r -> r.get("tone")).containsExactly("NEGATIVE", "POSITIVE", "NEGATIVE", "NEUTRAL");
        assertThat((String) forex.get(0).get("summary")).contains("a weaker yuan: a headwind")
                .contains("largest trading partner");
        assertThat((String) forex.get(1).get("summary")).contains("a weaker dollar: supportive");
        assertThat((String) forex.get(2).get("summary")).contains("risk aversion, carry trades unwinding: a headwind");
        assertThat((String) forex.get(3).get("summary")).contains("no strong move").contains("ringgit");
        @SuppressWarnings("unchecked")
        Map<String, Object> terms = (Map<String, Object>) board.get("termsOfTrade");
        assertThat(terms.get("signals")).isEqualTo(0);
    }

    /**
     * The rupiah against the median of its Asian peers over 3 months: 3 points weaker than the region is a pressure of
     * its own (a headwind), weaker with the region the dollar's, firmer than the region supportive.
     */
    @Test
    void theRupiahIsReadAgainstItsAsianPeers() {
        // USD/IDR +4%, the peers +1.0, +0.5, +1.5, +2.0, +1.0 (median +1.0): 3 points weaker than the region
        Map<String, Object> weaker = regional(MarketBoard.compute(List.of(
                daily("USDIDR", "USD/IDR", "FOREX", 16000, 16640),
                daily("USDCNY", "USD/CNY", "FOREX", 7.0, 7.07),
                daily("USDMYR", "USD/MYR", "FOREX", 4.0, 4.02),
                daily("USDPHP", "USD/PHP", "FOREX", 56, 56.84),
                daily("USDINR", "USD/INR", "FOREX", 85, 86.7),
                daily("USDKRW", "USD/KRW", "FOREX", 1400, 1414)), AS_OF));
        assertThat(weaker.get("state")).isEqualTo("UNDERPERFORMING");
        assertThat(weaker.get("tone")).isEqualTo("NEGATIVE");
        assertThat((Double) weaker.get("peersMedian3mPct")).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat((Double) weaker.get("gapPts")).isCloseTo(3.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat((List<?>) weaker.get("peers")).hasSize(5);
        assertThat((String) weaker.get("summary")).contains("USD/IDR moved +4.0%").contains("a median +1.0%")
                .contains("USD/CNY +1.0%").contains("3.0 points weaker than the region");

        // USD/IDR +3%, the peers +2.5, +2.2, +3.0 (median +2.5): with the region, a strong dollar
        Map<String, Object> withRegion = regional(MarketBoard.compute(List.of(
                daily("USDIDR", "USD/IDR", "FOREX", 16000, 16480),
                daily("USDCNY", "USD/CNY", "FOREX", 7.0, 7.175),
                daily("USDMYR", "USD/MYR", "FOREX", 4.0, 4.088),
                daily("USDKRW", "USD/KRW", "FOREX", 1400, 1442)), AS_OF));
        assertThat(withRegion.get("state")).isEqualTo("IN_LINE");
        assertThat(withRegion.get("tone")).isEqualTo("NEUTRAL");
        assertThat((String) withRegion.get("summary")).contains("a strong dollar weighs on all of Asia");

        // USD/IDR -2%, the peers 0, +0.5, +1.0 (median +0.5): 2.5 points firmer
        Map<String, Object> firmer = regional(MarketBoard.compute(List.of(
                daily("USDIDR", "USD/IDR", "FOREX", 16000, 15680),
                daily("USDCNY", "USD/CNY", "FOREX", 7.0, 7.0),
                daily("USDPHP", "USD/PHP", "FOREX", 56, 56.28),
                daily("USDINR", "USD/INR", "FOREX", 85, 85.85)), AS_OF));
        assertThat(firmer.get("state")).isEqualTo("OUTPERFORMING");
        assertThat(firmer.get("tone")).isEqualTo("POSITIVE");
        assertThat((String) firmer.get("summary")).contains("2.5 points firmer than the region");
    }

    @Test
    void tooFewPeersOrNoRupiahLeaveTheRegionalReadingUnknown() {
        Map<String, Object> twoPeers = regional(MarketBoard.compute(List.of(
                daily("USDIDR", "USD/IDR", "FOREX", 16000, 16640),
                daily("USDCNY", "USD/CNY", "FOREX", 7.0, 7.07),
                daily("USDMYR", "USD/MYR", "FOREX", 4.0, 4.02)), AS_OF));
        assertThat(twoPeers.get("state")).isNull();
        assertThat(twoPeers.get("tone")).isEqualTo("NEUTRAL");
        assertThat((String) twoPeers.get("summary")).startsWith("Only 2 of the rupiah's Asian peers");

        Map<String, Object> noRupiah = regional(MarketBoard.compute(List.of(
                daily("USDCNY", "USD/CNY", "FOREX", 7.0, 7.07)), AS_OF));
        assertThat(noRupiah.get("state")).isNull();
        assertThat((String) noRupiah.get("summary")).startsWith("No current USD/IDR price is stored");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> regional(Map<String, Object> board) {
        return (Map<String, Object>) board.get("regionalCurrencies");
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
