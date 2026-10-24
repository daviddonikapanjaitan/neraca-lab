package com.neracalab.backend.market;

/**
 * Stored market or macro data changed: rows of {@code index_daily}, {@code instrument_daily}, {@code macro_series} or
 * {@code fx_rate_daily} were inserted, changed or deleted. Published by their repositories; a listener of a cache of
 * views read from them (the Macro page's outlook, {@code MacroOutlookCache}) runs after the commit.
 *
 * @param table the table written
 */
public record MarketDataChanged(String table) {

    /** Whether a batch wrote a row: 0 is an unchanged row, any other count (also "no info", -2) may be a change. */
    public static boolean written(int[]... batches) {
        for (int[] batch : batches) {
            for (int count : batch) {
                if (count != 0) {
                    return true;
                }
            }
        }
        return false;
    }
}
