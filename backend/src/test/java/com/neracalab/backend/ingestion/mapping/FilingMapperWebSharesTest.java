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

import com.neracalab.backend.ingestion.mapping.ShareCapital.ShareAt;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * Share counts published on the web (Yahoo Finance, October 2026: yahoo_timeseries_shares_*.json) for filings
 * that give none: only counts that fit the filing's own EPS at the filing's dates are used.
 */
class FilingMapperWebSharesTest {

    /** Yahoo's BNGA counts: {date, outstanding, issued, treasury}. */
    private static final List<WebShareCount> BNGA = List.of(
            count("2022-12-31", 24_933_123_961L, 25_131_606_843L, 198_482_882L),
            count("2023-12-31", 25_024_439_161L, 25_131_606_843L, 107_167_682L),
            count("2024-12-31", 25_137_965_543L, 25_142_205_843L, 4_240_300L),
            count("2025-03-31", 25_137_965_543L, 25_142_205_843L, 4_240_300L),
            count("2025-06-30", 25_140_467_943L, 25_142_205_843L, 1_737_900L),
            count("2025-12-31", 25_140_519_043L, 25_142_205_843L, 1_686_800L),
            count("2026-03-31", 25_142_043_843L, 25_142_205_843L, 162_000L),
            count("2026-06-30", 25_141_869_843L, 25_141_869_843L, 336_000L));   // inconsistent as published

    private static final List<WebShareCount> SMDR = List.of(
            count("2022-12-31", 16_375_600_000L, 16_375_600_000L, null),
            count("2023-12-31", 16_375_600_000L, 16_375_600_000L, null),
            count("2024-12-31", 16_375_600_000L, 16_375_600_000L, null),
            count("2025-12-31", 16_375_600_000L, 16_375_600_000L, null));

