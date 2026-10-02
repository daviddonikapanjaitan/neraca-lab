package com.neracalab.backend.price.provider;

/** The provider has no data for the symbol (unknown, delisted or not covered). */
public class SymbolNotFoundException extends PriceProviderException {

    public SymbolNotFoundException(String message) {
        super(message);
    }
}
