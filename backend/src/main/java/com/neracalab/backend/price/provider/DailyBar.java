package com.neracalab.backend.price.provider;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One daily bar as delivered by a provider, in the listing currency. Any value may be
 * {@code null} (e.g. holiday placeholders of Yahoo carry no prices).
 *
 * @param date          trading date in the exchange time zone
 * @param adjustedClose close adjusted for splits and cash dividends (provider adjustment)
 */
public record DailyBar(LocalDate date, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
                       BigDecimal adjustedClose, Long volume) {
}
