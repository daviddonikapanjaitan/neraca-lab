package com.neracalab.backend.price;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.neracalab.backend.price.PriceIngestionService.Result;

/**
 * Bars of a Forex pair or a commodity ({@code market_instrument}) into {@code instrument_daily}. The price queue runs it
 * like the prices of a company (same workers, Yahoo Finance pacing and HTTP 429 back-off); the market package implements
 * it, as it owns the instrument data.
 */
public interface InstrumentIngestion {

    /**
     * An instrument that can be ingested.
     *
     * @param code      code in {@code instrument_daily} and the API (USDIDR, GOLD); the ticker of its jobs
     * @param market    FOREX or COMMODITY; the exchange column of its jobs
     * @param name      display name (USD/IDR, Gold)
     * @param symbol    symbol at the source (IDR=X; the World Bank column "Coal, Australian")
     * @param source    yahoo or worldbank
     * @param frequency DAILY or MONTHLY
     */
    record InstrumentRef(String code, String market, String name, String symbol, String source, String frequency) {

        public boolean monthly() {
            return "MONTHLY".equals(frequency);
        }
    }

    /**
     * What is stored of an instrument.
     *
     * @param rows      stored bars
     * @param firstDate first stored day ({@code null}: none)
     * @param lastDate  latest stored day ({@code null}: none)
     */
    record Stored(int rows, LocalDate firstDate, LocalDate lastDate) {
    }

    /** The active instruments of a market, in display order. */
    List<InstrumentRef> instruments(String market);

    Optional<InstrumentRef> instrument(String market, String code);

    Stored stored(InstrumentRef instrument);

    /** The latest bar date that is final now: the weekday before today, or the month before for a monthly instrument. */
    LocalDate lastCompleted(InstrumentRef instrument);

    /** Source of the bars (the price queue reports it in the job's stage): yahoo or worldbank. */
    String sourceName(InstrumentRef instrument);

    /**
     * Fetches the bars from the latest stored day to the last completed day (no request when that day is stored
     * already), or the whole history ({@code full}, or nothing stored yet), and upserts them.
     */
    Result ingest(InstrumentRef instrument, boolean full);
}
