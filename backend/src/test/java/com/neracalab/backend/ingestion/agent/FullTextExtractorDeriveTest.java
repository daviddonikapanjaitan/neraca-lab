package com.neracalab.backend.ingestion.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * EBIT and EBITDA of the full-text extraction: with the depreciation and amortisation the profit or loss states (a
 * profit or loss by nature, every column alike), else depreciation + amortization, else none. NSSS H1 2025 (the
 * comparative of its H1 2026 filing): the model read the stated 80,031 million as depreciation while the current
 * column took the roll-forward's 32,899 for a stated 80,672, so its trailing EBITDA mixed the two.
 */
class FullTextExtractorDeriveTest {

    private static Map<String, BigDecimal> income(String pretax, String financeCost, String financeIncome,
                                                  String depreciation) {
        Map<String, BigDecimal> v = new HashMap<>();
        v.put("pretax_income", new BigDecimal(pretax));
        v.put("interest_expense", new BigDecimal(financeCost));
        v.put("interest_income", new BigDecimal(financeIncome));
        v.put("depreciation", depreciation == null ? null : new BigDecimal(depreciation));
        return v;
    }

    @Test
    void theStatedDepreciationAndAmortisationWins() {
        Map<String, BigDecimal> v = income("400", "70", "10", "32");
        Map<String, String> how = new HashMap<>();
        FullTextExtractor.derive(v, how, new BigDecimal("80"));
        assertThat(v.get("ebit")).isEqualByComparingTo("460");
        assertThat(v.get("ebitda")).isEqualByComparingTo("540");
        assertThat(how.get("ebitda")).contains("as the profit or loss states it");
    }

    @Test
    void withoutItDepreciationAndAmortization() {
        Map<String, BigDecimal> v = income("400", "70", "10", "32");
        v.put("amortization", new BigDecimal("5"));
        Map<String, String> how = new HashMap<>();
        FullTextExtractor.derive(v, how, null);
        assertThat(v.get("ebitda")).isEqualByComparingTo("497");
        assertThat(how.get("ebitda")).isEqualTo("ebit + depreciation + amortization");

        Map<String, BigDecimal> none = income("400", "70", "10", null);
        FullTextExtractor.derive(none, new HashMap<>(), null);
        assertThat(none.get("ebitda")).isNull();
    }
}
