package com.neracalab.backend.ingestion.mapping;

/**
 * The columns of an IDX filing. Statement sheets carry two contexts each: income statement and
 * cash flow have current / prior year-to-date durations, the balance sheet has the current period
 * end and the prior fiscal year end.
 *
 * <ul>
 *   <li>{@link #CURRENT_PERIOD}: the period the filing reports (income, cash flow, balance sheet).</li>
 *   <li>{@link #PRIOR_PERIOD}: the comparative year-to-date period of the prior year (income, cash
 *       flow; for annual filings also the prior year-end balance sheet).</li>
 *   <li>{@link #PRIOR_YEAR_END}: interim filings only, the balance sheet at the prior fiscal year end.</li>
 * </ul>
 */
public enum StatementColumn {
    CURRENT_PERIOD,
    PRIOR_PERIOD,
    PRIOR_YEAR_END;

    public boolean isComparative() {
        return this != CURRENT_PERIOD;
    }
}
