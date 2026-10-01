package com.neracalab.backend.ingestion.mapping;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Share counts derived from the statements of changes in equity (1410000 / 1410000PY).
 * IDX XBRL does not state the par value, so it is inferred: the only standard par value for which
 * common stock / par is a whole number of shares that reproduces the reported basic EPS.
 *
 * @param parValue        inferred par value per share, {@code null} when not resolvable
 * @param snapshots       share counts at every disclosed date
 * @param weightedCurrent weighted shares of the current period ({@code null} if share capital changed)
 * @param weightedPrior   weighted shares of the prior period
 */
public record ShareCapital(
        BigDecimal parValue,
        List<ShareAt> snapshots,
        BigDecimal weightedCurrent,
        BigDecimal weightedPrior,
        List<Check> checks) {

    /**
     * @param basicShares weighted shares of the period ending on {@code date}, if this filing reports it
     * @param source      sheet and position the share capital was read from
     */
    public record ShareAt(LocalDate date, BigDecimal commonStock, BigDecimal sharesOutstanding,
                          BigDecimal treasuryShares, BigDecimal basicShares, String source) {
    }

    public boolean resolved() {
        return parValue != null;
    }
}
