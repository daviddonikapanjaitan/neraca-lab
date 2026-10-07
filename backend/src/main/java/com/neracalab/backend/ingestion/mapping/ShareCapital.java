package com.neracalab.backend.ingestion.mapping;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Share counts derived from the statements of changes in equity (1410000 / 1410000PY).
 * IDX XBRL does not state the par value, so it is inferred: the only standard par value for which
 * common stock / par is a whole number of shares that reproduces the reported basic EPS. When no par
 * value fits (share capital in another currency, e.g. INDY in USD), shares outstanding come from an
 * exact EPS denominator instead (FilingMapper#sharesFromEps).
 *
 * @param parValue        inferred par value per share, {@code null} when not inferable
 * @param basis           how the share counts were obtained ({@code par value 100} or the EPS derivation);
 *                        {@code null} when they could not be
 * @param snapshots       share counts at every disclosed date
 * @param weightedCurrent weighted shares of the current period ({@code null} if share capital changed)
 * @param weightedPrior   weighted shares of the prior period
 * @param webSource       {@code null} when the counts come from the filing; otherwise the website they were
 *                        fetched from (checked against the filing, see FilingMapper#withWebShareCounts),
 *                        stored only where no count is stored yet
 */
public record ShareCapital(
        BigDecimal parValue,
        String basis,
        List<ShareAt> snapshots,
        BigDecimal weightedCurrent,
        BigDecimal weightedPrior,
        List<Check> checks,
        String webSource) {

    /**
     * @param basicShares weighted shares of the period ending on {@code date}, if this filing reports it
     * @param source      sheet and position the share capital was read from
     */
    public record ShareAt(LocalDate date, BigDecimal commonStock, BigDecimal sharesOutstanding,
                          BigDecimal treasuryShares, BigDecimal basicShares, String source) {
    }

    public boolean resolved() {
        return basis != null;
    }
}
