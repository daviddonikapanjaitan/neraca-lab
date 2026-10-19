package com.neracalab.backend.ingestion.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.neracalab.backend.ingestion.agent.IngestionSession;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * Filings that ended in the error log ({@code ingestion_error}) on 2026-10-11 although nothing is wrong with them:
 * a comparative column with a 'Proforma equity' row (IMPC, MSIN), an amount filed on two lines of the same kind
 * (WIIM), a profit or loss by nature whose EPS is filed in the rounding unit (MSIN).
 */
class FilingMapperErrorLogTest {

    /**
     * IMPC FY2024: the FY2023 column is restated for an entity combined under common control and carries its
     * equity as 'Proforma equity' 138,044,698,258 between the parent's equity (2,273,215,122,096) and the
     * non-controlling interests (214,434,701,176); total equity 2,625,694,521,530 is the three together. The
     * column used to fail "parent equity + non-controlling interests = total equity" by exactly that row.
     */
    @Test
    void proformaEquityIsPartOfTotalEquityAndCountedWithTheNonControllingInterests() throws Exception {
        FilingMapper mapper = mapper("IMPC", "2024-Tahunan");

        MappedStatement prior = mapper.balanceSheet(StatementColumn.PRIOR_PERIOD, mapper.shareCapital()).orElseThrow();

        assertThat(prior.checks().stream().filter(Check::isError).toList()).isEmpty();
        Map<String, BigDecimal> v = prior.values();
        assertThat(v.get("shareholders_equity")).isEqualByComparingTo("2273215122096");
        assertThat(v.get("non_controlling_interest")).isEqualByComparingTo("352479399434");      // 214,434,701,176 + 138,044,698,258
        assertThat(v.get("total_equity")).isEqualByComparingTo("2625694521530");
        assertThat(v.get("shareholders_equity").add(v.get("non_controlling_interest"))).isEqualByComparingTo(v.get("total_equity"));
        assertThat(prior.checks()).anyMatch(c -> c.rule().equals("proforma_equity") && !c.isError()
                && c.message().contains("138044698258"));

        // the current column has no such row: nothing changes there
        MappedStatement current = mapper.balanceSheet(StatementColumn.CURRENT_PERIOD, mapper.shareCapital()).orElseThrow();
        assertThat(current.values().get("non_controlling_interest")).isEqualByComparingTo("250190785449");
        assertThat(current.checks()).noneMatch(c -> c.rule().equals("proforma_equity"));
        assertThat(current.checks().stream().filter(Check::isError).toList()).isEmpty();
    }

