package com.neracalab.backend.price.provider;

/** A price request failed (network error, unexpected status or response). */
public class PriceProviderException extends RuntimeException {

    public PriceProviderException(String message) {
        super(message);
    }

    public PriceProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
