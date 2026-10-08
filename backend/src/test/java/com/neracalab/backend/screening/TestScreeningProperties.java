package com.neracalab.backend.screening;

import java.time.Duration;
import java.util.List;

/** Screening properties with the defaults of application.yaml, for tests without a Spring context. */
public final class TestScreeningProperties {

    private TestScreeningProperties() {
    }

    public static ScreeningProperties create() {
        return create(0.45, "");
    }

    public static ScreeningProperties create(double budgetUsd, String tavilyKey) {
        return new ScreeningProperties(budgetUsd, Math.min(0.15, budgetUsd / 3), 3, 100, 50, 0.4, 0.2, 10e12, 1e12, 50,
                5e9, 1e9, 2e8, Duration.ofDays(10), true, List.of("EXCL"), 0.5,
                new ScreeningProperties.Llm("deepseek/deepseek-v4-flash-0731", "deepseek/deepseek-v4-flash-0731",
                        "anthropic/claude-opus-5.5", "low", "price", List.of("OpenInference"), 4, 3, 450, 12000, 2,
                        Duration.ZERO, null),
                new ScreeningProperties.News(tavilyKey, "https://api.tavily.com", 5, Duration.ofHours(12),
                        Duration.ofDays(120), 8, 1500, Duration.ofMillis(0), Duration.ofMillis(0)),
                new ScreeningProperties.Etl(Duration.ofDays(7),
                        new ScreeningProperties.Schedule(false, "0 0 18 * * MON-FRI", "Asia/Jakarta", "IDX")));
    }
}
