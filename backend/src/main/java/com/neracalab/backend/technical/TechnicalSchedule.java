package com.neracalab.backend.technical;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Nightly technical refresh ({@code neracalab.technical.schedule.enabled}, default on): after the evening price run,
 * fetch the new index bars and the corporate events that are due, then recompute and store the snapshot of every
 * active listing of the exchange. A refresh already running (started from the API) is left alone.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "neracalab.technical.schedule", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TechnicalSchedule {

    private static final Logger log = LoggerFactory.getLogger(TechnicalSchedule.class);

    private final TechnicalService service;
    private final TechnicalProperties properties;

    public TechnicalSchedule(TechnicalService service, TechnicalProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @Scheduled(cron = "${neracalab.technical.schedule.cron:0 0 19 * * MON-FRI}",
               zone = "${neracalab.technical.schedule.zone:Asia/Jakarta}")
    public void nightly() {
        var status = service.startRefresh(properties.schedule().exchange(), null, false);
        log.info("Scheduled technical refresh of {} started at {} (running: {})", status.exchange(), status.startedAt(),
                status.running());
    }
}
