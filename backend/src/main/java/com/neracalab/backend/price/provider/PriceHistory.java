package com.neracalab.backend.price.provider;

import java.util.List;

/**
 * Daily bars of one symbol, oldest first.
 *
 * @param symbol   provider symbol, e.g. HRTA.JK
 * @param currency currency of the prices as reported by the provider; {@code null} when the provider does not say
 */
public record PriceHistory(String symbol, String currency, List<DailyBar> bars) {
}
