package com.neracalab.backend.technical.idx;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.technical.TechnicalProperties;
import com.neracalab.backend.technical.TechnicalTestData;
import com.neracalab.backend.technical.engine.Candle;

/** IDX price fractions and auto-rejection detection with the default (application.yaml) rules. */
class IdxRulesTest {

    private final TechnicalProperties properties = TechnicalTestData.properties();
    private final IdxTickSize ticks = new IdxTickSize(properties.tickSizes());
    private final AutoRejection rejection = new AutoRejection(properties, ticks);

    @Test
    void tickOfEachPriceBand() {
        assertThat(ticks.tick(new BigDecimal("50"))).isEqualByComparingTo("1");
        assertThat(ticks.tick(new BigDecimal("199"))).isEqualByComparingTo("1");
        assertThat(ticks.tick(new BigDecimal("200"))).isEqualByComparingTo("2");
        assertThat(ticks.tick(new BigDecimal("499.9"))).isEqualByComparingTo("2");
        assertThat(ticks.tick(new BigDecimal("500"))).isEqualByComparingTo("5");
        assertThat(ticks.tick(new BigDecimal("1995"))).isEqualByComparingTo("5");
        assertThat(ticks.tick(new BigDecimal("2000"))).isEqualByComparingTo("10");
        assertThat(ticks.tick(new BigDecimal("4990"))).isEqualByComparingTo("10");
        assertThat(ticks.tick(new BigDecimal("5000"))).isEqualByComparingTo("25");
        assertThat(ticks.tick(new BigDecimal("98000"))).isEqualByComparingTo("25");
    }

    @Test
    void roundsToValidPricesAlsoAcrossBandBoundaries() {
        assertThat(ticks.round(new BigDecimal("123.4"))).isEqualByComparingTo("123");
        assertThat(ticks.round(new BigDecimal("301"))).isEqualByComparingTo("302");      // half up to the 2-step
        assertThat(ticks.round(new BigDecimal("867.5"))).isEqualByComparingTo("870");    // TUGU 2022 close, split-adjusted
        assertThat(ticks.round(new BigDecimal("1998"))).isEqualByComparingTo("2000");    // the boundary is a multiple of 10
        assertThat(ticks.round(new BigDecimal("4994"))).isEqualByComparingTo("4990");
        assertThat(ticks.round(new BigDecimal("4996"))).isEqualByComparingTo("5000");
        assertThat(ticks.round(new BigDecimal("7712.4"))).isEqualByComparingTo("7700");
        assertThat(ticks.floor(new BigDecimal("499.5"))).isEqualByComparingTo("498");
        assertThat(ticks.ceil(new BigDecimal("499.5"))).isEqualByComparingTo("500");
        assertThat(ticks.ceil(new BigDecimal("4999"))).isEqualByComparingTo("5000");
        assertThat(ticks.floor(new BigDecimal("5010"))).isEqualByComparingTo("5000");
        assertThat(ticks.round(new BigDecimal("0.4"))).isEqualByComparingTo("1");          // never below the smallest tick
        for (double p = 1; p < 20_000; p += 3.37) {
            BigDecimal price = BigDecimal.valueOf(p);
            assertThat(ticks.isValid(ticks.round(price))).as("round(%s)", p).isTrue();
            assertThat(ticks.isValid(ticks.floor(price))).as("floor(%s)", p).isTrue();
            assertThat(ticks.isValid(ticks.ceil(price))).as("ceil(%s)", p).isTrue();
            assertThat(ticks.floor(price)).isLessThanOrEqualTo(price);
            assertThat(ticks.ceil(price)).isGreaterThanOrEqualTo(price);
        }
    }

