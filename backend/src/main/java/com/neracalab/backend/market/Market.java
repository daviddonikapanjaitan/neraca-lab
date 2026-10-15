package com.neracalab.backend.market;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Markets beside the stock exchanges (Ingestion › Price Ingestion, Companies › Technicals): Forex pairs and
 * commodities ({@code market_instrument.market}). Their codes never collide with an {@link
 * com.neracalab.backend.company.Exchange} code, so every API that takes an exchange also takes a market.
 */
public enum Market {

    FOREX("Forex", "Currency", "Currencies", ZoneId.of("Europe/London")),
    COMMODITY("Commodities", "Commodity", "Commodities", ZoneId.of("America/New_York"));

    private final String displayName;
    private final String itemLabel;
    private final String itemsLabel;
    private final ZoneId zone;

    Market(String displayName, String itemLabel, String itemsLabel, ZoneId zone) {
        this.displayName = displayName;
        this.itemLabel = itemLabel;
        this.itemsLabel = itemsLabel;
        this.zone = zone;
    }

    public String code() {
        return name();
    }

    /** "Forex", "Commodities" */
    public String displayName() {
        return displayName;
    }

    /** What one instrument is called in a dropdown: "Currency", "Commodity". */
    public String itemLabel() {
        return itemLabel;
    }

    public String itemsLabel() {
        return itemsLabel;
    }

    /**
     * Time zone the source dates the daily bars in (Yahoo Finance: London for currency pairs, New York for the futures).
     */
    public ZoneId zone() {
        return zone;
    }

    /**
     * The latest day whose daily bar is final at {@code today} (a date in {@link #zone()}): the weekday before today.
     * Currencies and futures trade around the clock, so today's bar is still forming until the day is over; a Saturday,
     * Sunday or Monday steps back to Friday.
     */
    public static LocalDate lastCompletedDay(LocalDate today) {
        LocalDate day = today.minusDays(1);
        while (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
            day = day.minusDays(1);
        }
        return day;
    }

    /**
     * The latest month whose monthly average is final at {@code today}: the 1st of the month before (the World Bank
     * publishes a month early in the next one; until then the previous month is the latest there is).
     */
    public static LocalDate lastCompletedMonth(LocalDate today) {
        return today.withDayOfMonth(1).minusMonths(1);
    }

    /** {@code "forex"} -> FOREX; also "commodities". Empty for an exchange code or anything else. */
    public static Optional<Market> find(String code) {
        String c = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (c.equals("COMMODITIES")) {
            c = "COMMODITY";
        }
        String normalized = c;
        return Arrays.stream(values()).filter(m -> m.name().equals(normalized)).findFirst();
    }

    /** Resolves a market code or fails with the supported ones. */
    public static Market of(String code) {
        return find(code).orElseThrow(() -> new UnsupportedMarketException(code));
    }

    public static boolean isMarket(String code) {
        return find(code).isPresent();
    }

    public static List<String> codes() {
        return Arrays.stream(values()).map(Market::code).toList();
    }

    /** The requested code is not a market. */
    public static class UnsupportedMarketException extends RuntimeException {

        UnsupportedMarketException(String code) {
            super("Unsupported market '" + code + "'; supported: " + String.join(", ", codes()));
        }
    }
}
