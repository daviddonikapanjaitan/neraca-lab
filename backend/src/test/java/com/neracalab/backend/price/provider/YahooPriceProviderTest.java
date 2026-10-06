package com.neracalab.backend.price.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.company.Exchange;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Parsing of a real Yahoo chart response (HRTA.JK around the Rp40 dividend ex 2026-06-12, holiday 2026-06-16). */
class YahooPriceProviderTest {

    @Test
    void symbolAddsTheExchangeSuffix() {
        assertThat(YahooPriceProvider.symbol(Exchange.IDX, "HRTA")).isEqualTo("HRTA.JK");
        assertThat(EodhdPriceProvider.symbol(Exchange.IDX, "HRTA")).isEqualTo("HRTA.JK");
    }

    @Test
    void parsesBarsInTheExchangeTimeZone() throws IOException {
        JsonNode result = JsonMapper.builder().build().readTree(resource()).path("chart").path("result").path(0);

        // fallback zone UTC is ignored: meta.exchangeTimezoneName (Asia/Jakarta) wins
        PriceHistory history = YahooPriceProvider.history("HRTA.JK", result, ZoneId.of("UTC"));

        assertThat(history.symbol()).isEqualTo("HRTA.JK");
        assertThat(history.currency()).isEqualTo("IDR");
        // timestamps are the 09:00 WIB session open = 02:00 UTC, so the date is the WIB calendar day
        assertThat(history.bars()).extracting(DailyBar::date).containsExactly(
                LocalDate.of(2026, 6, 10), LocalDate.of(2026, 6, 11), LocalDate.of(2026, 6, 12),
                LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 16), LocalDate.of(2026, 6, 17));

        DailyBar first = history.bars().getFirst();
        assertThat(first.open()).isEqualByComparingTo("2140");
        assertThat(first.high()).isEqualByComparingTo("2150");
        assertThat(first.low()).isEqualByComparingTo("2070");
        assertThat(first.close()).isEqualByComparingTo("2080");
        assertThat(first.adjustedClose()).isEqualTo(new BigDecimal("2033.5195"));   // 2033.51953125, rounded like V1.0.5
        assertThat(first.volume()).isEqualTo(11147100L);

        DailyBar holiday = history.bars().get(4);
        assertThat(holiday.open()).isNull();
        assertThat(holiday.close()).isNull();
        assertThat(holiday.adjustedClose()).isNull();
        assertThat(holiday.volume()).isNull();
    }

    @Test
    void readsRetryAfterSeconds() {
        assertThat(PacedHttpClient.retryAfter("120")).isEqualTo(Duration.ofSeconds(120));
        assertThat(PacedHttpClient.retryAfter("Wed, 21 Oct 2026 07:28:00 GMT")).isNull();
        assertThat(PacedHttpClient.retryAfter(null)).isNull();
    }

    @Test
    void anIdxListingTradesInRupiah() {
        assertThat(Exchange.IDX.currency()).isEqualTo("IDR");
    }

    private static String resource() throws IOException {
        try (InputStream in = YahooPriceProviderTest.class
                .getResourceAsStream("/price/yahoo_HRTA.JK_2026-06-10_2026-06-17.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
