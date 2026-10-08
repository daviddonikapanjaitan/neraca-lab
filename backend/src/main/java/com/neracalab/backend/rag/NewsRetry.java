package com.neracalab.backend.rag;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.neracalab.backend.screening.news.NewsHttpClient;
import com.neracalab.backend.screening.news.NewsHttpClient.NewsFetchException;

/**
 * Requests to the news sites, retried when the failure is passing: a network error or timeout, or an answer that
 * says "try again" (408, 425, 429, 500, 502, 503, 504) - and, for an article, 404: a site sometimes answers 404 for an
 * article its own tag page has just listed (IDX Channel, BNGA 2026-10-08), and serves it a moment later. Up to
 * {@code retries} more attempts, after {@code backoff}, doubled for each further one. Other answers (200, 403, 410,
 * ...) are returned at once.
 */
final class NewsRetry {

    private static final Logger log = LoggerFactory.getLogger(NewsRetry.class);

    /** Answers worth asking again. */
    static final Set<Integer> PASSING = Set.of(408, 425, 429, 500, 502, 503, 504);

    private NewsRetry() {
    }

    /**
     * The page, after up to {@code retries} more attempts on a passing failure.
     *
     * @param notFoundPasses 404 is retried too (an article listed by the site), not for a tag page (404 = no such tag)
     * @return the last answer (which may still be a passing failure when every attempt failed)
     * @throws NewsFetchException the network error of the last attempt, or an interruption (flag restored)
     */
    static NewsHttpClient.Page get(NewsHttpClient http, URI uri, int retries, Duration backoff, boolean notFoundPasses) {
        Duration wait = backoff;
        for (int attempt = 1; ; attempt++) {
            boolean last = attempt > retries;
            String failure;
            try {
                NewsHttpClient.Page page = http.get(uri);
                if (last || !passing(page.status(), notFoundPasses)) {
                    return page;
                }
                failure = "HTTP " + page.status();
            } catch (NewsFetchException e) {
                if (last || Thread.currentThread().isInterrupted()) {
                    throw e;
                }
                failure = e.getMessage();
            }
            log.info("{} failed ({}), attempt {} of {}; retrying in {} ms", uri, failure, attempt, retries + 1,
                    wait.toMillis());
            pause(wait, uri);
            wait = wait.multipliedBy(2);
        }
    }

    static boolean passing(int status, boolean notFoundPasses) {
        return PASSING.contains(status) || (notFoundPasses && status == 404);
    }

    private static void pause(Duration wait, URI uri) {
        if (!wait.isPositive()) {
            return;
        }
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NewsFetchException("Interrupted while waiting to retry " + uri.getHost(), e);
        }
    }
}
