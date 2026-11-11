package com.neracalab.backend.portfolio.intraday;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Every few minutes on IDX trading days ({@code neracalab.intraday.schedule.cron}, default every 5 minutes from 09:00,
 * Asia/Jakarta), the intraday prices of the stocks held in the portfolios are brought up to date
 * ({@link IntradayService#refreshScheduled}); outside 09:00 - 16:20 WIB nothing is fetched. On by default
 * ({@code neracalab.intraday.schedule.enabled}).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "neracalab.intraday.schedule", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class IntradaySchedule {

    private static final Logger log = LoggerFactory.getLogger(IntradaySchedule.class);

    private final IntradayService service;

    public IntradaySchedule(IntradayService service) {
        this.service = service;
    }

    @Scheduled(cron = "${neracalab.intraday.schedule.cron:0 */5 9-16 * * MON-FRI}",
               zone = "${neracalab.intraday.schedule.zone:Asia/Jakarta}")
    public void refresh() {
        try {
            service.refreshScheduled();
        } catch (RuntimeException e) {
            // the next round tries again
            log.warn("Intraday refresh failed: {}", e.toString());
        }
    }
}
