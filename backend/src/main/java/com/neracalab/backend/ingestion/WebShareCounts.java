package com.neracalab.backend.ingestion;

import java.math.BigDecimal;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.ingestion.agent.IngestionSession;
import com.neracalab.backend.ingestion.mapping.Check;
import com.neracalab.backend.ingestion.mapping.ShareCapital;
import com.neracalab.backend.ingestion.mapping.WebShareCount;
import com.neracalab.backend.ingestion.mapping.WebSplit;
import com.neracalab.backend.screening.data.YahooFundamentalsClient;
import com.neracalab.backend.technical.YahooChartClient;

/**
 * Share counts from the web for filings whose statements give none (several share classes, treasury
 * shares, imprecise EPS, share capital in another currency, ...). Without share counts there is no
 * market cap and no valuation. Yahoo Finance publishes year-end and quarter-end shares outstanding,
 * issued and treasury shares (BNGA 2022 .. 2025: exactly the audited counts of its annual reports);
 * {@link com.neracalab.backend.ingestion.mapping.FilingMapper#withWebShareCounts} keeps only the counts
 * that fit the filing's own EPS. Deterministic: runs before the agent, which only saves the result.
 * A failed or empty fetch leaves the filing's share capital as it was (a note says why).
 * <p>
 * Yahoo's counts are split-adjusted to today, so they fit a filing of before a split only on the count of before
 * it (CUAN FY2024, DSSA FY2022 .. FY2025): Yahoo's split events are fetched too. Without them (a failed fetch) a
 * count is only checked as published.
 */
@Component
public class WebShareCounts {

    static final String SOURCE = "Yahoo Finance";

    private static final Logger log = LoggerFactory.getLogger(WebShareCounts.class);

    private final YahooFundamentalsClient yahoo;
    private final YahooChartClient chart;
    private final boolean enabled;

    @Autowired
    public WebShareCounts(YahooFundamentalsClient yahoo, YahooChartClient chart,
                          @Value("${neracalab.ingestion.web-share-counts:true}") boolean enabled) {
        this.yahoo = yahoo;
        this.chart = chart;
        this.enabled = enabled;
    }

    /** Without the split events (tests). */
    public WebShareCounts(YahooFundamentalsClient yahoo, boolean enabled) {
        this(yahoo, null, enabled);
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
        ShareCapital completed = session.mapper().withWebShareCounts(filing, counts, splits(session, ticker), SOURCE);
        session.shareCapital(completed);
        if (completed.resolved()) {
            session.note("The filing gives no share counts; " + completed.basis());
        } else {
            session.note("The filing gives no share counts and no " + SOURCE + " count fits it: "
                    + completed.checks().stream().filter(c -> "web_shares".equals(c.rule())).map(Check::message).toList());
        }
    }

    /** Yahoo's splits of the company (STOCK_SPLIT / REVERSE_SPLIT events, value = new shares per old share). */
    private List<WebSplit> splits(IngestionSession session, String ticker) {
        if (chart == null) {
            return List.of();
        }
        try {
            return chart.events(YahooFundamentalsClient.symbol(Exchange.IDX, ticker), Exchange.IDX.zone()).stream()
                    .filter(e -> ("STOCK_SPLIT".equals(e.type()) || "REVERSE_SPLIT".equals(e.type())) && e.value() != null)
                    .map(e -> new WebSplit(e.date(), e.value()))
                    .toList();
        } catch (RuntimeException e) {
            log.warn("ingestion {}: splits of {} from {} failed: {}", session.id(), ticker, SOURCE, e.getMessage());
            session.note(SOURCE + "'s splits of " + ticker + " could not be read (" + e.getMessage()
                    + "); its share counts are checked against the filing's EPS as published");
            return List.of();
        }
    }

    private static BigDecimal big(Long value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }
}
