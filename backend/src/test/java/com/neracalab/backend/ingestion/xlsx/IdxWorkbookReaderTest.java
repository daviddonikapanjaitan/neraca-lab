package com.neracalab.backend.ingestion.xlsx;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Sheet names of the pre-2023 template and of the Infrastructure Industry taxonomy map to the General ones. */
class IdxWorkbookReaderTest {

    @Test
    void generalIndustryNamesAreKept() {
        assertThat(IdxWorkbookReader.canonicalName("1000000")).isEqualTo("1000000");
        assertThat(IdxWorkbookReader.canonicalName("1210000")).isEqualTo("1210000");
        assertThat(IdxWorkbookReader.canonicalName("1410000PY")).isEqualTo("1410000PY");
        assertThat(IdxWorkbookReader.canonicalName("Context")).isEqualTo("Context");
        assertThat(IdxWorkbookReader.canonicalName("3621000a")).isEqualTo("3621000a");   // a note sheet, not used
    }

    @Test
    void preTemplateNamesGetTheCurrentNames() {
        assertThat(IdxWorkbookReader.canonicalName("1410000 1 CurrentYear")).isEqualTo("1410000");
        assertThat(IdxWorkbookReader.canonicalName("1410000 2 PriorYear")).isEqualTo("1410000PY");
    }

    @Test
    void infrastructureNamesGetTheGeneralCodes() {
        assertThat(IdxWorkbookReader.canonicalName("3210000")).isEqualTo(IdxSheets.BALANCE_SHEET);
        assertThat(IdxWorkbookReader.canonicalName("3311000")).isEqualTo(IdxSheets.INCOME_BY_FUNCTION);
        assertThat(IdxWorkbookReader.canonicalName("3510000")).isEqualTo(IdxSheets.CASH_FLOW_DIRECT);
        assertThat(IdxWorkbookReader.canonicalName("3410000PY")).isEqualTo(IdxSheets.EQUITY_PRIOR_YEAR);
        assertThat(IdxWorkbookReader.canonicalName("3410000 1 CurrentYear")).isEqualTo(IdxSheets.EQUITY);
        assertThat(IdxWorkbookReader.canonicalName("3611000 2 PriorYear")).isEqualTo(IdxSheets.PPE_PRIOR_YEAR);
        assertThat(IdxWorkbookReader.canonicalName("3617000")).isEqualTo(IdxSheets.REVENUE_BY_TYPE);
    }

    @Test
    void financialNamesGetTheGeneralCodes() {
        assertThat(IdxWorkbookReader.canonicalName("4220000")).isEqualTo(IdxSheets.BALANCE_SHEET_LIQUIDITY);
        assertThat(IdxWorkbookReader.canonicalName("4322000")).isEqualTo(IdxSheets.INCOME_BY_NATURE_BEFORE_TAX);
        assertThat(IdxWorkbookReader.canonicalName("4312000")).isEqualTo(IdxSheets.INCOME_BY_NATURE);
        assertThat(IdxWorkbookReader.canonicalName("4510000")).isEqualTo(IdxSheets.CASH_FLOW_DIRECT);
        assertThat(IdxWorkbookReader.canonicalName("4410000 2 PriorYear")).isEqualTo(IdxSheets.EQUITY_PRIOR_YEAR);
        assertThat(IdxWorkbookReader.canonicalName("4611000PY")).isEqualTo(IdxSheets.PPE_PRIOR_YEAR);
        assertThat(IdxWorkbookReader.canonicalName("4611100a")).isEqualTo("4611100a");   // a note sheet, not used
    }

    @Test
    void taxonomyFollowsTheFiledCode() {
        assertThat(IdxTaxonomy.of("1210000")).isEqualTo(IdxTaxonomy.GENERAL);
        assertThat(IdxTaxonomy.of("3410000PY")).isEqualTo(IdxTaxonomy.INFRASTRUCTURE);
        assertThat(IdxTaxonomy.of("4220000")).isEqualTo(IdxTaxonomy.FINANCIAL);
        assertThat(IdxTaxonomy.of("Context")).isNull();
        assertThat(IdxTaxonomy.FINANCIAL.code(IdxSheets.BALANCE_SHEET_LIQUIDITY)).isEqualTo("4220000");
    }
}
