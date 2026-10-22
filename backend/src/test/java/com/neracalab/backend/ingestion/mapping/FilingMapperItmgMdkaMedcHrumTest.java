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

import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * The uploads of 2026-10-21 (errors 707 - 713) and the review of ITMG, MDKA, MEDC, PGAS, PGEO and ISAT:
 * <ul>
 *   <li>ITMG FY2023: full amounts under "In Thousand" that the EPS does not tell apart (1.1 billion or 1.1 trillion
 *       shares);</li>
 *   <li>ITMG and MDKA H1 2025: 'Other gains (losses)' filed again inside a residual line of its direction;</li>
 *   <li>ITMG H1 2025 and MDKA H1 2026: one column's EPS filed a power of ten off, told by the published counts;</li>
 *   <li>MEDC and HRUM: a row of the EPS from discontinued operations that is no such EPS.</li>
 * </ul>
 */
class FilingMapperItmgMdkaMedcHrumTest {

    /** Yahoo Finance's shares outstanding of ITMG, as published on 2026-10-21. */
    private static final List<WebShareCount> ITMG = List.of(
            count("2022-12-31", "1129925000"), count("2023-12-31", "1129925000"), count("2024-12-31", "1129925000"),
            count("2025-06-30", "1129925000"), count("2025-09-30", "1129925000"), count("2025-12-31", "1123887100"),
            count("2026-03-31", "1114899100"), count("2026-06-30", "1102700300"));

    /** Yahoo Finance's shares outstanding of MDKA, as published on 2026-10-21 (no 2026-06-30 count yet). */
    private static final List<WebShareCount> MDKA = List.of(
            count("2022-12-31", "24042553971"), count("2023-12-31", "24044656071"), count("2024-12-31", "24411754471"),
            count("2025-03-31", "24411754471"), count("2025-06-30", "24415100971"), count("2025-09-30", "24416007371"),
            count("2025-12-31", "24416902071"), count("2026-03-31", "24416902071"));

    @Test
    void itmgFy2023FullAmountsTheEpsDoesNotDecide() throws Exception {
        FilingMapper mapper = mapper("ITMG", "2023-Tahunan");
        assertThat(mapper.unit()).isEqualByComparingTo("1");
        assertThat(mapper.precision()).isEqualByComparingTo("1000");
        assertThat(mapper.warnings()).anyMatch(w -> w.startsWith("The workbook declares 'Ribuan / In Thousand' but its amounts "
                + "are full amounts") && w.contains("in the declared unit"));

        MappedStatement balance = mapper.balanceSheet(StatementColumn.CURRENT_PERIOD, mapper.shareCapital()).orElseThrow();
        assertThat(balance.values().get("total_assets")).isEqualByComparingTo("2187847000");
        MappedStatement income = mapper.incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(), mapper.shareCapital()).orElseThrow();
        assertThat(income.values().get("gross_profit")).isEqualByComparingTo("742542000");
        assertThat(income.values().get("pretax_income")).isEqualByComparingTo("644112000");
        assertThat(income.values().get("net_income_to_parent")).isEqualByComparingTo("500331000");
        assertThat(income.values().get("basic_eps")).isEqualByComparingTo("0.44");

