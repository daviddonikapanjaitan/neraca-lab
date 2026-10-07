package com.neracalab.backend.ingestion.mapping;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One statement of one column, mapped to the columns of its database table (keys are the
 * table's column names, values in full currency units; EPS and share counts unscaled).
 *
 * @param values       table column -> value, {@code null} = not reported / not derivable
 * @param derivations  table column -> how the value was obtained (audit trail)
 * @param checks       validation results; any ERROR blocks saving
 * @param unclassified lines whose meaning is unknown to the mapper (must be classified first)
 * @param rejected     columns whose filed value was found wrong (e.g. an EPS contradicting the filing's other
 *                     column): stored as NULL even over a value stored before, unlike a value that is just not
 *                     reported (which keeps another filing's value)
 */
public record MappedStatement(
        String table,
        StatementColumn column,
        PeriodRef period,
        String sourceSheet,
        Map<String, BigDecimal> values,
        Map<String, String> derivations,
        List<Check> checks,
        List<UnclassifiedLine> unclassified,
        Set<String> rejected) {

    public MappedStatement(String table, StatementColumn column, PeriodRef period, String sourceSheet,
                           Map<String, BigDecimal> values, Map<String, String> derivations, List<Check> checks,
                           List<UnclassifiedLine> unclassified) {
        this(table, column, period, sourceSheet, values, derivations, checks, unclassified, Set.of());
    }

    public record UnclassifiedLine(String label, BigDecimal value) {
    }

    public boolean hasErrors() {
        return !unclassified.isEmpty() || checks.stream().anyMatch(Check::isError);
    }
}
