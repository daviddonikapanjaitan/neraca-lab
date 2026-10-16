package com.neracalab.backend.ingestion.mapping;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A share split published on a website (Yahoo Finance's split events), for checking published share counts, which
 * are split-adjusted to today, against a filing of before the split ({@link FilingMapper#withWebShareCounts}).
 *
 * @param date         the day the split took effect
 * @param factor       new shares per old share (10 for a split 1:10, 0.2 for a reverse split 5:1)
 * @param sharesBefore shares outstanding just before the split, on the count of before it; {@code null} until
 *                     {@link FilingMapper#withWebShareCounts} derives it from the published counts
 */
public record WebSplit(LocalDate date, BigDecimal factor, BigDecimal sharesBefore) {

    public WebSplit(LocalDate date, BigDecimal factor) {
        this(date, factor, null);
    }
}
