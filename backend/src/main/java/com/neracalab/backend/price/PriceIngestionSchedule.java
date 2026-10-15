package com.neracalab.backend.price;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import com.neracalab.backend.company.Exchange;

/**
 * Optional evening run ({@code neracalab.prices.schedule.enabled=true}): brings the configured exchange up to date like
 * "Update all companies" on Ingestion › Price Ingestion ({@link PriceUpdateService#updateAll}): every active company
 * whose prices end before the last completed trading day is queued, and the exchange's index (IHSG); the queue then
 * fetches them one by one with the usual pacing (about 15-30 minutes for 900 tickers).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "neracalab.prices.schedule", name = "enabled", havingValue = "true")
public class PriceIngestionSchedule {

    private static final Logger log = LoggerFactory.getLogger(PriceIngestionSchedule.class);

    private final PriceUpdateService updates;
    private final PriceProperties properties;

    public PriceIngestionSchedule(PriceUpdateService updates, PriceProperties properties) {
        this.updates = updates;
        this.properties = properties;
    }

    @Scheduled(cron = "${neracalab.prices.schedule.cron:0 30 17 * * MON-FRI}",
               zone = "${neracalab.prices.schedule.zone:Asia/Jakarta}")
    public void queueAllActiveCompanies() {
        PriceUpdateService.Update update = updates.updateAll(Exchange.of(properties.schedule().exchange()), true, null);
        log.info("Scheduled price ingestion: {} active {} companies, {} queued, {} already active, {} up to date",
                update.companies(), update.exchange(), update.queued(), update.alreadyActive(), update.upToDate());
    }
}
