package com.neracalab.backend.technical.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.technical.TechnicalProperties;
import com.neracalab.backend.technical.TechnicalTestData;
import com.neracalab.backend.technical.idx.IdxTickSize;

/**
 * The Technical Score (a company's Technical (Score) tab and the company list's column): each preset as
 * 50 + 50 x score / max on 0-100, the overall their average, its stance by the share of the maximum. No database.
 */
class TechnicalScoreTest {

    private static Map<String, Object> preset(String preset, String name, double score, double max, String stance) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("preset", preset);
        m.put("name", name);
        m.put("stance", stance);
        m.put("score", score);
        m.put("maxScore", max);
        return m;
    }

    private static Map<String, Object> summary(List<Map<String, Object>> presets) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("summaries", presets);
        return m;
    }

    @Test
    void agentScoresMapTheRulesScoreTo0To100() {
        assertThat(TechnicalScore.agentScore(7.0, 7.0)).isEqualTo(100);
        assertThat(TechnicalScore.agentScore(-7.0, 7.0)).isEqualTo(0);
        assertThat(TechnicalScore.agentScore(0.0, 6.0)).isEqualTo(50);
        assertThat(TechnicalScore.agentScore(3.0, 6.0)).isEqualTo(75);
        assertThat(TechnicalScore.agentScore(1.5, 4.5)).isEqualTo(66.7);
        // clamped, and no maximum = neutral
        assertThat(TechnicalScore.agentScore(9.0, 6.0)).isEqualTo(100);
        assertThat(TechnicalScore.agentScore(1.0, 0.0)).isEqualTo(50);
        assertThat(TechnicalScore.agentScore(null, 6.0)).isEqualTo(50);
    }

    @Test
    void theOverallIsTheAverageWithItsStance() {
        TechnicalScore.Score s = TechnicalScore.of(summary(List.of(
                preset("murphy", "Murphy", 7, 7, "BUY"),
                preset("gill", "Keith Gill", 3, 6, "BUY"),
                preset("ptj", "Paul Tudor Jones", 0, 4.5, "WAIT"),
                preset("druckenmiller", "Druckenmiller", -2.5, 5, "AVOID"))));
        // 100, 75, 50, 25
        assertThat(s.overall()).isEqualTo(62.5);
        assertThat(s.stance()).isEqualTo("ACCUMULATE");
        assertThat(s.agents()).extracting(TechnicalScore.Agent::score).containsExactly(100.0, 75.0, 50.0, 25.0);
        assertThat(s.stances()).containsEntry("BUY", 2).containsEntry("ACCUMULATE", 0).containsEntry("WAIT", 1)
                .containsEntry("AVOID", 1);
        assertThat(s.summary()).startsWith("Overall 62.5 (Accumulate): 2 of 4 agents say Buy or Accumulate")
                .contains("Strongest Murphy 100.0 (Buy), weakest Druckenmiller 25.0 (Avoid)")
                .contains("disagree");
        assertThat(s.toMap()).containsKeys("overall", "stance", "agents", "stances", "summary", "method");
    }

    @Test
    void oneAgentBuying() {
        TechnicalScore.Score s = TechnicalScore.of(summary(List.of(
                preset("murphy", "Murphy", -2, 7, "AVOID"),
                preset("gill", "Keith Gill", 2.5, 6, "ACCUMULATE"),
                preset("ptj", "Paul Tudor Jones", -3, 4.5, "AVOID"),
                preset("druckenmiller", "Druckenmiller", -3, 5, "AVOID"))));
        // HRTA on 2026-11-11: 35.7, 70.8, 16.7, 20.0
        assertThat(s.overall()).isEqualTo(35.8);
        assertThat(s.stance()).isEqualTo("AVOID");
        assertThat(s.summary()).startsWith("Overall 35.8 (Avoid): 1 of 4 agents says Buy or Accumulate (1 Accumulate, 3 Avoid).");
    }

    @Test
    void stanceBands() {
        assertThat(TechnicalScore.stance(75)).isEqualTo("BUY");
        assertThat(TechnicalScore.stance(74.9)).isEqualTo("ACCUMULATE");
        assertThat(TechnicalScore.stance(58.4)).isEqualTo("ACCUMULATE");
        assertThat(TechnicalScore.stance(58.3)).isEqualTo("WAIT");
        assertThat(TechnicalScore.stance(41.7)).isEqualTo("WAIT");
        assertThat(TechnicalScore.stance(41.6)).isEqualTo("AVOID");
        assertThat(TechnicalScore.stance(0)).isEqualTo("AVOID");
    }

    @Test
    void noSummariesNoScore() {
        TechnicalScore.Score s = TechnicalScore.of(summary(List.of()));
        assertThat(s.overall()).isNull();
        assertThat(s.stance()).isNull();
        assertThat(s.agents()).isEmpty();
    }

    @Test
    void onTheRealHrtaSummaries() {
        TechnicalProperties properties = TechnicalTestData.properties();
        IdxTickSize ticks = new IdxTickSize(properties.tickSizes());
        List<Candle> ihsg = TechnicalTestData.fixture("IHSG");
        List<Candle> bars = TechnicalTestData.fixture("HRTA");
        MarketContext market = new MarketContext("IDX", ihsg.getLast().date(), "IHSG", ihsg, new LinkedHashMap<>());
        Map<String, Object> doc = new SnapshotCalculator(properties, ticks)
                .snapshot("IDX", "HRTA", "Hartadinata", null, "IDR", bars, List.of(), market);
        Map<String, Object> summary = PresetSummaries.summarize(
                new PresetSummaries.Input(doc, IndicatorEngine.compute(bars, Interval.D), ticks, false));

        TechnicalScore.Score s = TechnicalScore.of(summary);
        assertThat(s.agents()).extracting(TechnicalScore.Agent::preset)
                .containsExactly("murphy", "gill", "ptj", "druckenmiller");
        double mean = s.agents().stream().mapToDouble(TechnicalScore.Agent::score).average().orElseThrow();
        assertThat(s.overall()).isCloseTo(mean, org.assertj.core.data.Offset.offset(0.05));
        assertThat(s.overall()).isBetween(0.0, 100.0);
        assertThat(TechnicalScore.STANCES).contains(s.stance());
        for (TechnicalScore.Agent a : s.agents()) {
            assertThat(a.score()).isEqualTo(TechnicalScore.agentScore(a.raw(), a.maxScore()));
            assertThat(TechnicalScore.STANCES).contains(a.stance());
        }
        assertThat(BigDecimal.valueOf(s.overall()).scale()).isLessThanOrEqualTo(1);
    }
}