    /** FY2025: the three dates of the statements of changes in equity get the audited counts. */
    @Test
    void annualFilingGetsTheCountsThatFitItsEps() throws Exception {
        FilingMapper mapper = mapper("BNGA", "2025-Tahunan");
        ShareCapital shares = mapper.withWebShareCounts(mapper.shareCapital(), BNGA, "Yahoo Finance");

        assertThat(shares.resolved()).isTrue();
        assertThat(shares.webSource()).isEqualTo("Yahoo Finance");
        assertThat(outstanding(shares)).containsExactlyInAnyOrderEntriesOf(Map.of(
                LocalDate.parse("2023-12-31"), new BigDecimal("25024439161"),
                LocalDate.parse("2024-12-31"), new BigDecimal("25137965543"),
                LocalDate.parse("2025-12-31"), new BigDecimal("25140519043")));
        assertThat(shares.snapshots()).allSatisfy(s -> {
            assertThat(s.basicShares()).isNull();     // a period-end count is not a weighted count
            assertThat(s.commonStock()).isNotNull();
        });
        assertThat(shares.snapshots().stream().filter(s -> s.date().equals(LocalDate.parse("2025-12-31")))
                .findFirst().orElseThrow().treasuryShares()).isEqualByComparingTo("1686800");
        assertThat(shares.checks()).noneMatch(Check::isError)
                .noneMatch(c -> c.severity() == Check.Severity.WARNING && c.rule().equals("par_value"));

        Map<String, BigDecimal> balance = mapper.balanceSheet(StatementColumn.CURRENT_PERIOD, shares).orElseThrow().values();
        assertThat(balance.get("shares_outstanding")).isEqualByComparingTo("25140519043");
        assertThat(mapper.balanceSheet(StatementColumn.CURRENT_PERIOD, shares).orElseThrow().derivations()
                .get("shares_outstanding")).contains("Yahoo Finance");
        assertThat(mapper.incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(), shares).orElseThrow()
                .values().get("basic_shares")).isNull();
    }

    /** H1 2026: the inconsistent 2026-06-30 point (outstanding = issued with treasury shares) is not used. */
    @Test
    void inconsistentCountIsRejected() throws Exception {
        FilingMapper mapper = mapper("BNGA", "2026-II");
        ShareCapital shares = mapper.withWebShareCounts(mapper.shareCapital(), BNGA, "Yahoo Finance");

        assertThat(shares.resolved()).isTrue();
        Map<LocalDate, BigDecimal> counts = outstanding(shares);
        assertThat(counts).containsEntry(LocalDate.parse("2025-12-31"), new BigDecimal("25140519043"))
                .containsEntry(LocalDate.parse("2025-06-30"), new BigDecimal("25140467943"))
                .containsEntry(LocalDate.parse("2024-12-31"), new BigDecimal("25137965543"))
                .doesNotContainKey(LocalDate.parse("2026-06-30"));
        assertThat(shares.checks()).anyMatch(c -> c.rule().equals("web_shares") && c.message().contains("2026-06-30")
                && c.message().contains("!= issued"));
        assertThat(mapper.balanceSheet(StatementColumn.CURRENT_PERIOD, shares).orElseThrow().values()
                .get("shares_outstanding")).isNull();
    }

    /** A count that does not reproduce the EPS (here five times too many shares) is never used. */
    @Test
    void countThatContradictsTheEpsIsRejected() throws Exception {
        FilingMapper mapper = mapper("BNGA", "2025-Tahunan");
        List<WebShareCount> wrong = BNGA.stream().map(c -> new WebShareCount(c.date(), c.outstanding().multiply(BigDecimal.valueOf(5)),
                null, null)).toList();
        ShareCapital shares = mapper.withWebShareCounts(mapper.shareCapital(), wrong, "Yahoo Finance");

        assertThat(shares.resolved()).isFalse();
        assertThat(shares.webSource()).isNull();
        assertThat(shares.snapshots()).allSatisfy(s -> assertThat(s.sharesOutstanding()).isNull());
        assertThat(shares.checks()).anyMatch(c -> c.rule().equals("web_shares") && c.message().contains("basic EPS is 273.53"));
    }

    /**
     * SMDR's FY2022 filing predates its 1:5 split of 2023-01-31: Yahoo's split-adjusted 16,375,600,000 gives an EPS
     * of 0.013 against the filed 0.065, so it is rejected; its FY2025 filing (EPS 0.003) accepts it.
     */
    @Test
    void splitNotReflectedInTheFilingIsRejected() throws Exception {
        FilingMapper fy2022 = mapper("SMDR", "2022-Tahunan");
        ShareCapital before = fy2022.withWebShareCounts(fy2022.shareCapital(), SMDR, "Yahoo Finance");
        assertThat(before.resolved()).isFalse();
        assertThat(before.checks()).anyMatch(c -> c.rule().equals("web_shares") && c.message().contains("2022-12-31"));

        FilingMapper fy2025 = mapper("SMDR", "2025-Tahunan");
        ShareCapital after = fy2025.withWebShareCounts(fy2025.shareCapital(), SMDR, "Yahoo Finance");
        assertThat(after.resolved()).isTrue();
        assertThat(outstanding(after)).hasSize(3).allSatisfy((d, n) -> assertThat(n).isEqualByComparingTo("16375600000"));
    }

    /** Counts derived from the filing are never replaced. */
    @Test
    void resolvedFilingIsKept() throws Exception {
        FilingMapper mapper = mapper("HRTA", "2025-Tahunan");
        ShareCapital filing = mapper.shareCapital();
        assumeTrue(filing.resolved(), "HRTA's filing gives share counts");

        assertThat(mapper.withWebShareCounts(filing, List.of(count("2025-12-31", 1L, 1L, 0L)), "Yahoo Finance")).isSameAs(filing);
    }

    private static Map<LocalDate, BigDecimal> outstanding(ShareCapital shares) {
        return shares.snapshots().stream().filter(s -> s.sharesOutstanding() != null)
                .collect(java.util.stream.Collectors.toMap(ShareAt::date, ShareAt::sharesOutstanding));
    }

    private static WebShareCount count(String date, long outstanding, Long issued, Long treasury) {
        return new WebShareCount(LocalDate.parse(date), BigDecimal.valueOf(outstanding),
                issued == null ? null : BigDecimal.valueOf(issued), treasury == null ? null : BigDecimal.valueOf(treasury));
    }

    private static FilingMapper mapper(String ticker, String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", ticker, "xlsx", "FinancialStatement-" + filing + "-" + ticker + ".xlsx");
        assumeTrue(Files.exists(file), ticker + " source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString()));
        }
    }
}
