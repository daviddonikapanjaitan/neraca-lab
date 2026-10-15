package com.neracalab.backend.macro;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Optional evening run ({@code neracalab.macro.schedule.enabled=true}): "Update all macro data" - every series with a
 * source whose latest observation is older than the one that should be public by now is queued. A monthly or weekly
 * series is therefore asked only once its next release is due.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "neracalab.macro.schedule", name = "enabled", havingValue = "true")
public class MacroSchedule {

    private static final Logger log = LoggerFactory.getLogger(MacroSchedule.class);

    private final MacroService macro;

    public MacroSchedule(MacroService macro) {
        this.macro = macro;
    }

    @Scheduled(cron = "${neracalab.macro.schedule.cron:0 0 20 * * *}", zone = "${neracalab.macro.schedule.zone:Asia/Jakarta}")
    public void updateAll() {
        MacroService.Update update = macro.updateAll(null);
        log.info("Scheduled macro update: {} series, {} queued, {} already active, {} up to date, {} manual",
                update.series(), update.queued(), update.alreadyActive(), update.upToDate(), update.manual());
    }
}
