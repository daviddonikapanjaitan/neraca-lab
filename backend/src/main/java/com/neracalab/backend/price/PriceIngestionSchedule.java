package com.neracalab.backend.price;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.price.PriceDailyRepository.CompanyRef;

/**
 * Optional evening run ({@code neracalab.prices.schedule.enabled=true}): queues every active company
 * of the configured exchange; the queue then fetches them one by one with the usual pacing
 * (about 15-30 minutes for 900 tickers).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "neracalab.prices.schedule", name = "enabled", havingValue = "true")
public class PriceIngestionSchedule {

    private static final Logger log = LoggerFactory.getLogger(PriceIngestionSchedule.class);

    private final PriceIngestionQueue queue;
    private final PriceDailyRepository prices;
    private final PriceProperties properties;

    public PriceIngestionSchedule(PriceIngestionQueue queue, PriceDailyRepository prices, PriceProperties properties) {
        this.queue = queue;
        this.prices = prices;
        this.properties = properties;
    }

    @Scheduled(cron = "${neracalab.prices.schedule.cron:0 30 17 * * MON-FRI}",
               zone = "${neracalab.prices.schedule.zone:Asia/Jakarta}")
    public void queueAllActiveCompanies() {
        Exchange exchange = Exchange.of(properties.schedule().exchange());
        List<CompanyRef> companies = prices.activeCompanies(exchange);
        long created = companies.stream().filter(c -> queue.submit(c, false).created()).count();
        log.info("Scheduled price ingestion: {} active {} companies, {} queued (others already active)",
                companies.size(), exchange.code(), created);
    }
}
