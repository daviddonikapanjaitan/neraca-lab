package com.neracalab.backend.price;

import java.time.LocalDate;
import java.util.Optional;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.price.PriceIngestionService.Result;

/**
 * Daily bars of an exchange's index (IHSG = Yahoo Finance {@code ^JKSE} on IDX) into {@code index_daily}. The price
 * queue runs it like the prices of a company (same workers, pacing and HTTP 429 back-off); the technical analysis
 * implements it, as it owns the index data.
 */
public interface IndexIngestion {

    /**
     * An index that can be ingested.
     *
     * @param code     code in {@code index_daily} and the API (IHSG); the ticker of its jobs
     * @param symbol   symbol at the source (^JKSE)
     * @param name     display name
     * @param exchange exchange it belongs to
     */
    record IndexRef(String code, String symbol, String name, Exchange exchange) {
    }

    /**
     * What is stored of an index.
     *
     * @param rows      stored daily bars
     * @param firstDate first stored day ({@code null}: none)
     * @param lastDate  latest stored day ({@code null}: none)
     */
    record Stored(int rows, LocalDate firstDate, LocalDate lastDate) {
    }

    /** The index of an exchange (empty: the exchange has none configured). */
    Optional<IndexRef> index(Exchange exchange);

    Stored stored(IndexRef index);

    /** Source of the index bars (the price queue reports it in the job's stage). */
    String sourceName();

    /**
     * Fetches the bars from the latest stored day to the last completed trading day (no request when that day is
     * stored already), or the whole history ({@code full}, or nothing stored yet), and upserts them.
     */
    Result ingest(IndexRef index, boolean full);
}
