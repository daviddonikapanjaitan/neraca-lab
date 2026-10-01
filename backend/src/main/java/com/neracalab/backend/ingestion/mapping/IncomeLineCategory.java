package com.neracalab.backend.ingestion.mapping;

import java.util.Map;

/**
 * Meaning of a profit-or-loss line item in the IDX "by function" template. Expense and cost
 * categories are reported as positive amounts and subtracted; NON_OPERATING_GAIN_OR_LOSS is signed.
 */
public enum IncomeLineCategory {
    REVENUE,
    COST_OF_REVENUE,
    SELLING_EXPENSE,
    GENERAL_ADMINISTRATIVE_EXPENSE,
    /** operating income outside revenue, e.g. "Other income" (positive). */
    OTHER_OPERATING_INCOME,
    /** operating expense outside selling / G&A, e.g. "Other expenses" (positive). */
    OTHER_OPERATING_EXPENSE,
    /** interest / finance income (positive). */
    FINANCE_INCOME,
    /** interest and finance costs (positive). */
    FINANCE_COST,
    /** signed gains or losses below operating profit: FX, associates, fair value, dividends, ... */
    NON_OPERATING_GAIN_OR_LOSS,
    /** final tax charged before profit before tax (positive). */
    FINAL_TAX_EXPENSE,
    /** subtotal, header or other comprehensive income line: not part of profit before tax. */
    IGNORE;

    /** Default classification of the template's line items. */
    static final Map<String, IncomeLineCategory> KNOWN = Map.ofEntries(
            Map.entry("Sales and revenue", REVENUE),
            Map.entry("Cost of sales and revenue", COST_OF_REVENUE),
            Map.entry("Selling expenses", SELLING_EXPENSE),
            Map.entry("General and administrative expenses", GENERAL_ADMINISTRATIVE_EXPENSE),
            Map.entry("Finance income", FINANCE_INCOME),
            Map.entry("Interest income", FINANCE_INCOME),
            Map.entry("Dividends income", NON_OPERATING_GAIN_OR_LOSS),
            Map.entry("Investment income", NON_OPERATING_GAIN_OR_LOSS),
            Map.entry("Interest and finance costs", FINANCE_COST),
            Map.entry("Gains (losses) on changes in foreign exchange rates", NON_OPERATING_GAIN_OR_LOSS),
            Map.entry("Share of profit (loss) of associates accounted for using equity method", NON_OPERATING_GAIN_OR_LOSS),
            Map.entry("Share of profit (loss) of joint ventures accounted for using equity method", NON_OPERATING_GAIN_OR_LOSS),
            Map.entry("Gains (losses) on changes in fair value of marketable securities", NON_OPERATING_GAIN_OR_LOSS),
            Map.entry("Realised gains (losses) on trading of marketable securities", NON_OPERATING_GAIN_OR_LOSS),
            Map.entry("Gains (losses) on derivative financial instruments", NON_OPERATING_GAIN_OR_LOSS),
            Map.entry("Final tax expenses", FINAL_TAX_EXPENSE),
            Map.entry("Other income", OTHER_OPERATING_INCOME),
            Map.entry("Other expenses", OTHER_OPERATING_EXPENSE),
            Map.entry("Other gains (losses)", NON_OPERATING_GAIN_OR_LOSS));
}
