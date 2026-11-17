package com.neracalab.backend.portfolio.performance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import com.neracalab.backend.portfolio.performance.PerformanceService.Capture;

/**
 * Every 15 minutes from 17:00 to 23:45 WIB on weekdays ({@code neracalab.performance.schedule.cron}), every portfolio
 * without a snapshot of today gets one ({@link PerformanceService#captureScheduled}): once a day, at or after the end
 * of the IDX session, and later that evening when the backend was down at 17:00. An exchange holiday (no IHSG bar and
 * no intraday session of the day) saves nothing. On by default ({@code neracalab.performance.schedule.enabled}).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "neracalab.performance.schedule", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class PerformanceSchedule {

    private static final Logger log = LoggerFactory.getLogger(PerformanceSchedule.class);

    private final PerformanceService service;

    public PerformanceSchedule(PerformanceService service) {
        this.service = service;
    }

    @Scheduled(cron = "${neracalab.performance.schedule.cron:0 0/15 17-23 * * MON-FRI}",
               zone = "${neracalab.performance.schedule.zone:Asia/Jakarta}")
    public void save() {
        for (long userId : service.portfolioUsers()) {
            try {
                Capture c = service.captureScheduled(userId);
                if (c != null && c.outcome() != PerformanceService.Outcome.SAVED) {
                    log.info("Performance snapshot of user {}: {}", userId, c.message());
                }
            } catch (RuntimeException e) {
                // one portfolio never stops the others; the next round tries again
                log.warn("Performance snapshot of user {} failed: {}", userId, e.toString());
            }
        }
    }
}