    /**
     * WIIM H1 2026: the H1 2025 column carries 5,797,003,720 on 'Finance income' and again on 'Interest income';
     * profit before tax 196,550,613,747 only reconciles with the amount counted once.
     */
    @Test
    void anAmountFiledOnTwoLinesOfTheSameKindIsCountedOnce() throws Exception {
        FilingMapper mapper = mapper("WIIM", "2026-II");

        MappedStatement prior = mapper.incomeStatement(StatementColumn.PRIOR_PERIOD, Map.of(), mapper.shareCapital()).orElseThrow();

        assertThat(prior.checks().stream().filter(Check::isError).toList()).isEmpty();
        assertThat(prior.unclassified()).isEmpty();
        assertThat(prior.checks()).anyMatch(c -> c.rule().equals("double_reported")
                && c.message().contains("'Finance income' and 'Interest income' report the same amount"));
        assertThat(prior.values().get("interest_income")).isEqualByComparingTo("5797003720");
        assertThat(prior.values().get("pretax_income")).isEqualByComparingTo("196550613747");

        // the current column files the interest income once (4,382,461,009): read as filed
        MappedStatement current = mapper.incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(), mapper.shareCapital()).orElseThrow();
        assertThat(current.checks().stream().filter(Check::isError).toList()).isEmpty();
        assertThat(current.checks()).noneMatch(c -> c.rule().equals("double_reported"));
        assertThat(current.values().get("interest_income")).isEqualByComparingTo("4382461009");
        assertThat(current.values().get("pretax_income")).isEqualByComparingTo("389582126325");
    }

    /**
     * MSIN files profit or loss by nature and, from FY2023 on, its EPS in the rounding unit (FY2024: 0.00000655
     * for Rp 6.55, amounts in millions). The unit is judged from the profit or loss as filed, so the EPS is
     * stored in rupiah and a published share count can be checked against it - without that the company has no
     * share count, no market cap and no valuation.
     */
    @ParameterizedTest
    @CsvSource({"2022-Tahunan, 1", "2023-Tahunan, 1000000", "2024-Tahunan, 1000000", "2025-Tahunan, 1000000", "2026-II, 1000000"})
    void theEpsUnitOfAProfitOrLossByNatureIsRecognised(String filing, String epsScale) throws Exception {
        FilingMapper mapper = mapper("MSIN", filing);

        assertThat(mapper.incomeByNature()).isTrue();
        assertThat(mapper.unit()).isEqualByComparingTo("1000000");
        assertThat(mapper.epsScale()).isEqualByComparingTo(epsScale);
        // by nature and nothing else wrong: the model decides it by design, a stored upload is not an error
        assertThat(mapper.templateProblems()).singleElement().asString().contains("'by nature' template");
        assertThat(mapper.decidedByModel()).isTrue();
    }

    @Test
    void aPublishedShareCountFitsTheEpsOfAFilingByNatureInItsRoundingUnit() throws Exception {
        FilingMapper mapper = mapper("MSIN", "2024-Tahunan");
        // 397,637 million / 60,676,250,000 shares = 6.5534; the filing's EPS is 0.00000655 x 1,000,000 = 6.55
        List<WebShareCount> counts = List.of(new WebShareCount(LocalDate.parse("2024-12-31"), new BigDecimal("60676250000"), null, null));

        ShareCapital shares = mapper.withWebShareCounts(mapper.shareCapital(), counts, "Yahoo Finance");

        assertThat(shares.resolved()).isTrue();
        assertThat(shares.snapshots()).anyMatch(s -> s.date().equals(LocalDate.parse("2024-12-31"))
                && s.sharesOutstanding() != null && s.sharesOutstanding().compareTo(new BigDecimal("60676250000")) == 0);

        // a count five times too small (the shares before a split) is still refused; the share capital then gives the
        // count (606,762,000 million / par 10, as the EPS confirms)
        ShareCapital wrong = mapper.withWebShareCounts(mapper.shareCapital(),
                List.of(new WebShareCount(LocalDate.parse("2024-12-31"), new BigDecimal("12135250000"), null, null)), "Yahoo Finance");
        assertThat(wrong.webSource()).isNull();
        assertThat(wrong.parValue()).isEqualByComparingTo("10");
        assertThat(wrong.snapshots()).anyMatch(s -> s.date().equals(LocalDate.parse("2024-12-31"))
                && s.sharesOutstanding().compareTo(new BigDecimal("60676200000")) == 0);
        assertThat(wrong.checks()).anyMatch(c -> c.rule().equals("web_shares") && c.message().contains("not used"));
    }

    /** A workbook with a second problem is not "decided by the model by design"; a filing by function never is. */
    @Test
    void onlyAFilingByNatureWithNothingElseWrongIsDecidedByTheModelByDesign() throws Exception {
        assertThat(mapper("NSSS", "2025-Tahunan").decidedByModel()).isTrue();
        assertThat(mapper("WIIM", "2026-II").decidedByModel()).isFalse();
        assertThat(mapper("IMPC", "2024-Tahunan").decidedByModel()).isFalse();
    }

    /** Every filing of the companies uploaded on 2026-10-11 that the rule-based extraction maps: no failed check. */
    @ParameterizedTest
    @CsvSource({"AUTO", "DKFT", "DMAS", "IMPC", "PTBA", "RAJA", "SMSM", "WIIM"})
    void everyFilingMapsWithoutFailedChecks(String ticker) throws Exception {
        for (String filing : List.of("2022-Tahunan", "2023-Tahunan", "2024-Tahunan", "2025-Tahunan", "2026-II")) {
            FilingMapper mapper = mapper(ticker, filing);
            assertThat(mapper.templateProblems()).as(ticker + " " + filing).isEmpty();
            IngestionSession session = new IngestionSession(mapper);
            for (StatementColumn column : mapper.columns()) {
                for (MappedStatement s : session.statements(column)) {
                    assertThat(s.checks().stream().filter(Check::isError).toList())
                            .as(ticker + " " + filing + " " + column + " " + s.table()).isEmpty();
                    assertThat(s.unclassified()).as(ticker + " " + filing + " " + column + " " + s.table()).isEmpty();
                }
            }
        }
    }

    private static FilingMapper mapper(String ticker, String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", ticker, "xlsx", "FinancialStatement-" + filing + "-" + ticker + ".xlsx");
        assumeTrue(Files.exists(file), ticker + " source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString()));
        }
    }
}
