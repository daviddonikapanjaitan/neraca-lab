package com.neracalab.backend.ingestion.mapping;

import java.util.Map;

/**
 * Meaning of a profit-or-loss line item (IDX "by function" template; banks: "by nature", see
 * {@link #FINANCIAL_KNOWN}). Expense and cost categories are reported as positive amounts and
 * subtracted; NON_OPERATING_GAIN_OR_LOSS is signed.
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
    /** expenses below operating profit, e.g. a bank's "Non-operating expenses" (positive). */
    NON_OPERATING_EXPENSE,
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

    /**
     * Default classification of the "Financial and Sharia Industry" template (profit or loss by nature,
     * 4312000 / 4322000), whose lines differ from the General Industry ones (e.g. "Interest income" is a
     * bank's revenue, not finance income). Revenue = interest and sharia income + fee, commission, trading,
     * investment and other operating income; cost of revenue = interest expense and the syirkah fund
     * holders' share. Recoveries and reversals are signed operating income; impairment charges operating
     * expenses. Insurance premiums are revenue and insurance claims cost of revenue (a bank's insurance
     * subsidiary); the other insurance lines, whose signs differ between filers, are left to the agent.
     */
    static final Map<String, IncomeLineCategory> FINANCIAL_KNOWN = Map.ofEntries(
            Map.entry("Interest income", REVENUE),
            Map.entry("Revenue from fund management as mudharib", REVENUE),
            Map.entry("Revenue from consumer financing", REVENUE),
            Map.entry("Revenue from finance lease", REVENUE),
            Map.entry("Revenue from operating lease", REVENUE),
            Map.entry("Revenue from factoring", REVENUE),
            Map.entry("Revenue from underwriting activities and selling fees", REVENUE),
            Map.entry("Revenue from financing transactions", REVENUE),
            Map.entry("Revenue from securities administration service", REVENUE),
            Map.entry("Revenue from investment management services", REVENUE),
            Map.entry("Revenue from financial advisory services", REVENUE),
            Map.entry("Realised gains (losses) on trading of marketable securities", REVENUE),
            Map.entry("Gains (losses) on changes in fair value of marketable securities", REVENUE),
            Map.entry("Investments income", REVENUE),
            Map.entry("Provisions and commissions income from transactions other than loan", REVENUE),
            // bancassurance fees (BTPN FY2023: 54,570 million; profit from operation reconciles with it as revenue)
            Map.entry("Insurance commission income", REVENUE),
            // a bank's insurance subsidiary (BMRI FY2025: 550,415 million; profit from operation reconciles with it)
            Map.entry("Revenue from insurance premiums", REVENUE),
            Map.entry("Claim expenses", COST_OF_REVENUE),
            Map.entry("Revenue from trading transactions", REVENUE),
            Map.entry("Dividends income", REVENUE),
            Map.entry("Realised gains (losses) from derivative instruments", REVENUE),
            Map.entry("Gains (losses) on changes in foreign exchange rates", REVENUE),
            Map.entry("Other operating income", REVENUE),
            Map.entry("Interest expenses", COST_OF_REVENUE),
            Map.entry("Third parties share on return of temporary syirkah funds", COST_OF_REVENUE),
            Map.entry("Revenue from recovery of written-off assets", OTHER_OPERATING_INCOME),
            Map.entry("Gains (losses) on disposal of property and equipment", OTHER_OPERATING_INCOME),
            Map.entry("Gains (losses) on disposal of foreclosed assets", OTHER_OPERATING_INCOME),
            Map.entry("Recovery of impairment loss of financial assets", OTHER_OPERATING_INCOME),
            Map.entry("Recovery of impairment loss of financial assets finance lease", OTHER_OPERATING_INCOME),
            Map.entry("Recovery of impairment loss of financial assets consumer financing receivables", OTHER_OPERATING_INCOME),
            Map.entry("Recovery of impairment loss of non-financial assets", OTHER_OPERATING_INCOME),
            Map.entry("Recovery of impairment loss of non-financial assets repossessed collaterals", OTHER_OPERATING_INCOME),
            Map.entry("Recovery of estimated loss of commitments and contingency", OTHER_OPERATING_INCOME),
            // "Reversal (expense)": positive = reversal (income), negative = expense
            Map.entry("Reversal (expense) of estimated losses on commitments and contingencies", OTHER_OPERATING_INCOME),
            Map.entry("Allowances for impairment losses on earnings assets", OTHER_OPERATING_EXPENSE),
            Map.entry("Allowances for impairment losses on non-earnings assets", OTHER_OPERATING_EXPENSE),
            Map.entry("General and administrative expenses", GENERAL_ADMINISTRATIVE_EXPENSE),
            Map.entry("Selling expenses", SELLING_EXPENSE),
            Map.entry("Rent, maintenance and improvement expenses", OTHER_OPERATING_EXPENSE),
            Map.entry("Other fees and commissions expenses", OTHER_OPERATING_EXPENSE),
            Map.entry("Other operating expenses", OTHER_OPERATING_EXPENSE),
            Map.entry("Non-operating income", NON_OPERATING_GAIN_OR_LOSS),
            Map.entry("Non-operating expenses", NON_OPERATING_EXPENSE),
            Map.entry("Share of profit (loss) of associates accounted for using equity method", NON_OPERATING_GAIN_OR_LOSS),
            Map.entry("Share of profit (loss) of joint ventures accounted for using equity method", NON_OPERATING_GAIN_OR_LOSS));
}
