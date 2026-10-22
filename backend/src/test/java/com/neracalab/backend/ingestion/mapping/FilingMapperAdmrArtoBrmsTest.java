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
 * The uploads of 2026-10-18 (errors 620, 622) and BRMS's empty valuation:
 * <ul>
 *   <li>ADMR H1 2026, H1 2025 column (USD thousand): exploration and evaluation assets 124.222 and intangible assets
 *       90.109, filed with decimals, are not in the investing total -322,409;</li>
 *   <li>ARTO FY2022 (bank): 'Total temporary syirkah funds' empty, its lines filed (526,059 million);</li>
 *   <li>BRMS (full USD amounts) files its EPS per 1,000 shares: 0.35 for USD 0.00035.</li>
 * </ul>
 */
class FilingMapperAdmrArtoBrmsTest {

    @Test
    void admrLinesWithDecimalsOutsideTheInvestingTotal() throws Exception {
        FilingMapper mapper = mapper("ADMR", "2026-II");
        MappedStatement prior = mapper.cashFlow(StatementColumn.PRIOR_PERIOD).orElseThrow();
        assertThat(prior.hasErrors()).as(prior.checks().toString()).isFalse();
        assertThat(prior.checks()).anyMatch(c -> c.rule().equals("investing_section") && c.message().contains("decimals")
                && c.message().contains("exploration and evaluation"));
        assertThat(prior.values().get("investing_cash_flow")).isEqualByComparingTo("-322409000");
        assertThat(mapper.cashFlow(StatementColumn.CURRENT_PERIOD).orElseThrow().hasErrors()).isFalse();
    }

    @Test
    void artoSyirkahFundsFromTheirLines() throws Exception {
        FilingMapper mapper = mapper("ARTO", "2022-Tahunan");
        for (StatementColumn column : List.of(StatementColumn.CURRENT_PERIOD, StatementColumn.PRIOR_PERIOD)) {
            MappedStatement balance = mapper.balanceSheet(column, mapper.shareCapital()).orElseThrow();
            assertThat(balance.hasErrors()).as(column + " " + balance.checks()).isFalse();
            assertThat(balance.checks()).anyMatch(c -> c.rule().equals("syirkah"));
        }
        assertThat(mapper.balanceSheet(StatementColumn.CURRENT_PERIOD, mapper.shareCapital()).orElseThrow()
                .values().get("temporary_syirkah_funds")).isEqualByComparingTo("526059000000");
        assertThat(mapper.balanceSheet(StatementColumn.PRIOR_PERIOD, mapper.shareCapital()).orElseThrow()
                .values().get("temporary_syirkah_funds")).isEqualByComparingTo("110361000000");
    }

    @Test
    void brmsEpsFiledPerThousandShares() throws Exception {
        FilingMapper mapper = mapper("BRMS", "2025-Tahunan");
        assertThat(mapper.shareCapital().resolved()).isFalse();
        // Yahoo Finance, 2026-10-18
        List<WebShareCount> yahoo = List.of(count("2023-12-31", 141_784_040_338L), count("2024-12-31", 141_784_041_138L),
                count("2025-12-31", 141_784_040_338L));
        ShareCapital shares = mapper.withWebShareCounts(mapper.shareCapital(), yahoo, "Yahoo Finance");
        assertThat(shares.resolved()).isTrue();
        assertThat(shares.checks()).anyMatch(c -> c.rule().equals("eps_unit"));
        assertThat(mapper.epsScale()).isEqualByComparingTo("0.001");
        MappedStatement income = mapper.incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(), shares).orElseThrow();
        assertThat(income.values().get("basic_eps")).isEqualByComparingTo("0.00035");

        // counts of another company fit neither way: nothing is changed
        FilingMapper other = mapper("BRMS", "2025-Tahunan");
        ShareCapital wrong = other.withWebShareCounts(other.shareCapital(), List.of(count("2025-12-31", 5_000_000_000L)),
                "Yahoo Finance");
        assertThat(wrong.resolved()).isFalse();
        assertThat(other.epsScale()).isEqualByComparingTo("1");
    }

    private static WebShareCount count(String date, long outstanding) {
        return new WebShareCount(LocalDate.parse(date), BigDecimal.valueOf(outstanding), BigDecimal.valueOf(outstanding), null);
    }

    private static FilingMapper mapper(String ticker, String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", ticker, "xlsx", "FinancialStatement-" + filing + "-" + ticker + ".xlsx");
        assumeTrue(Files.exists(file), ticker + " source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString()));
        }
    }
}