    @Test
    void araIsAClosingAtTheHighAtTheUpperLimit() {
        // previous close 1,000 (band 200 .. 5,000: 25 %); limit 1,250
        Candle prev = Candle.daily(LocalDate.of(2025, 6, 2), 1000, 1000, 1000, 1000, 1000, 100);
        Candle ara = Candle.daily(LocalDate.of(2025, 6, 3), 1010, 1250, 1005, 1250, 1250, 900);
        Candle notAtHigh = Candle.daily(LocalDate.of(2025, 6, 3), 1010, 1250, 1005, 1240, 1240, 900);
        Candle short1 = Candle.daily(LocalDate.of(2025, 6, 3), 1010, 1200, 1005, 1200, 1200, 900);
        assertThat(rejection.hit(prev, ara)).isNotNull().extracting(AutoRejection.Hit::type).isEqualTo(AutoRejection.Type.ARA);
        assertThat(rejection.hit(prev, notAtHigh)).isNull();
        assertThat(rejection.hit(prev, short1)).isNull();
        // band 50 .. 200: 35 % (150 -> 202 is the limit 202.5 rounded down to the 2-step)
        Candle low = Candle.daily(LocalDate.of(2025, 6, 2), 150, 150, 150, 150, 150, 100);
        Candle lowAra = Candle.daily(LocalDate.of(2025, 6, 3), 152, 202, 151, 202, 202, 900);
        assertThat(rejection.hit(low, lowAra)).isNotNull();
    }

    @Test
    void arbFollowsTheRuleInForceThatDay() {
        Candle prev = Candle.daily(LocalDate.of(2025, 6, 2), 1000, 1000, 1000, 1000, 1000, 100);
        // since 2025-04-08 ARB is 15 %: 850 at the low is an ARB
        Candle arb2025 = Candle.daily(LocalDate.of(2025, 6, 3), 990, 995, 850, 850, 850, 900);
        assertThat(rejection.hit(prev, arb2025)).isNotNull().extracting(AutoRejection.Hit::type).isEqualTo(AutoRejection.Type.ARB);
        // in 2024 (symmetric, 25 %) the same day is not
        Candle prev2024 = Candle.daily(LocalDate.of(2024, 6, 3), 1000, 1000, 1000, 1000, 1000, 100);
        Candle down2024 = Candle.daily(LocalDate.of(2024, 6, 4), 990, 995, 850, 850, 850, 900);
        assertThat(rejection.hit(prev2024, down2024)).isNull();
        // in 2021 (7 %): 930 at the low is an ARB
        Candle prev2021 = Candle.daily(LocalDate.of(2021, 6, 1), 1000, 1000, 1000, 1000, 1000, 100);
        Candle arb2021 = Candle.daily(LocalDate.of(2021, 6, 2), 990, 995, 930, 930, 930, 900);
        assertThat(rejection.hit(prev2021, arb2021)).isNotNull();
        // no ARB at the minimum price of 50
        Candle floor = Candle.daily(LocalDate.of(2025, 6, 2), 50, 50, 50, 50, 50, 100);
        Candle stillFifty = Candle.daily(LocalDate.of(2025, 6, 3), 50, 50, 42, 42, 42, 100);
        assertThat(rejection.hit(floor, stillFifty)).isNull();
    }

    @Test
    void hitsOfASeries() {
        List<Candle> bars = List.of(
                Candle.daily(LocalDate.of(2025, 6, 2), 1000, 1000, 1000, 1000, 1000, 100),
                Candle.daily(LocalDate.of(2025, 6, 3), 1010, 1250, 1005, 1250, 1250, 900),
                Candle.daily(LocalDate.of(2025, 6, 4), 1250, 1270, 1240, 1260, 1260, 900),
                Candle.daily(LocalDate.of(2025, 6, 5), 1250, 1255, 1071, 1071, 1071, 900));
        assertThat(rejection.hits(bars)).extracting(AutoRejection.Hit::type)
                .containsExactly(AutoRejection.Type.ARA, AutoRejection.Type.ARB);
    }

    @Test
    void autoRejectionRulesAreOrderedByDate() {
        assertThat(properties.autoRejectionOn(LocalDate.of(2019, 1, 2)).arb().getFirst().pct()).isEqualByComparingTo("0.35");
        assertThat(properties.autoRejectionOn(LocalDate.of(2020, 3, 12)).arb().getFirst().pct()).isEqualByComparingTo("0.10");
        assertThat(properties.autoRejectionOn(LocalDate.of(2022, 1, 3)).arb().getFirst().pct()).isEqualByComparingTo("0.07");
        assertThat(properties.autoRejectionOn(LocalDate.of(2023, 7, 3)).arb().getFirst().pct()).isEqualByComparingTo("0.15");
        assertThat(properties.autoRejectionOn(LocalDate.of(2024, 7, 1)).arb().getFirst().pct()).isEqualByComparingTo("0.35");
        assertThat(properties.autoRejectionOn(LocalDate.of(2026, 10, 1)).arb().getFirst().pct()).isEqualByComparingTo("0.15");
    }
}
