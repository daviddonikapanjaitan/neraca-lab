package com.neracalab.backend.ingestion.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * Interim workbooks of 2026 that file the comparative EPS in their rounding unit beside a current EPS in rupiah (BJTM
 * 9M 2026, millions: 72.995 and 0.00007062 for Rp 70.62; AGRO H1 2026, thousands: 0.49 and 0.00133 for Rp 1.33), one
 * whose comparative EPS is off by yet another power of ten (BJBR 9M 2026: 0.00000075 in millions for Rp 75), and the
 * large EPS of companies before their IPO that must stay as filed (MORA FY2019: 1,005,543 on 588,235 shares).
 */
class FilingMapperColumnEpsUnitTest {

    @Test
    void aComparativeEpsFiledInTheRoundingUnitIsReadInIt() throws Exception {
        FilingMapper bjtm = mapper("BJTM", "2026-III");
        assertThat(eps(bjtm, StatementColumn.PRIOR_PERIOD)).isEqualByComparingTo("70.62");
        assertThat(eps(bjtm, StatementColumn.CURRENT_PERIOD)).isEqualByComparingTo("72.99531417568596");
        assertThat(bjtm.warnings()).anyMatch(w -> w.contains("2025 9M basic EPS 0.00007062 is filed in its rounding unit"));

        FilingMapper agro = mapper("AGRO", "2026-II");
        assertThat(eps(agro, StatementColumn.PRIOR_PERIOD)).isEqualByComparingTo("1.33");
        assertThat(eps(agro, StatementColumn.CURRENT_PERIOD)).isEqualByComparingTo("0.49");
    }

    @Test
    void anEpsGivingMoreSharesThanAnyListedCompanyHasIsNotStored() throws Exception {
        FilingMapper bjbr = mapper("BJBR", "2026-III");
        MappedStatement prior = bjbr.incomeStatement(StatementColumn.PRIOR_PERIOD, Map.of(), null).orElseThrow();
        assertThat(prior.values().get("basic_eps")).isNull();
        assertThat(prior.rejected()).contains("basic_eps");
        assertThat(prior.checks()).anyMatch(c -> c.message().contains("more than any listed company has"));
        assertThat(eps(bjbr, StatementColumn.CURRENT_PERIOD)).isEqualByComparingTo("103.17");
    }

    @Test
    void aLargeEpsBeforeAnIpoIsKept() throws Exception {
        // MORA FY2019: 591,495,494,441 / 1,005,543 = 588,235 shares before its IPO
        assertThat(eps(mapper("MORA", "2019-Tahunan"), StatementColumn.CURRENT_PERIOD)).isEqualByComparingTo("1005543");
        assertThat(FilingMapper.impossibleEps(new BigDecimal("591495494441"), new BigDecimal("1005543"))).isFalse();
    }

    @Test
    void aBalanceSheetWithOnlyCurrentAssetsHasNoNonCurrentAssets() throws Exception {
        // AIMS FY2019: total current assets = total assets = 17,531,591,615, 'Total non-current assets' left empty
        FilingMapper aims = mapper("AIMS", "2019-Tahunan");
        for (StatementColumn column : new StatementColumn[] {StatementColumn.CURRENT_PERIOD, StatementColumn.PRIOR_PERIOD}) {
            MappedStatement balance = aims.balanceSheet(column, aims.shareCapital()).orElseThrow();
            assertThat(balance.checks()).noneMatch(c -> c.isError());
            assertThat(balance.checks()).anyMatch(c -> "non_current_assets".equals(c.rule()));
        }
        assertThat(aims.balanceSheet(StatementColumn.CURRENT_PERIOD, aims.shareCapital()).orElseThrow().values()
                .get("total_assets")).isEqualByComparingTo("17531591615");
    }

    private static BigDecimal eps(FilingMapper mapper, StatementColumn column) {
        return mapper.incomeStatement(column, Map.of(), null).orElseThrow().values().get("basic_eps");
    }

    private static FilingMapper mapper(String ticker, String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", ticker, "xlsx", "FinancialStatement-" + filing + "-" + ticker + ".xlsx");
        assumeTrue(Files.exists(file), ticker + " source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString()));
        }
    }
}
