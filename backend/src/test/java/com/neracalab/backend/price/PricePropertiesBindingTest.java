package com.neracalab.backend.price;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * {@code neracalab.prices.*} binds through the record's canonical constructor ({@code @ConstructorBinding}): a second
 * constructor without {@code throughput} (for tests) left Spring without a constructor to bind and the backend did not
 * start on 2026-11-05 ("No default constructor found").
 */
class PricePropertiesBindingTest {

    private static PriceProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("neracalab.prices", PriceProperties.class);
    }

    @Test
    void theDefaultsBind() {
        PriceProperties p = bind(Map.of());

        assertThat(p.minDelay()).isEqualTo(Duration.ofSeconds(1));
        assertThat(p.throughput()).isEqualTo(PriceProperties.Throughput.DEFAULTS);
        assertThat(p.throughput().concurrentRequests()).isEqualTo(8);
        assertThat(p.throughput().requestSpacing()).isEqualTo(Duration.ofMillis(150));
    }

    @Test
    void thePaceIsReadFromTheConfiguration() {
        PriceProperties p = bind(Map.of("neracalab.prices.throughput.concurrent-requests", "1",
                "neracalab.prices.throughput.request-spacing", "250ms",
                "neracalab.prices.throughput.throttled-for", "10m",
                "neracalab.prices.throughput.no-data-retry-after", "3d"));

        assertThat(p.throughput()).isEqualTo(new PriceProperties.Throughput(1, Duration.ofMillis(250),
                Duration.ofMinutes(10), Duration.ofDays(3)));
    }
}
