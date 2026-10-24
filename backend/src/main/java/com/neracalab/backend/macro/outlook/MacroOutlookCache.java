package com.neracalab.backend.macro.outlook;

import java.time.LocalDate;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.macro.MacroProperties;
import com.neracalab.backend.market.MarketDataChanged;

import tools.jackson.databind.json.JsonMapper;

/**
 * The Macro page's outlook JSON kept in Redis ({@code neracalab.macro.outlook-cache}): computing it reads every index
 * bar, macro series, commodity and currency pair (about half a second); a cached one is returned as stored.
 * <p>
 * Key: {@code neracalab:macro-outlook:<instance>:<generation>:<country>:<index>:<range>:<day>}. The generation is a
 * Redis counter that every write of the data read ({@link MarketDataChanged}, after its commit) and every start (the
 * SQL scripts reload the catalogs) count up, so no stale outlook is read again; the day (the exchange's) starts a new
 * outlook at midnight, since the outlook is as of today. The instance (one per start) keeps two backends of different
 * versions sharing the Redis apart. The generation is read before the outlook is computed: one computed while the data
 * changed is stored under the old generation, which is not read any more, and expires after the ttl.
 * <p>
 * Redis unreachable: the outlook is computed for every request (logged).
 */
@Component
public class MacroOutlookCache {

    private static final Logger log = LoggerFactory.getLogger(MacroOutlookCache.class);

    static final String PREFIX = "neracalab:macro-outlook:";
    static final String GENERATION = PREFIX + "generation";

    /** The outlook JSON and whether it came from Redis. */
    public record Outlook(String json, boolean cached) {
    }

    private final MacroOutlookService service;
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final MacroProperties.OutlookCache properties;
    private final String instance = UUID.randomUUID().toString().substring(0, 8);

    public MacroOutlookCache(MacroOutlookService service, StringRedisTemplate redis, JsonMapper json,
                             MacroProperties properties) {
        this.service = service;
        this.redis = redis;
        this.json = json;
        this.properties = properties.outlookCache();
    }

    /**
     * {@link MacroOutlookService#outlook} as JSON, from Redis when stored.
     *
     * @throws IllegalArgumentException an unknown or not yet available country, an unknown index or range
     */
    public Outlook outlook(String countryCode, String indexCode, String range) {
        MacroOutlookService.Choice choice = service.choice(countryCode, indexCode);
        String r = MacroOutlookService.range(range);
        LocalDate today = LocalDate.now(Exchange.of(choice.exchange()).zone());
        String key = properties.enabled() ? key(choice, r, today) : null;
        if (key != null) {
            try {
                String stored = redis.opsForValue().get(key);
                if (stored != null) {
                    return new Outlook(stored, true);
                }
            } catch (RuntimeException e) {
                log.warn("Macro outlook cache: Redis read failed ({}); computing the outlook", e.getMessage());
                key = null;
            }
        }
        String out = json.writeValueAsString(service.outlook(choice.country(), choice.index(), r, today));
        if (key != null) {
            try {
                redis.opsForValue().set(key, out, properties.ttl());
            } catch (RuntimeException e) {
                log.warn("Macro outlook cache: Redis write failed ({})", e.getMessage());
            }
        }
        return new Outlook(out, false);
    }

    /** The key of an outlook; null when the generation cannot be read (Redis unreachable: nothing is cached). */
    private String key(MacroOutlookService.Choice choice, String range, LocalDate today) {
        String generation;
        try {
            generation = redis.opsForValue().get(GENERATION);
        } catch (RuntimeException e) {
            log.warn("Macro outlook cache: Redis read failed ({}); computing the outlook", e.getMessage());
            return null;
        }
        return PREFIX + instance + ":" + (generation == null ? "0" : generation) + ":" + choice.country() + ":"
                + choice.index() + ":" + range + ":" + today;
    }

    /** Index bars, macro series, commodity or currency bars written: every stored outlook is stale. */
    @TransactionalEventListener(fallbackExecution = true)
    public void dataChanged(MarketDataChanged event) {
        clear(event.table() + " changed");
    }

    /** Started: the SQL scripts ran (catalogs, seed data) and the code may be new. */
    @EventListener(ApplicationReadyEvent.class)
    public void started() {
        clear("start");
    }

    /** Counts the generation up: the stored outlooks are not read any more and expire after the ttl. */
    void clear(String reason) {
        try {
            redis.opsForValue().increment(GENERATION);
        } catch (RuntimeException e) {
            log.warn("Macro outlook cache: Redis unreachable, not cleared after {} ({}); stored outlooks expire within {}",
                    reason, e.getMessage(), properties.ttl());
        }
    }
}
