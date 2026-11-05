package com.neracalab.backend.ingestion.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * PNBN FY2022 (millions): its own filing states total assets 212,431,881 = total liabilities 161,715,787 (its syirkah
 * funds inside, no syirkah row) + equity 50,716,094; its FY2023 filing restates FY2022 as liabilities 151,844,644 +
 * temporary syirkah funds 9,871,143 + equity 50,716,094. A comparative fills only empty fields: the syirkah funds
 * filled beside the own liabilities counted 9,871,143 million twice (ingestion error 1767: assets 212,431,881 vs
 * liabilities + equity 222,303,024).
 */
class BalanceSheetPartsTest {

    private static final BigDecimal M = new BigDecimal("1000000");

    @Test
    void aComparativeDoesNotFillAPartThatBreaksABalancedStoredBalanceSheet() {
        Map<String, Object> stored = row("212431881", "161715787", null, "50716094");
        Map<String, BigDecimal> comparative = values("212431881", "151844644", "9871143", "50716094");

        assertThat(IngestionRepository.breakingTheBalance(stored, comparative,
                List.of("temporary_syirkah_funds", "cash_and_equivalents"))).containsExactly("temporary_syirkah_funds");
    }

    @Test
    void aComparativeFillsAPartTheStoredBalanceSheetNeeds() {
        // the stored row lacks its liabilities: the comparative's make it balance
        Map<String, Object> missing = row("212431881", null, null, "50716094");
        assertThat(IngestionRepository.breakingTheBalance(missing,
                values("212431881", "161715787", null, "50716094"), List.of("total_liabilities"))).isEmpty();
        // a syirkah figure that keeps the balance (the stored liabilities exclude it) is filled
        Map<String, Object> without = row("222303024", "161715787", null, "50716094");
        assertThat(IngestionRepository.breakingTheBalance(without,
                values("222303024", "161715787", "9871143", "50716094"), List.of("temporary_syirkah_funds"))).isEmpty();
    }

    @Test
    void theOwnFilingClearsASyirkahFigureItsBalanceHoldsWithout() {
        // the FY2023 comparative was stored first (Hermes uploads newest first), then FY2022's own filing
        Map<String, Object> stored = row("212431881", "151844644", "9871143", "50716094");

        assertThat(IngestionRepository.staleBalanceParts(stored, values("212431881", "161715787", null, "50716094")))
                .containsExactly("temporary_syirkah_funds");
        // its own syirkah figure, or a balance that needs the stored one: nothing cleared
        assertThat(IngestionRepository.staleBalanceParts(stored, values("212431881", "151844644", "9871143", "50716094")))
                .isEmpty();
        assertThat(IngestionRepository.staleBalanceParts(stored, values("212431881", "151844644", null, "50716094")))
                .isEmpty();
    }

    private static Map<String, Object> row(String assets, String liabilities, String syirkah, String equity) {
        Map<String, Object> row = new HashMap<>();
        values(assets, liabilities, syirkah, equity).forEach(row::put);
        return row;
    }

    private static Map<String, BigDecimal> values(String assets, String liabilities, String syirkah, String equity) {
        Map<String, BigDecimal> v = new HashMap<>();
        v.put("total_assets", millions(assets));
        v.put("total_liabilities", millions(liabilities));
        v.put("temporary_syirkah_funds", millions(syirkah));
        v.put("total_equity", millions(equity));
        return v;
    }

    private static BigDecimal millions(String value) {
        return value == null ? null : new BigDecimal(value).multiply(M);
    }
}
