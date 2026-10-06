package com.neracalab.backend.company;

import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Stock exchanges the API serves. The enum name is the code stored in {@code company.exchange}.
 * <p>
 * To support another exchange (NYSE, NASDAQ, SSE, SZSE, HKEX, ...) add a constant here; the
 * company APIs accept it immediately. Its data still has to be loaded (the AI upload currently
 * reads IDX filings only).
 */
public enum Exchange {

    IDX("Indonesia Stock Exchange", "Indonesia", ZoneId.of("Asia/Jakarta"), "IDR");

    private final String displayName;
    private final String country;
    private final ZoneId zone;
    private final String currency;

    Exchange(String displayName, String country, ZoneId zone, String currency) {
        this.displayName = displayName;
        this.country = country;
        this.zone = zone;
        this.currency = currency;
    }

    public String code() {
        return name();
    }

    public String displayName() {
        return displayName;
    }

    public String country() {
        return country;
    }

    /** Time zone of the trading sessions; trading dates are calendar days in this zone. */
    public ZoneId zone() {
        return zone;
    }

    /**
     * ISO 4217 currency the listings trade in (IDX: IDR). A company may report in another currency
     * (e.g. INDY reports in USD); its prices are then converted, see PriceIngestionService.
     */
    public String currency() {
        return currency;
    }

    /** Resolves a code case-insensitively, e.g. {@code "idx"} -> {@link #IDX}. */
    public static Exchange of(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(e -> e.name().equals(normalized)).findFirst()
                .orElseThrow(() -> new UnsupportedExchangeException(code, codes()));
    }

    public static List<String> codes() {
        return Arrays.stream(values()).map(Exchange::code).toList();
    }

    /** The requested exchange code is not one of {@link Exchange#values()}. */
    public static class UnsupportedExchangeException extends RuntimeException {

        private final List<String> supported;

        UnsupportedExchangeException(String code, List<String> supported) {
            super("Unsupported exchange '" + code + "'; supported: " + String.join(", ", supported));
            this.supported = supported;
        }

        public List<String> supported() {
            return supported;
        }
    }
}
