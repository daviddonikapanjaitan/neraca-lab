package com.neracalab.backend.ingestion.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * A profit or loss by nature states the period's "Depreciation and amortisation expenses" in every column, the
 * interim comparative too, where no roll-forward note exists: the EBITDA of both columns is EBIT plus it, so a trailing
 * twelve months of H1 2026 + FY2025 - H1 2025 takes the D&A of all three periods alike (before, H1 2025 had no EBITDA
 * and its H1 2026 trailing EV / EBITDA was empty). The roll-forward differs from it: TLKM H1 2026 14,370 billion beside
 * the 17,203 stated.
 */
class FilingMapperStatedDaTest {

    /** ticker, H1 2026 D&A stated, H1 2025 D&A stated (full units). */
    @ParameterizedTest
    @CsvSource({
            "BIPI, 8543293, 23717840",                       // full rupiah (1312000)
            "TLKM, 17203000000000, 16818000000000",          // billions, Infrastructure Industry (3312000)
            "NSSS, 80672000000, 80031000000",                // millions (1312000)
            "BUKA, 40172810000, 56451935000"})               // thousands (1312000)
    void bothColumnsStateTheDepreciationAndAmortisation(String ticker, String current, String prior)
            throws Exception {
        // these profits or losses by nature are mapped by the full-text extraction (the rule-based one maps none of the
        // stored filings), whose EBITDA is EBIT plus this (FullTextExtractorDeriveTest)
        assertThat(mapper(ticker, "2026-II").incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(),
                mapper(ticker, "2026-II").shareCapital())).isEmpty();
        FilingMapper mapper = mapper(ticker, "2026-II");
        assertThat(mapper.statedDepreciationAndAmortization(StatementColumn.CURRENT_PERIOD))
                .isEqualByComparingTo(current);
        assertThat(mapper.statedDepreciationAndAmortization(StatementColumn.PRIOR_PERIOD)).isEqualByComparingTo(prior);
        assertThat(mapper.statedDepreciationAndAmortization(StatementColumn.PRIOR_YEAR_END)).isNull();
    }

    /** A profit or loss by function states no D&A: the roll-forward notes, current column only, as before. */
    @Test
    void aProfitOrLossByFunctionKeepsTheRollForward() throws Exception {
        FilingMapper mapper = mapper("HRTA", "2026-II");
        assertThat(mapper.statedDepreciationAndAmortization(StatementColumn.CURRENT_PERIOD)).isNull();
        MappedStatement income = mapper.incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(), mapper.shareCapital())
                .orElseThrow();
        Map<String, BigDecimal> v = income.values();
        assertThat(v.get("ebitda")).isEqualByComparingTo(v.get("ebit").add(v.get("depreciation")).add(v.get("amortization")));
        assertThat(income.derivations().get("ebitda")).contains("roll-forward notes");
        MappedStatement prior = mapper.incomeStatement(StatementColumn.PRIOR_PERIOD, Map.of(), mapper.shareCapital())
                .orElseThrow();
        assertThat(prior.values().get("ebitda")).isNull();
    }

    private static FilingMapper mapper(String ticker, String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", ticker, "xlsx", "FinancialStatement-" + filing + "-" + ticker + ".xlsx");
        assumeTrue(Files.exists(file), ticker + " source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString()));
        }
    }
}
