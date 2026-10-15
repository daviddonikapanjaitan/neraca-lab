package com.neracalab.backend.technical.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import com.neracalab.backend.technical.TechnicalProperties;
import com.neracalab.backend.technical.TechnicalTestData;
import com.neracalab.backend.technical.idx.IdxTickSize;

/**
 * The snapshot on 300 days of real IDX data (HRTA, SMSM, MYOR to early October 2026, src/test/resources/technical) with
 * IHSG: the documented, versioned schema is complete, every level is a valid IDX price, and the result is
 * deterministic. No database, no model.
 */
class SnapshotCalculatorTest {

    private static final Set<String> TOP_LEVEL = Set.of("schemaVersion", "exchange", "ticker", "companyName", "sector",
            "currency", "asOfDate", "priceBasis", "history", "price", "trend", "momentum", "volatility", "volume",
            "structure", "relativeStrength", "marketRegime", "macro", "events", "risk", "notes");

    private final TechnicalProperties properties = TechnicalTestData.properties();
    private final IdxTickSize ticks = new IdxTickSize(properties.tickSizes());
    private final SnapshotCalculator calculator = new SnapshotCalculator(properties, ticks);
    private final List<Candle> ihsg = TechnicalTestData.fixture("IHSG");

    private MarketContext market() {
        Map<Long, MarketContext.Member> members = new LinkedHashMap<>();
        long id = 1;
        for (String t : List.of("HRTA", "SMSM", "MYOR")) {
            List<Candle> b = TechnicalTestData.fixture(t);
            int n = b.size();
            double close = b.getLast().close();
            members.put(id, new MarketContext.Member(id, t, t.equals("HRTA") ? "Consumer Cyclicals" : "Consumer Non-Cyclicals",
                    b.getLast().date(), Stats.pctChange(close, b.get(n - 1 - 126).close()),
                    Stats.pctChange(close, b.get(n - 1 - 252).close()),
                    close > b.subList(n - 200, n).stream().mapToDouble(Candle::close).average().orElseThrow()));
            id++;
        }
        Map<String, List<MarketContext.Observation>> macro = new LinkedHashMap<>();
        macro.put("BI_RATE", List.of());
        macro.put("SBN_10Y", List.of());
        macro.put("USD_IDR", List.of(new MarketContext.Observation(LocalDate.of(2026, 6, 30), 16000),
                new MarketContext.Observation(LocalDate.of(2026, 10, 1), 16800)));
        return new MarketContext("IDX", ihsg.getLast().date(), "IHSG", ihsg, members, macro);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HRTA", "SMSM", "MYOR"})
    @SuppressWarnings("unchecked")
    void completeSchemaOnRealData(String ticker) {
        List<Candle> bars = TechnicalTestData.fixture(ticker);
        Map<String, Object> doc = calculator.snapshot("IDX", ticker, ticker, "Consumer", "IDR", bars, List.of(), market());

        assertThat(doc.keySet()).containsExactlyInAnyOrderElementsOf(TOP_LEVEL);
        assertThat(doc.get("schemaVersion")).isEqualTo(SnapshotCalculator.SCHEMA_VERSION);
        assertThat(doc.get("asOfDate")).isEqualTo(bars.getLast().date().toString());
        assertThat(((Map<String, Object>) doc.get("history")).get("limited")).isEqualTo(false);
        Map<String, Object> trend = (Map<String, Object>) doc.get("trend");
        assertThat(trend).containsKeys("sma20", "sma50", "sma100", "sma200", "ema20", "ema50", "sma200Slope20dPct",
                "priceVsMaPct", "sma50Vs200Cross", "adx14", "plusDi14", "minusDi14");
        assertThat(trend.get("sma200")).isNotNull();
        Map<String, Object> cross = (Map<String, Object>) trend.get("sma50Vs200Cross");
        double sma50 = (Double) trend.get("sma50");
        double sma200 = (Double) trend.get("sma200");
        assertThat(cross.get("state")).isEqualTo(sma50 > sma200 ? "GOLDEN" : "DEATH");
        Map<String, Object> momentum = (Map<String, Object>) doc.get("momentum");
        assertThat(momentum.get("rsi14")).isNotNull();
        assertThat(((Map<String, Object>) momentum.get("rocPct")).get("d252")).isNotNull();
        assertThat(momentum.get("momentum12_1Pct")).isNotNull();
        Map<String, Object> volatility = (Map<String, Object>) doc.get("volatility");
        assertThat((Double) volatility.get("atrPctPercentile252")).isBetween(0.0, 100.0);
        Map<String, Object> volume = (Map<String, Object>) doc.get("volume");
        assertThat(volume.get("upDownVolumeRatio50")).isNotNull();
        assertThat(((Map<String, Object>) volume.get("volumeSpikes60")).get("upDays")).isNotNull();
        Map<String, Object> rs = (Map<String, Object>) doc.get("relativeStrength");
        assertThat(rs.get("ratio")).isNotNull();
        assertThat(rs.get("ratioChange50Pct")).isNotNull();
        assertThat((Double) rs.get("percentile12m")).isBetween(0.0, 100.0);
        Map<String, Object> regime = (Map<String, Object>) doc.get("marketRegime");
        assertThat(regime.get("indexAboveSma200")).isNotNull();
        assertThat(regime.get("breadthAbove200Pct")).isNotNull();
        Map<String, Object> macro = (Map<String, Object>) doc.get("macro");
        assertThat(((Map<String, Object>) macro.get("usdIdr")).get("changePct")).isEqualTo(5.0);
        assertThat(((Map<String, Object>) macro.get("biRate")).get("value")).isNull();
        assertThat((List<String>) doc.get("notes")).anyMatch(n -> n.contains("BI_RATE"));

        // every level is a valid IDX price
        Map<String, Object> s = (Map<String, Object>) doc.get("structure");
        List<Double> levels = new ArrayList<>();
        for (Map<String, Object> z : (List<Map<String, Object>>) s.get("zones")) {
            levels.add((Double) z.get("low"));
            levels.add((Double) z.get("high"));
            levels.add((Double) z.get("mid"));
        }
        Map<String, Object> r = (Map<String, Object>) s.get("retracement");
        assertThat(r).isNotNull();
        for (Map<String, Object> l : (List<Map<String, Object>>) r.get("levels")) {
            levels.add((Double) l.get("price"));
        }
        Map<String, Object> w52 = (Map<String, Object>) s.get("week52");
        levels.add((Double) w52.get("high"));
        levels.add((Double) w52.get("low"));
        levels.add((Double) ((Map<String, Object>) s.get("allTimeHigh")).get("price"));
        for (Map<String, Object> node : (List<Map<String, Object>>) ((Map<String, Object>) s.get("volumeProfile")).get("topNodes")) {
            levels.add((Double) node.get("price"));
        }
        assertThat((List<?>) s.get("zones")).isNotEmpty();
        assertThat(levels).isNotEmpty().allSatisfy(p -> assertThat(ticks.isValid(BigDecimal.valueOf(p))).as("%s", p).isTrue());
        assertThat((Double) w52.get("high")).isGreaterThanOrEqualTo((Double) w52.get("low"));
    }

    @Test
    void deterministic() {
        List<Candle> bars = TechnicalTestData.fixture("HRTA");
        MarketContext market = market();
        Map<String, Object> a = calculator.snapshot("IDX", "HRTA", "HRTA", null, "IDR", bars, List.of(), market);
        Map<String, Object> b = calculator.snapshot("IDX", "HRTA", "HRTA", null, "IDR", bars, List.of(), market);
        assertThat(a).isEqualTo(b);
        assertThat(SnapshotCalculator.inputHash(bars, List.of(), market, properties))
                .isEqualTo(SnapshotCalculator.inputHash(bars, List.of(), market, properties))
                .isNotEqualTo(SnapshotCalculator.inputHash(bars.subList(0, 299), List.of(), market, properties));
    }

    @Test
    @SuppressWarnings("unchecked")
    void limitedHistoryLeavesLongIndicatorsEmpty() {
        List<Candle> bars = TechnicalTestData.fixture("SMSM").subList(0, 120);
        Map<String, Object> doc = calculator.snapshot("IDX", "SMSM", "SMSM", null, "IDR", bars, List.of(), market());
        assertThat(((Map<String, Object>) doc.get("history")).get("limited")).isEqualTo(true);
        Map<String, Object> trend = (Map<String, Object>) doc.get("trend");
        assertThat(trend.get("sma200")).isNull();
        assertThat(trend.get("sma100")).isNotNull();
        assertThat(((Map<String, Object>) trend.get("sma50Vs200Cross")).get("state")).isNull();
        assertThat(((Map<String, Object>) ((Map<String, Object>) doc.get("momentum")).get("rocPct")).get("d252")).isNull();
        assertThat(((Map<String, Object>) doc.get("volatility")).get("atrPctPercentile252")).isNull();
        assertThat(((Map<String, Object>) ((Map<String, Object>) doc.get("structure")).get("week52")).get("complete")).isEqualTo(false);
        assertThat((List<String>) doc.get("notes")).anyMatch(n -> n.startsWith("Limited history: 120 trading days"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingSessionsAndStalePrices() {
        List<Candle> full = TechnicalTestData.fixture("HRTA");
        Map<String, Object> risk = (Map<String, Object>) calculator.snapshot("IDX", "HRTA", "HRTA", null, "IDR", full, List.of(),
                market()).get("risk");
        assertThat(risk.get("missingSessions60")).isEqualTo(0);
        assertThat(risk.get("possiblySuspended")).isEqualTo(false);

        // prices not refreshed for the last 4 sessions: stale, not a suspension
        List<Candle> older = full.subList(0, full.size() - 4);
        MarketContext market = market();
        long after = market.indexBars().stream().filter(b -> b.date().isAfter(older.getLast().date())).count();
        risk = (Map<String, Object>) calculator.snapshot("IDX", "HRTA", "HRTA", null, "IDR", older, List.of(), market).get("risk");
        assertThat(risk.get("sessionsAfterLastBar")).isEqualTo((int) after);
        assertThat(after).isGreaterThanOrEqualTo(4);
        assertThat(risk.get("possiblySuspended")).isEqualTo(false);

        // three sessions in a row without a bar within the last 60 bars: possibly suspended
        List<Candle> gap = new ArrayList<>(full);
        gap.remove(full.size() - 10);
        gap.remove(full.size() - 11);
        gap.remove(full.size() - 12);
        risk = (Map<String, Object>) calculator.snapshot("IDX", "HRTA", "HRTA", null, "IDR", gap, List.of(), market()).get("risk");
        assertThat(risk.get("missingSessions60")).isEqualTo(3);
        assertThat(risk.get("possiblySuspended")).isEqualTo(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void earningsReactionAndUpcomingEvents() {
        List<Candle> bars = TechnicalTestData.fixture("MYOR");
        LocalDate earnings = bars.get(250).date();
        LocalDate next = bars.getLast().date().plusDays(20);
        List<SnapshotCalculator.EventInfo> events = List.of(
                new SnapshotCalculator.EventInfo(earnings, "EARNINGS", null, "FY results", "test"),
                new SnapshotCalculator.EventInfo(next, "DIVIDEND", new BigDecimal("25"), "Cash dividend", "test"));
        Map<String, Object> doc = calculator.snapshot("IDX", "MYOR", "MYOR", null, "IDR", bars, events, market());
        Map<String, Object> e = (Map<String, Object>) doc.get("events");
        assertThat((List<Map<String, Object>>) e.get("upcoming")).singleElement()
                .satisfies(m -> assertThat(m.get("type")).isEqualTo("DIVIDEND"));
        Map<String, Object> last = (Map<String, Object>) e.get("lastEarnings");
        double gap = (bars.get(250).open() / bars.get(249).close() - 1) * 100;
        double follow = (bars.get(255).close() / bars.get(250).close() - 1) * 100;
        assertThat((Double) last.get("gapPct")).isCloseTo(gap, org.assertj.core.api.Assertions.within(1e-3));
        assertThat((Double) last.get("followThrough5dPct")).isCloseTo(follow, org.assertj.core.api.Assertions.within(1e-3));
    }
}
