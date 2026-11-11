package com.neracalab.backend.portfolio.intraday;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.neracalab.backend.portfolio.intraday.IntradayRepository.Held;
import com.neracalab.backend.price.provider.RateLimitedException;

/**
 * Keeps the intraday prices of the stocks held in Portfolio &gt; Position: every few minutes during the IDX session
 * ({@link IntradaySchedule}), and on request (the page's "Refresh" button), the latest session of each held company is
 * read from Yahoo Finance ({@link YahooIntradayClient}) and stored in {@code intraday_quote} / {@code intraday_bar}.
 * <p>
 * Safe for the portfolios: nothing but the two intraday tables is written - the positions, the cash and the daily
 * prices ({@code price_daily}, read by Fundamentals and Technicals) are never touched. One refresh runs at a time; a
 * company fetched less than {@link #MIN_AGE} ago is not fetched again. An HTTP 429 stops the round (the paced client
 * slows down; the next round tries again).
 */
@Service
public class IntradayService {

    private static final Logger log = LoggerFactory.getLogger(IntradayService.class);

    /** A company is not fetched again within this time (a manual refresh right after the schedule's). */
    static final Duration MIN_AGE = Duration.ofSeconds(60);

    /**
     * The result of a refresh.
     *
     * @param companies companies asked for
     * @param fetched   read from Yahoo and stored
     * @param fresh     skipped: fetched less than a minute ago
     * @param failed    ticker: why
     */
    public record Refresh(Instant at, int companies, int fetched, int fresh, List<String> failed, boolean rateLimited,
                          long millis) {
    }

    private final IntradayRepository repository;
    private final YahooIntradayClient yahoo;
    private final LocalTime sessionStart;
    private final LocalTime sessionEnd;
    private final int keepDays;
    private final String cron;
    private final boolean scheduled;
    private final ReentrantLock running = new ReentrantLock();
    private final AtomicReference<Refresh> last = new AtomicReference<>();
    private Clock clock = Clock.system(IdxSession.ZONE);

    @Autowired
    public IntradayService(IntradayRepository repository, YahooIntradayClient yahoo,
                           @Value("${neracalab.intraday.session-start:09:00}") LocalTime sessionStart,
                           @Value("${neracalab.intraday.session-end:16:20}") LocalTime sessionEnd,
                           @Value("${neracalab.intraday.keep-days:10}") int keepDays,
                           @Value("${neracalab.intraday.schedule.cron:0 */5 9-16 * * MON-FRI}") String cron,
                           @Value("${neracalab.intraday.schedule.enabled:true}") boolean scheduled) {
        this.repository = repository;
        this.yahoo = yahoo;
        this.sessionStart = sessionStart;
        this.sessionEnd = sessionEnd;
        this.keepDays = keepDays;
        this.cron = cron;
        this.scheduled = scheduled;
    }

    /** For tests: a fixed time. */
    void clock(Clock clock) {
        this.clock = clock;
    }

    /** Whether now is within the hours the schedule fetches (a weekday, session start to session end, WIB). */
    public boolean withinSession() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        if (now.getDayOfWeek().getValue() >= 6) {
            return false;
        }
        LocalTime t = now.toLocalTime();
        return !t.isBefore(sessionStart) && !t.isAfter(sessionEnd);
    }

    public IdxSession.Phase phase() {
        return IdxSession.phase(ZonedDateTime.now(clock));
    }

    /** The minutes between two scheduled refreshes, read from the cron ("0 *&#47;5 ..." -> 5); null when unknown. */
    public Integer everyMinutes() {
        String[] parts = cron.trim().split("\\s+");
        if (parts.length >= 2 && parts[1].startsWith("*/")) {
            try {
                return Integer.parseInt(parts[1].substring(2));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public boolean scheduled() {
        return scheduled;
    }

    public Refresh last() {
        return last.get();
    }

    /** The schedule's round: every held company, during the session hours only. */
    public Refresh refreshScheduled() {
        if (!withinSession()) {
            return null;
        }
        Refresh r = refresh(repository.held());
        if (r != null) {
            int deleted = repository.deleteBarsBefore(ZonedDateTime.now(clock).toLocalDate().minusDays(keepDays));
            if (deleted > 0) {
                log.info("Intraday: {} bars older than {} days deleted", deleted, keepDays);
            }
        }
        return r;
    }

    /** A user's "Refresh": the companies of their portfolio, at any time (the latest session Yahoo has). */
    public Refresh refreshUser(long userId) {
        return refresh(repository.held(userId));
    }

    /** Reads and stores the latest session of each company; null when another refresh is running. */
    Refresh refresh(List<Held> companies) {
        if (!running.tryLock()) {
            return null;
        }
        long start = System.nanoTime();
        try {
            int fetched = 0;
            int fresh = 0;
            boolean limited = false;
            List<String> failed = new ArrayList<>();
            Instant now = clock.instant();
            for (Held h : companies) {
                Instant at = repository.fetchedAt(h.companyId());
                if (at != null && at.plus(MIN_AGE).isAfter(now)) {
                    fresh++;
                    continue;
                }
                try {
                    repository.save(h.companyId(), yahoo.fetch(h.ticker()));
                    fetched++;
                } catch (RateLimitedException e) {
                    limited = true;
                    failed.add(h.ticker() + ": rate limited by Yahoo Finance");
                    break;
                } catch (RuntimeException e) {
                    // one listing never stops the others
                    failed.add(h.ticker() + ": " + e.getMessage());
                }
            }
            Refresh r = new Refresh(Instant.now(), companies.size(), fetched, fresh, List.copyOf(failed), limited,
                    (System.nanoTime() - start) / 1_000_000);
            last.set(r);
            if (!failed.isEmpty()) {
                log.warn("Intraday refresh: {} of {} fetched, failed: {}", fetched, companies.size(), failed);
            } else {
                log.info("Intraday refresh: {} of {} fetched ({} fresh) in {} ms", fetched, companies.size(), fresh,
                        r.millis());
            }
            return r;
        } finally {
            running.unlock();
        }
    }
}
