package com.neracalab.backend.price.provider;

import java.time.Duration;

/** The provider answered HTTP 429 Too Many Requests. */
public class RateLimitedException extends PriceProviderException {

    private final Duration retryAfter;

    /** @param retryAfter wait requested by the Retry-After header; {@code null} when absent */
    public RateLimitedException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