        // the FY2024 workbook is in thousands, as it declares
        FilingMapper fy2024 = mapper("ITMG", "2024-Tahunan");
        assertThat(fy2024.unit()).isEqualByComparingTo("1000");
        assertThat(fy2024.balanceSheet(StatementColumn.PRIOR_PERIOD, fy2024.shareCapital()).orElseThrow().values().get("total_assets"))
                .isEqualByComparingTo("2187847000");
    }

    @Test
    void itmgH1PriorEpsInTheRoundingUnitAndOtherGainsWithinOtherExpenses() throws Exception {
        FilingMapper mapper = mapper("ITMG", "2026-II");
        ShareCapital shares = mapper.withWebShareCounts(mapper.shareCapital(), ITMG, "Yahoo Finance");
        assertThat(shares.resolved()).isTrue();
        assertThat(mapper.epsScale(StatementColumn.PRIOR_PERIOD)).isEqualByComparingTo("1000");
        assertThat(mapper.epsScale(StatementColumn.CURRENT_PERIOD)).isEqualByComparingTo("1");
        assertThat(shares.checks()).anyMatch(c -> c.rule().equals("eps_scale")
                && c.message().startsWith("The workbook's 2025 H1 basic EPS 0.00008 is filed 1000 times too small"));
        assertThat(outstanding(shares, "2025-06-30")).isEqualByComparingTo("1129925000");
        assertThat(outstanding(shares, "2026-06-30")).isEqualByComparingTo("1102700300");

        MappedStatement prior = mapper.incomeStatement(StatementColumn.PRIOR_PERIOD, Map.of(), shares).orElseThrow();
        assertThat(prior.hasErrors()).as(prior.checks().toString()).isFalse();
        assertThat(prior.checks()).anyMatch(c -> c.rule().equals("double_reported")
                && c.message().startsWith("'Other gains (losses)' and 'Other expenses' report one amount twice"));
        assertThat(prior.values().get("basic_eps")).isEqualByComparingTo("0.08");
        assertThat(prior.values().get("pretax_income")).isEqualByComparingTo("137383000");
        // gross profit 224,712 - selling 81,162 - G&A 18,652 - other expenses 1,644 (thousand)
        assertThat(prior.values().get("operating_income")).isEqualByComparingTo("123254000");

        MappedStatement current = mapper.incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(), shares).orElseThrow();
        assertThat(current.values().get("basic_eps")).isEqualByComparingTo("0.1");
    }

    @Test
    void mdkaCurrentEpsADigitOffAndOtherGainsWithinOtherIncome() throws Exception {
        FilingMapper mapper = mapper("MDKA", "2026-II");
        ShareCapital shares = mapper.withWebShareCounts(mapper.shareCapital(), MDKA, "Yahoo Finance");
        assertThat(shares.resolved()).isTrue();
        assertThat(mapper.epsScale(StatementColumn.CURRENT_PERIOD)).isEqualByComparingTo("10");
        assertThat(mapper.epsScale(StatementColumn.PRIOR_PERIOD)).isEqualByComparingTo("1");
        assertThat(outstanding(shares, "2025-12-31")).isEqualByComparingTo("24416902071");
        assertThat(outstanding(shares, "2025-06-30")).isEqualByComparingTo("24415100971");

        MappedStatement current = mapper.incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(), shares).orElseThrow();
        assertThat(current.values().get("basic_eps")).isEqualByComparingTo("0.0042");

        MappedStatement prior = mapper.incomeStatement(StatementColumn.PRIOR_PERIOD, Map.of(), shares).orElseThrow();
        assertThat(prior.hasErrors()).as(prior.checks().toString()).isFalse();
        assertThat(prior.checks()).anyMatch(c -> c.rule().equals("double_reported")
                && c.message().startsWith("'Other gains (losses)' and 'Other income' report one amount twice"));
        assertThat(prior.values().get("basic_eps")).isEqualByComparingTo("-0.0006");
        assertThat(prior.values().get("pretax_income")).isEqualByComparingTo("24671192");
        // gross profit 105,955,405 - selling 2,273,930 - G&A 24,796,421 + other income 5,849,538 - other expenses 1,552,475
        assertThat(prior.values().get("operating_income")).isEqualByComparingTo("83182117");
    }

    /** Without published counts at the column's dates nothing tells which column is off: the EPS is read as filed. */
    @Test
    void noPowerOfTenWithoutPublishedEvidence() throws Exception {
        FilingMapper mapper = mapper("MDKA", "2026-II");
        List<WebShareCount> priorOnly = MDKA.stream().filter(c -> c.date().isBefore(LocalDate.parse("2025-07-01"))).toList();
        mapper.withWebShareCounts(mapper.shareCapital(), priorOnly, "Yahoo Finance");
        assertThat(mapper.epsScale(StatementColumn.CURRENT_PERIOD)).isEqualByComparingTo("1");

        // counts of another basis (five times as many shares) fit neither column: no column is corrected
        FilingMapper other = mapper("MDKA", "2026-II");
        List<WebShareCount> wrong = MDKA.stream().map(c -> new WebShareCount(c.date(), c.outstanding().multiply(BigDecimal.valueOf(5)),
                null, null)).toList();
        other.withWebShareCounts(other.shareCapital(), wrong, "Yahoo Finance");
        assertThat(other.epsScale(StatementColumn.CURRENT_PERIOD)).isEqualByComparingTo("1");
        assertThat(other.epsScale(StatementColumn.PRIOR_PERIOD)).isEqualByComparingTo("1");
    }

    @Test
    void medcTotalEpsFiledAgainOnTheDiscontinuedRow() throws Exception {
        FilingMapper fy2022 = mapper("MEDC", "2022-Tahunan");
        MappedStatement current = fy2022.incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(), fy2022.shareCapital()).orElseThrow();
        assertThat(current.values().get("basic_eps")).isEqualByComparingTo("0.021503");
        assertThat(current.values().get("diluted_eps")).isEqualByComparingTo("0.021503");
        assertThat(current.checks()).anyMatch(c -> c.rule().equals("eps_discontinued")
                && c.message().startsWith("'Basic earnings (loss) per share from discontinued operations' 0.021503 is not an EPS"));
        MappedStatement prior = fy2022.incomeStatement(StatementColumn.PRIOR_PERIOD, Map.of(), fy2022.shareCapital()).orElseThrow();
        assertThat(prior.values().get("basic_eps")).isEqualByComparingTo("0.001857");

        FilingMapper fy2025 = mapper("MEDC", "2025-Tahunan");
        assertThat(fy2025.incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(), fy2025.shareCapital()).orElseThrow()
                .values().get("basic_eps")).isEqualByComparingTo("0.00408");

        // FY2023 files a discontinued EPS its discontinued loss gives: kept (0.0149 - 0.00169)
        FilingMapper fy2023 = mapper("MEDC", "2023-Tahunan");
        MappedStatement fy2023Current = fy2023.incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(), fy2023.shareCapital()).orElseThrow();
        assertThat(fy2023Current.values().get("basic_eps")).isEqualByComparingTo("0.01321");
        assertThat(fy2023Current.checks()).noneMatch(c -> c.rule().equals("eps_discontinued"));
    }

    @Test
    void hrumContinuingEpsFiledAgainWithoutDiscontinuedOperations() throws Exception {
        for (String filing : List.of("2022-Tahunan", "2023-Tahunan", "2024-Tahunan", "2025-Tahunan")) {
            FilingMapper mapper = mapper("HRUM", filing);
            for (StatementColumn column : List.of(StatementColumn.CURRENT_PERIOD, StatementColumn.PRIOR_PERIOD)) {
                MappedStatement income = mapper.incomeStatement(column, Map.of(), mapper.shareCapital()).orElseThrow();
                BigDecimal parent = income.values().get("net_income_to_parent");
                BigDecimal eps = income.values().get("basic_eps");
                // 13.1 - 13.5 billion shares, not half of them (FY2022 in the FY2023 filing: EPS 0.02 for 0.0227, 15.1 billion)
                assertThat(parent.divide(eps, java.math.MathContext.DECIMAL64)).as(filing + " " + column)
                        .isBetween(new BigDecimal("12.5e9"), new BigDecimal("15.5e9"));
            }
        }
        FilingMapper fy2025 = mapper("HRUM", "2025-Tahunan");
        assertThat(fy2025.incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(), fy2025.shareCapital()).orElseThrow()
                .values().get("basic_eps")).isEqualByComparingTo("0.00284");
    }

    /** Discontinued EPS that the discontinued profit gives are kept, also when filed coarsely (MPMX: 1 beside 105). */
    @Test
    void discontinuedEpsThatFitTheirProfitAreKept() throws Exception {
        assertEps("UNVR", "2025-Tahunan", StatementColumn.CURRENT_PERIOD, "201");
        assertEps("UNVR", "2026-II", StatementColumn.PRIOR_PERIOD, "57");
        assertEps("EMTK", "2023-Tahunan", StatementColumn.CURRENT_PERIOD, "-2.31");
        assertEps("MPMX", "2025-Tahunan", StatementColumn.CURRENT_PERIOD, "106");
        assertEps("KRAS", "2023-Tahunan", StatementColumn.PRIOR_PERIOD, "0.001");
    }

    private static void assertEps(String ticker, String filing, StatementColumn column, String eps) throws Exception {
        FilingMapper mapper = mapper(ticker, filing);
        MappedStatement income = mapper.incomeStatement(column, Map.of(), mapper.shareCapital()).orElseThrow();
        assertThat(income.checks()).as(ticker + " " + filing).noneMatch(c -> c.rule().equals("eps_discontinued"));
        assertThat(income.values().get("basic_eps")).as(ticker + " " + filing).isEqualByComparingTo(eps);
    }

    private static BigDecimal outstanding(ShareCapital shares, String date) {
        return shares.snapshots().stream().filter(s -> s.date().equals(LocalDate.parse(date))).findFirst().orElseThrow()
                .sharesOutstanding();
    }

    private static WebShareCount count(String date, String outstanding) {
        return new WebShareCount(LocalDate.parse(date), new BigDecimal(outstanding), null, null);
    }

    private static FilingMapper mapper(String ticker, String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", ticker, "xlsx", "FinancialStatement-" + filing + "-" + ticker + ".xlsx");
        assumeTrue(Files.exists(file), ticker + " source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString()));
        }
    }
}
