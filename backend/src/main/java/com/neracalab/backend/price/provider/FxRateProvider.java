package com.neracalab.backend.price.provider;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Source of daily exchange rates, used to convert listing prices into the reporting currency of a
 * company that reports in another currency than its listing trades in (INDY: IDR listing, USD
 * reports). Separate from {@link PriceProvider}: the price providers' FX histories are not reliable
 * enough (Yahoo's USDIDR=X has days off by a factor of 10 and weeks of a stale value).
 */
public interface FxRateProvider {

    /** Stored in fx_rate_daily.source, e.g. {@code ecb}. */
    String name();

    /**
     * Daily reference rates of {@code base}/{@code quote} from {@code from} to {@code to} (both inclusive),
     * oldest first: {@code quote} units per 1 {@code base} unit (USD/IDR: about 17,900). Days without a
     * rate (weekends, holidays) are absent. One HTTP request.
     *
     * @throws RateLimitedException    the source answered HTTP 429
     * @throws SymbolNotFoundException the source does not know a currency
     * @throws PriceProviderException  any other failure
     */
    List<FxRate> fetch(String base, String quote, LocalDate from, LocalDate to);

    /** One daily rate: {@code rate} quote units per 1 base unit on {@code date}. */
    record FxRate(LocalDate date, BigDecimal rate) {
    }
}
