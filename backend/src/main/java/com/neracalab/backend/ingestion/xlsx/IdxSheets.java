package com.neracalab.backend.ingestion.xlsx;

/** Sheet codes of the IDX XBRL "General Industry" taxonomy used by the ingestion (other taxonomies are read under these names, see {@link IdxTaxonomy}). */
public final class IdxSheets {

    public static final String BALANCE_SHEET = "1210000";            // current / non-current presentation
    public static final String BALANCE_SHEET_LIQUIDITY = "1220000";  // order of liquidity (Financial taxonomy only)
    public static final String INCOME_BY_FUNCTION = "1311000";
    public static final String INCOME_BY_NATURE = "1312000";         // by nature (Financial taxonomy only)
    public static final String INCOME_BY_FUNCTION_BEFORE_TAX = "1321000";
    public static final String INCOME_BY_NATURE_BEFORE_TAX = "1322000"; // by nature (Financial taxonomy only)
    public static final String EQUITY = "1410000";
    public static final String EQUITY_PRIOR_YEAR = "1410000PY";
    public static final String CASH_FLOW_DIRECT = "1510000";
    public static final String CASH_FLOW_INDIRECT = "1520000";
    public static final String PPE = "1611000";
    public static final String PPE_PRIOR_YEAR = "1611000PY";
    public static final String RIGHT_OF_USE = "1612000";
    public static final String RIGHT_OF_USE_PRIOR_YEAR = "1612000PY";
    public static final String REVENUE_BY_TYPE = "1617000";
    public static final String REVENUE_BY_SOURCE = "1618000";

    private IdxSheets() {
    }
}
