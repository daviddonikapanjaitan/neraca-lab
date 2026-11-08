package com.neracalab.backend.company.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.company.profile.CompanyProfileViews.DividendRow;
import com.neracalab.backend.company.profile.CompanyProfileViews.DividendYear;
import com.neracalab.backend.company.profile.DividendHistory.FiscalYear;
import com.neracalab.backend.company.profile.DividendHistory.Input;
import com.neracalab.backend.company.profile.DividendHistory.Rate;
import com.neracalab.backend.company.profile.DividendHistory.Result;
import com.neracalab.backend.company.profile.DividendHistory.Split;
import com.neracalab.backend.company.profile.DividendHistory.YahooDividend;
import com.neracalab.backend.company.profile.IdxProfileParser.Dividend;

/** Yields on the cum-date close, the share basis after splits, USD reporters and the payout ratios. */
class DividendHistoryTest {

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    private static BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    private static TreeMap<LocalDate, BigDecimal> closes(Object... dayPrice) {
        TreeMap<LocalDate, BigDecimal> m = new TreeMap<>();
        for (int i = 0; i < dayPrice.length; i += 2) {
            m.put(d((String) dayPrice[i]), n((String) dayPrice[i + 1]));
        }
        return m;
    }

    @Test
    void aYahooDividendYieldsOnTheCloseBeforeItsExDate() {
        Result r = DividendHistory.of(new Input("IDR", "IDR",
                closes("2026-03-23", "9000", "2026-03-24", "10000", "2026-03-25", "9800", "2026-11-06", "8000"),
                List.of(), List.of(new YahooDividend(d("2026-03-25"), n("250"))), List.of(), List.of(), List.of(),
                n("200"), d("2026-11-06")));

        DividendRow row = r.rows().get(0);
        assertThat(row.source()).isEqualTo("YAHOO");
        assertThat(row.priceDate()).isEqualTo(d("2026-03-24"));
        assertThat(row.price()).isEqualByComparingTo("10000");
        assertThat(row.dividendYield()).isEqualByComparingTo("0.025");
        // trailing 12 months on the last close
        assertThat(r.summary().trailingPerShare()).isEqualByComparingTo("250");
        assertThat(r.summary().trailingCount()).isEqualTo(1);
        assertThat(r.summary().trailingYield()).isEqualByComparingTo("0.03125");
        // per share: 250 of the last 12 months / EPS TTM 200
        assertThat(r.summary().trailingPayout()).isEqualByComparingTo("1.25");
    }

    @Test
    void anIdxDividendBeforeASplitIsSetAgainstTheCloseOfItsOwnShareBasis() {
        // a 1:5 split on 2026-07-16: the stored (split-adjusted) close of 2026-03-24 is 2,000 = 10,000 before the split
        Result r = DividendHistory.of(new Input("IDR", "IDR",
                closes("2026-03-24", "2000", "2026-11-06", "1900"),
                List.of(new Split(d("2026-07-16"), n("5"))),
                List.of(new YahooDividend(d("2026-03-26"), n("50"))),
                List.of(new Dividend(2025, "final", n("250"), "IDR", d("2026-03-24"), d("2026-03-25"), null,
                        d("2026-04-10"), null, null)),
                List.of(), List.of()));

        assertThat(r.rows()).hasSize(1);
        DividendRow row = r.rows().get(0);
        // Yahoo's 50 (today's basis) is the same dividend: ex dates one day apart
        assertThat(row.source()).isEqualTo("IDX_YAHOO");
        assertThat(row.yahooPerShare()).isEqualByComparingTo("50");
        assertThat(row.splitFactor()).isEqualByComparingTo("5");
        assertThat(row.price()).isEqualByComparingTo("10000");
        assertThat(row.dividendYield()).isEqualByComparingTo("0.025");
        // the trailing sum on today's basis: 250 / 5 = 50 against 1,900
        assertThat(r.summary().trailingPerShare()).isEqualByComparingTo("50");
        assertThat(r.summary().trailingYield()).isEqualByComparingTo(n("50").divide(n("1900"), 6,
                java.math.RoundingMode.HALF_UP));
    }

