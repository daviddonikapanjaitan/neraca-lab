package com.neracalab.backend.ingestion.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.ingestion.mapping.MappedStatement.UnclassifiedLine;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbook;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * Findings of the audit of the MAPA, ULTJ and BBCA filings against the database:
 * <ul>
 *   <li>ULTJ held treasury stock in 2022: the weighted share count of that year is not the issued shares;</li>
 *   <li>MAPA split its shares 1:10 in 2023: its FY2022 filing is on the basis before, its FY2023 filing restates
 *       FY2022 on the basis after (what {@code ShareSplitTest} then reconciles in the database);</li>
 *   <li>BBCA files 0 on the insurance lines it does not use: a line of amount zero needs no classification.</li>
 * </ul>
 */
class FilingMapperAuditedTickersTest {

    @Test
    void ultjGetsNoWeightedSharesForAYearWithTreasuryStock() throws Exception {
        FilingMapper mapper = mapper("ULTJ", "2023-Tahunan");
        ShareCapital shares = mapper.shareCapital();

        // FY2022: 11,553,520,000 issued shares, part of them bought back (count not in the filing); EPS 92 is per
        // outstanding share (profit 960,786 million / 92 = 10.44 billion)
        var prior = mapper.incomeStatement(StatementColumn.PRIOR_PERIOD, Map.of(), shares).orElseThrow();
        assertThat(prior.values().get("basic_eps")).isEqualByComparingTo("92");
        assertThat(prior.values().get("basic_shares")).isNull();
        assertThat(prior.checks()).noneMatch(c -> c.rule().equals("eps") && c.severity() != Check.Severity.OK);
        assertThat(shares.weightedPrior()).isNull();
        assertThat(shares.snapshots()).filteredOn(s -> s.date().equals(LocalDate.parse("2022-12-31")))
                .isNotEmpty()
                .allSatisfy(s -> {
                    assertThat(s.basicShares()).isNull();
                    assertThat(s.sharesOutstanding()).isNull();
                });
        // FY2023, after the treasury shares were cancelled: 519,909 million / Rp 50
        assertThat(shares.snapshots()).filteredOn(s -> s.date().equals(LocalDate.parse("2023-12-31")))
                .isNotEmpty()
                .allSatisfy(s -> assertThat(s.sharesOutstanding()).isEqualByComparingTo("10398180000"));
    }

    @Test
    void ultjKeepsTheWeightedSharesOfYearsWithoutTreasuryStock() throws Exception {
        FilingMapper mapper = mapper("ULTJ", "2025-Tahunan");
        ShareCapital shares = mapper.shareCapital();

        assertThat(mapper.incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(), shares).orElseThrow().values().get("basic_shares"))
                .isEqualByComparingTo("10398180000");
        assertThat(mapper.incomeStatement(StatementColumn.PRIOR_PERIOD, Map.of(), shares).orElseThrow().values().get("basic_shares"))
                .isEqualByComparingTo("10398180000");
    }

    @Test
    void mapaFilingsBeforeAndAfterItsSplitStateTheSameYearOnTwoBases() throws Exception {
        FilingMapper before = mapper("MAPA", "2022-Tahunan");
        FilingMapper after = mapper("MAPA", "2023-Tahunan");

        var own = before.incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(), before.shareCapital()).orElseThrow();
        var restated = after.incomeStatement(StatementColumn.PRIOR_PERIOD, Map.of(), after.shareCapital()).orElseThrow();

        assertThat(own.period().key()).isEqualTo(restated.period().key()).isEqualTo("2022 FY");
        assertThat(own.values().get("basic_eps")).isEqualByComparingTo("412");
        assertThat(own.values().get("basic_shares")).isEqualByComparingTo("2850400000");
        assertThat(restated.values().get("basic_eps")).isEqualByComparingTo("41");
        assertThat(restated.values().get("basic_shares")).isEqualByComparingTo("28504000000");
    }

    @Test
    void bbcaLinesFiledAsZeroNeedNoClassification() throws Exception {
        FilingMapper mapper = mapper("BBCA", "2023-Tahunan");
        ShareCapital shares = mapper.shareCapital();

        for (StatementColumn column : mapper.columns()) {
            var income = mapper.incomeStatement(column, Map.of(), shares).orElseThrow();
            assertThat(income.unclassified()).as(column + " unclassified").isEmpty();
            assertThat(income.checks()).as(column + " checks").noneMatch(Check::isError);
        }
    }

    @Test
    void bbcaInsuranceLineWithAnAmountIsStillLeftToTheAgent() throws Exception {
        FilingMapper mapper = mapper("BBCA", "2025-Tahunan");

        var income = mapper.incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(), mapper.shareCapital()).orElseThrow();

        assertThat(income.unclassified()).extracting(UnclassifiedLine::label).containsExactly("Other insurance expenses");
        assertThat(income.unclassified()).extracting(UnclassifiedLine::value)
                .allSatisfy(v -> assertThat(v).isEqualByComparingTo(new BigDecimal("1858302000000")));

        // classified as an operating expense, the filing's profit from operation reconciles
        var classified = mapper.incomeStatement(StatementColumn.CURRENT_PERIOD,
                Map.of("Other insurance expenses", IncomeLineCategory.OTHER_OPERATING_EXPENSE), mapper.shareCapital()).orElseThrow();
        assertThat(classified.unclassified()).isEmpty();
        assertThat(classified.checks()).noneMatch(Check::isError);
        assertThat(classified.values().get("operating_income")).isEqualByComparingTo("71260876000000");
    }

    private static FilingMapper mapper(String ticker, String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", ticker, "xlsx", "FinancialStatement-" + filing + "-" + ticker + ".xlsx");
        assumeTrue(Files.exists(file), ticker + " source data not available: " + file);
        IdxWorkbook workbook;
        try (InputStream in = Files.newInputStream(file)) {
            workbook = new IdxWorkbookReader().read(in, file.getFileName().toString());
        }
        return new FilingMapper(workbook);
    }
}
