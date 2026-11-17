package com.neracalab.backend.crawl;

/**
 * A crawled source could not be read: network error, unexpected status, or a page / file whose layout is not the one
 * the parser knows. Nothing is stored from such a response.
 */
public class SourceException extends RuntimeException {

    public SourceException(String message) {
        super(message);
    }

    public SourceException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * The source answered HTTP 429 (too many requests): the crawler stops and does not retry the request; a source that
     * reads in many requests may keep what it read and continue later ({@code MacroSource.PartialFetch}).
     */
    public static class RateLimited extends SourceException {

        public RateLimited(String message) {
            super(message);
        }
    }
}