    @Test
    void aUsdReportersClosesAreConvertedBackToRupiahAtTheRateBeforeTheDay() {
        // AADI: prices stored in USD; Yahoo's dividend in IDR
        Result r = DividendHistory.of(new Input("USD", "IDR",
                closes("2025-11-14", "0.52388935", "2026-11-06", "0.6"),
                List.of(), List.of(new YahooDividend(d("2025-11-18"), n("538.08"))), List.of(), List.of(),
                List.of(new Rate("USD", "IDR", d("2025-11-13"), n("16600")),
                        new Rate("USD", "IDR", d("2026-11-05"), n("16900")))));

        DividendRow row = r.rows().get(0);
        // 0.52388935 x 16,600 = 8,696.56
        assertThat(row.price()).isEqualByComparingTo("8696.5632");
        assertThat(row.dividendYield()).isEqualByComparingTo("0.061873");
        assertThat(r.summary().price()).isEqualByComparingTo("10140");
    }

    @Test
    void withoutARateTheYieldIsUnknownNotGuessed() {
        Result r = DividendHistory.of(new Input("USD", "IDR", closes("2025-11-14", "0.5"), List.of(),
                List.of(new YahooDividend(d("2025-11-18"), n("538"))), List.of(), List.of(), List.of()));
        assertThat(r.rows().get(0).price()).isNull();
        assertThat(r.rows().get(0).dividendYield()).isNull();
    }

    @Test
    void payoutRatiosOfTheFiscalYears() {
        Result r = DividendHistory.of(new Input("IDR", "IDR", closes("2026-11-06", "1000"), List.of(), List.of(),
                List.of(new Dividend(2025, "interim", n("20"), "IDR", d("2025-11-20"), d("2025-11-21"), null, null,
                                n("200000000"), "IDR"),
                        new Dividend(2025, "final", n("30"), "IDR", d("2026-05-20"), d("2026-05-21"), null, null,
                                n("300000000"), "IDR"),
                        new Dividend(2024, "final", n("40"), "IDR", d("2025-05-20"), d("2025-05-21"), null, null, null,
                                null)),
                List.of(new FiscalYear(2025, d("2025-12-31"), n("1000000000"), n("100"), n("-450000000")),
                        new FiscalYear(2024, d("2024-12-31"), n("800000000"), n("80"), n("-320000000")),
                        new FiscalYear(2023, d("2023-12-31"), n("-50000000"), n("-5"), n("-100000000"))),
                List.of()));

        DividendYear fy2025 = r.years().get(0);
        assertThat(fy2025.fiscalYear()).isEqualTo(2025);
        assertThat(fy2025.dividendsPaid()).isEqualByComparingTo("450000000");
        assertThat(fy2025.cashPayout()).isEqualByComparingTo("0.45");
        // declared from FY2025's profit: 20 + 30 per share, 500 million in all = 50%
        assertThat(fy2025.declaredPerShare()).isEqualByComparingTo("50");
        assertThat(fy2025.declaredTotal()).isEqualByComparingTo("500000000");
        assertThat(fy2025.declaredPayout()).isEqualByComparingTo("0.5");
        assertThat(fy2025.declaredCount()).isEqualTo(2);
        // without totals: per share / EPS = 40 / 80
        assertThat(r.years().get(1).declaredPayout()).isEqualByComparingTo("0.5");
        // a loss year has no payout ratio
        assertThat(r.years().get(2).cashPayout()).isNull();
        assertThat(r.summary().latestPayout()).isEqualByComparingTo("0.45");
        assertThat(r.summary().latestPayoutYear()).isEqualTo(2025);
        assertThat(r.summary().averagePayout()).isEqualByComparingTo("0.425");
        assertThat(r.summary().averagePayoutYears()).isEqualTo(2);
    }

    @Test
    void aSplitFactorMultipliesEverySplitAfterTheDay() {
        Result r = DividendHistory.of(new Input("IDR", "IDR", closes("2020-01-02", "100"),
                List.of(new Split(d("2021-01-01"), n("2")), new Split(d("2022-01-01"), n("0.1")),
                        new Split(d("2019-01-01"), n("5"))),
                List.of(new YahooDividend(d("2020-01-03"), n("1"))), List.of(), List.of(), List.of()));
        // 2 x 0.1 after 2020-01-02 (the 2019 split is before)
        assertThat(r.rows().get(0).splitFactor()).isEqualByComparingTo("0.2");
        assertThat(r.rows().get(0).cashPerShare()).isEqualByComparingTo("0.2");
        assertThat(r.rows().get(0).price()).isEqualByComparingTo("20");
        assertThat(r.rows().get(0).dividendYield()).isEqualByComparingTo("0.01");
    }
}
