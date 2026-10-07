package com.neracalab.backend.ingestion;

import java.math.BigDecimal;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.ingestion.agent.IngestionSession;
import com.neracalab.backend.ingestion.mapping.Check;
import com.neracalab.backend.ingestion.mapping.ShareCapital;
import com.neracalab.backend.ingestion.mapping.WebShareCount;
import com.neracalab.backend.screening.data.YahooFundamentalsClient;

/**
 * Share counts from the web for filings whose statements give none (several share classes, treasury
 * shares, imprecise EPS, share capital in another currency, ...). Without share counts there is no
 * market cap and no valuation. Yahoo Finance publishes year-end and quarter-end shares outstanding,
 * issued and treasury shares (BNGA 2022 .. 2025: exactly the audited counts of its annual reports);
 * {@link com.neracalab.backend.ingestion.mapping.FilingMapper#withWebShareCounts} keeps only the counts
 * that fit the filing's own EPS. Deterministic: runs before the agent, which only saves the result.
 * A failed or empty fetch leaves the filing's share capital as it was (a note says why).
 */
@Component
public class WebShareCounts {

    static final String SOURCE = "Yahoo Finance";

    private static final Logger log = LoggerFactory.getLogger(WebShareCounts.class);

    private final YahooFundamentalsClient yahoo;
    private final boolean enabled;

    public WebShareCounts(YahooFundamentalsClient yahoo,
                          @Value("${neracalab.ingestion.web-share-counts:true}") boolean enabled) {
        this.yahoo = yahoo;
        this.enabled = enabled;
    }

    /** Completes the session's share counts from the web when the filing gives none. */
    public void complete(IngestionSession session) {
        ShareCapital filing = session.shareCapital();
        if (!enabled || filing.resolved() || filing.snapshots().isEmpty()) {
            return;
        }
        String ticker = session.info().ticker();
        List<WebShareCount> counts;
        try {
            counts = yahoo.shareCounts(Exchange.IDX, ticker).stream()
                    .map(c -> new WebShareCount(c.date(), big(c.outstanding()), big(c.issued()), big(c.treasury())))
                    .toList();
        } catch (RuntimeException e) {
            log.warn("ingestion {}: share counts of {} from {} failed: {}", session.id(), ticker, SOURCE, e.getMessage());
            session.note("The filing gives no share counts and " + SOURCE + " could not be read (" + e.getMessage()
                    + "); share counts are left empty");
            return;
        }
        ShareCapital completed = session.mapper().withWebShareCounts(filing, counts, SOURCE);
        session.shareCapital(completed);
        if (completed.resolved()) {
            session.note("The filing gives no share counts; " + completed.basis());
        } else {
            session.note("The filing gives no share counts and no " + SOURCE + " count fits it: "
                    + completed.checks().stream().filter(c -> "web_shares".equals(c.rule())).map(Check::message).toList());
        }
    }

    private static BigDecimal big(Long value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }
}
