package com.neracalab.backend.ingestion;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

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
import com.neracalab.backend.price.provider.PacedHttpClient;
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
 * <p>
 * The answers are kept per company for {@link #CACHE_TTL}: the requests to Yahoo go one at a time with a pause of
 * 1 - 2 s, shared with the price ingestion (PacedHttpClient), and a company's filings arrive together (40% of the 892
 * uploads of 2026-10-29 - 31 fetched counts, for 118 companies: three filings each). With 50 uploads at once the
 * second and third filing of a company no longer wait in that line for an answer the first one got. A failed fetch is
 * not kept. The requests themselves go {@link PacedHttpClient#ahead} of the price jobs waiting in that line (the
 * uploads queue one per stored filing): the upload is waiting on them.
 */
@Component
public class WebShareCounts {

    static final String SOURCE = "Yahoo Finance";

    private static final Logger log = LoggerFactory.getLogger(WebShareCounts.class);

    /** How long a company's share counts and splits from Yahoo are reused. */
    static final Duration CACHE_TTL = Duration.ofHours(6);

    private record Cached<T>(T value, long fetchedAt) {
        boolean fresh() {
            return System.nanoTime() - fetchedAt < CACHE_TTL.toNanos();
        }
    }

    private final YahooFundamentalsClient yahoo;
    private final YahooChartClient chart;
    private final boolean enabled;
    private final Map<String, Cached<List<WebShareCount>>> countCache = new ConcurrentHashMap<>();
    private final Map<String, Cached<List<WebSplit>>> splitCache = new ConcurrentHashMap<>();
    /** Requests sent to Yahoo (not answered from the cache). */
    private final java.util.concurrent.atomic.AtomicLong fetches = new java.util.concurrent.atomic.AtomicLong();

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
            counts = cached(countCache, ticker, () -> PacedHttpClient.ahead(() -> yahoo.shareCounts(Exchange.IDX, ticker)).stream()
                    .map(c -> new WebShareCount(c.date(), big(c.outstanding()), big(c.issued()), big(c.treasury())))
                    .toList());
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
            return cached(splitCache, ticker, () -> PacedHttpClient.ahead(() -> chart.events(
                            YahooFundamentalsClient.symbol(Exchange.IDX, ticker), Exchange.IDX.zone())).stream()
                    .filter(e -> ("STOCK_SPLIT".equals(e.type()) || "REVERSE_SPLIT".equals(e.type())) && e.value() != null)
                    .map(e -> new WebSplit(e.date(), e.value()))
                    .toList());
        } catch (RuntimeException e) {
            log.warn("ingestion {}: splits of {} from {} failed: {}", session.id(), ticker, SOURCE, e.getMessage());
            session.note(SOURCE + "'s splits of " + ticker + " could not be read (" + e.getMessage()
                    + "); its share counts are checked against the filing's EPS as published");
            return List.of();
        }
    }

    /** The company's answer from the cache while fresh, else fetched (a failure is thrown and not kept). */
    private <T> T cached(Map<String, Cached<T>> cache, String ticker, Supplier<T> fetch) {
        String key = ticker == null ? "" : ticker.trim().toUpperCase(java.util.Locale.ROOT);
        Cached<T> hit = cache.get(key);
        if (hit != null && hit.fresh()) {
            return hit.value();
        }
        fetches.incrementAndGet();
        T value = fetch.get();
        cache.put(key, new Cached<>(value, System.nanoTime()));
        return value;
    }

    /** Requests sent to Yahoo Finance so far (tests). */
    long fetches() {
        return fetches.get();
    }

    private static BigDecimal big(Long value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }
}
