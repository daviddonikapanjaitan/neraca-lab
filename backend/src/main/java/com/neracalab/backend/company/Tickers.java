package com.neracalab.backend.company;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Ticker codes as stored in {@code company.ticker}: upper case, no blanks, 1-20 characters of
 * letters, digits, '.' and '-', starting with a letter or digit (HRTA, BBCA, BRK.B, 0700).
 * Mirrors the database check {@code ck_company_ticker}.
 */
public final class Tickers {

    private static final Pattern VALID = Pattern.compile("[A-Z0-9][A-Z0-9.-]{0,19}");

    private Tickers() {
    }

    /** Trims and upper-cases, e.g. {@code " hrta"} -> {@code "HRTA"}; rejects anything that is not a valid ticker. */
    public static String normalize(String ticker) {
        String normalized = ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT);
        if (!VALID.matcher(normalized).matches()) {
            throw new InvalidTickerException("Invalid ticker '" + ticker
                    + "': 1-20 letters, digits, '.' or '-', starting with a letter or digit");
        }
        return normalized;
    }

    public static class InvalidTickerException extends IllegalArgumentException {

        public InvalidTickerException(String message) {
            super(message);
        }
    }
}
