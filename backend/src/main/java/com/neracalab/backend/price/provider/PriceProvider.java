package com.neracalab.backend.price.provider;

import java.time.LocalDate;

import com.neracalab.backend.company.Exchange;

/**
 * Source of daily prices. Exactly one implementation is active, chosen by
 * {@code neracalab.prices.provider} ({@code yahoo} or {@code eodhd}), so switching the
 * source is a configuration change.
 * <p>
 * Implementations send every request through {@link PacedHttpClient}, which paces the
 * requests and keeps one HTTP client (and its cookies) for the whole application.
 */
public interface PriceProvider {

    /** Configuration value that selects this provider, e.g. {@code yahoo}. */
    String name();

    /**
     * Daily bars of {@code ticker} on {@code exchange} with trading dates from {@code from} to {@code to}
     * (both inclusive). One HTTP request.
     *
     * @throws RateLimitedException    the provider answered HTTP 429
     * @throws SymbolNotFoundException the provider does not know the symbol
     * @throws PriceProviderException  any other failure (network, unexpected response, ...)
     */
    PriceHistory fetch(Exchange exchange, String ticker, LocalDate from, LocalDate to);
}
