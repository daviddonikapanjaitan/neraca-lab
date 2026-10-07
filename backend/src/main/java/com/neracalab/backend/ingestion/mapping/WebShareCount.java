package com.neracalab.backend.ingestion.mapping;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A share count at a balance-sheet date published on a website (e.g. Yahoo Finance), for filings whose
 * statements give none. Checked against the filing before use: {@link FilingMapper#withWebShareCounts}.
 *
 * @param outstanding shares outstanding, excluding treasury shares
 * @param issued      shares issued, including treasury shares ({@code null} when not published)
 * @param treasury    treasury shares ({@code null} when not published)
 */
public record WebShareCount(LocalDate date, BigDecimal outstanding, BigDecimal issued, BigDecimal treasury) {
}
