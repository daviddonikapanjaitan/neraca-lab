package com.neracalab.backend.ingestion.mapping;

import java.math.BigDecimal;
import java.util.List;

/** Revenue breakdown of one duration column (sheet 1617000 by type, else 1618000 by source). */
public record SegmentExtraction(
        StatementColumn column,
        PeriodRef period,
        String sourceSheet,
        List<SegmentLine> lines,
        BigDecimal reportedTotal,
        List<Check> checks) {

    /**
     * @param name         segment name as written in the filing
     * @param filingType   PRODUCT, SERVICE or GEOGRAPHY, as the filing slots the line
     * @param revenue      full currency units
     * @param residualSlot filed in the template's "Other ... revenue" slot instead of a numbered line
     */
    public record SegmentLine(String name, String filingType, BigDecimal revenue, boolean residualSlot) {
    }

    public boolean hasErrors() {
        return checks.stream().anyMatch(Check::isError);
    }
}
