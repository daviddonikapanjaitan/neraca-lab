package com.neracalab.backend.price;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.job.Requester;
import com.neracalab.backend.price.PriceDailyRepository.CompanyRef;

/**
 * Queues the daily prices of a company whose financial statements were just stored. A filing holds no
 * prices, and without them a company has no market cap and no valuation (DSNG, ELSA, ICBP, JPFA and SIDO
 * had five years of statements and an empty valuation history until their prices were requested by hand).
 * <p>
 * Nothing is queued when the stored prices already reach the last completed trading day, and a company
 * with an active price job keeps that job, so five filings of one company uploaded together fetch its
 * prices once. The price job ends with the valuation refresh of the company
 * ({@link PriceIngestionService#ingest}). Switch off with {@code neracalab.ingestion.prices-after-upload: false}.
 */
@Component
public class PricesAfterUpload {

    private static final Logger log = LoggerFactory.getLogger(PricesAfterUpload.class);

    private final PriceIngestionQueue queue;
    private final PriceIngestionService service;
    private final PriceDailyRepository prices;
    private final boolean enabled;

    public PricesAfterUpload(PriceIngestionQueue queue, PriceIngestionService service, PriceDailyRepository prices,
                             @Value("${neracalab.ingestion.prices-after-upload:true}") boolean enabled) {
        this.queue = queue;
        this.service = service;
        this.prices = prices;
        this.enabled = enabled;
    }

    /**
     * @param requestedBy the user who uploaded the filing; recorded as the requester of the price job
     * @return the price job that will load the company's prices (a new one, or the one already active);
     *         empty when switched off, the company is unknown or its prices are up to date
     */
    public Optional<UUID> queue(Exchange exchange, String ticker, Requester requestedBy) {
        if (!enabled || ticker == null || ticker.isBlank()) {
            return Optional.empty();
        }
        Optional<CompanyRef> company = prices.company(exchange, ticker);
        if (company.isEmpty() || service.upToDate(company.get())) {
            return Optional.empty();
        }
        PriceIngestionQueue.Submission submission = queue.submit(company.get(), false, requestedBy);
        if (submission.created()) {
            log.info("price job {} queued for {} {} after its filing was stored", submission.job().id(),
                    exchange.code(), ticker);
        }
        return Optional.of(submission.job().id());
    }
}
