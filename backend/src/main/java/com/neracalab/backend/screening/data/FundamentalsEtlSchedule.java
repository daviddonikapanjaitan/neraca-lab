package com.neracalab.backend.screening.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.screening.ScreeningProperties;

/**
 * The daily screening data ETL ({@code neracalab.screening.etl.schedule.enabled}, on by default):
 * after the market close it queues one ETL run of the configured exchange. A run refreshes the market
 * data of every listing and the fundamentals that are due (each stock about once a week), so it
 * usually takes a few minutes; the first run loads every stock (about 45 minutes for IDX).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "neracalab.screening.etl.schedule", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class FundamentalsEtlSchedule {

    private static final Logger log = LoggerFactory.getLogger(FundamentalsEtlSchedule.class);

    private final FundamentalsQueue queue;
    private final ScreeningProperties properties;

    public FundamentalsEtlSchedule(FundamentalsQueue queue, ScreeningProperties properties) {
        this.queue = queue;
        this.properties = properties;
    }

    @Scheduled(cron = "${neracalab.screening.etl.schedule.cron:0 0 18 * * MON-FRI}",
               zone = "${neracalab.screening.etl.schedule.zone:Asia/Jakarta}")
    public void dailyRun() {
        Exchange exchange = Exchange.of(properties.etl().schedule().exchange());
        boolean created = queue.submit(exchange, false, null).created();
        log.info("Scheduled screening data ETL for {}: {}", exchange.code(), created ? "queued" : "already active");
    }
}
