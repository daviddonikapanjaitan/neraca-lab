package com.neracalab.backend.price.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.price.PriceIngestionService;
import com.neracalab.backend.price.TestPriceProperties;
import com.neracalab.backend.technical.YahooChartClient;
import com.neracalab.backend.technical.engine.Candle;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The close of a session Yahoo's daily chart has not written yet: real responses of 2026-11-03 03:30 WIB, when the
 * 2026-11-02 bar of every IDX listing and of ^JKSE still had {@code close = null} while the quote
 * ({@code meta.regularMarketPrice}, 16:14 WIB) held the session's close.
 */
class YahooSessionCloseTest {

    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void theLastBarWithoutACloseTakesTheSessionCloseOfTheQuote() throws IOException {
        PriceHistory history = YahooPriceProvider.history("TLKM.JK", result(TLKM), ZoneId.of("UTC"));

        assertThat(history.bars()).extracting(DailyBar::date)
                .containsExactly(LocalDate.of(2026, 10, 29), LocalDate.of(2026, 10, 30), MONDAY);
        DailyBar friday = history.bars().get(1);
        assertThat(friday.close()).isEqualByComparingTo("1980");
        assertThat(friday.adjustedClose()).isEqualByComparingTo("1980");

        DailyBar monday = history.bars().getLast();
        assertThat(monday.open()).isEqualByComparingTo("2000");
        assertThat(monday.high()).isEqualByComparingTo("2000");
        assertThat(monday.low()).isEqualByComparingTo("1935");
        assertThat(monday.close()).isEqualTo(new BigDecimal("1960.0000"));
        assertThat(monday.adjustedClose()).isEqualTo(new BigDecimal("1960.0000"));
        assertThat(monday.volume()).isEqualTo(209_433_900L);
    }

    @Test
    void theFilledBarIsStoredOnlyOnceItsSessionIsCompleted() throws IOException {
        List<DailyBar> bars = YahooPriceProvider.history("TLKM.JK", result(TLKM), WIB).bars();

        // after the 17:00 cutoff of Monday (or before it on Tuesday): Monday is the last completed trading day
        assertThat(PriceIngestionService.clean(bars, LocalDate.of(2026, 10, 30), MONDAY))
                .extracting(DailyBar::date).containsExactly(LocalDate.of(2026, 10, 30), MONDAY);
        // Monday before the cutoff: the session is still trading, its quote is not stored
        assertThat(PriceIngestionService.clean(bars, LocalDate.of(2026, 10, 30), LocalDate.of(2026, 10, 30)))
                .extracting(DailyBar::date).containsExactly(LocalDate.of(2026, 10, 30));
    }

    @Test
    void aQuoteOfAnotherDayIsNotTheBarsClose() throws IOException {
        ObjectNode result = (ObjectNode) result(TLKM);
        // the quote's last trade on Friday 30 October 16:14 WIB: Monday's bar stays without a close
        ((ObjectNode) result.path("meta")).put("regularMarketTime", 1793610880L - 3 * 86_400L);

        DailyBar monday = YahooPriceProvider.history("TLKM.JK", result, WIB).bars().getLast();

        assertThat(monday.date()).isEqualTo(MONDAY);
        assertThat(monday.close()).isNull();
        assertThat(monday.adjustedClose()).isNull();
    }

    @Test
    void aQuoteOutsideTheBarsRangeIsNotItsClose() throws IOException {
        JsonNode meta = result(TLKM).path("meta");
        BigDecimal high = new BigDecimal("2000.0000");
        BigDecimal low = new BigDecimal("1935.0000");

        assertThat(YahooSessionClose.of(meta, WIB, MONDAY, high, low)).get()
                .extracting(YahooSessionClose.Close::price).isEqualTo(new BigDecimal("1960.0000"));
        // 1960 above the bar's high / below its low
        assertThat(YahooSessionClose.of(meta, WIB, MONDAY, new BigDecimal("1950"), low)).isEmpty();
        assertThat(YahooSessionClose.of(meta, WIB, MONDAY, high, new BigDecimal("1970"))).isEmpty();
        // 1960 outside the quote's own day range
        ObjectNode outside = ((ObjectNode) meta.deepCopy()).put("regularMarketDayHigh", 1955.0);
        assertThat(YahooSessionClose.of(outside, WIB, MONDAY, null, null)).isEmpty();
    }

    @Test
    void noQuotePriceNoClose() throws IOException {
        ObjectNode meta = (ObjectNode) result(TLKM).path("meta").deepCopy();
        meta.remove("regularMarketPrice");
        assertThat(YahooSessionClose.of(meta, WIB, MONDAY, null, null)).isEmpty();
        meta.put("regularMarketPrice", 0.0);
        assertThat(YahooSessionClose.of(meta, WIB, MONDAY, null, null)).isEmpty();
        ObjectNode noTime = (ObjectNode) result(TLKM).path("meta").deepCopy();
        noTime.remove("regularMarketTime");
        assertThat(YahooSessionClose.of(noTime, WIB, MONDAY, null, null)).isEmpty();
    }

    @Test
    void aHolidayPlaceholderInsideTheHistoryIsStillDropped() throws IOException {
        // HRTA: the holiday 2026-06-16 is not the last bar, its quote is of 2026-06-17
        PriceHistory history = YahooPriceProvider.history("HRTA.JK",
                result("/price/yahoo_HRTA.JK_2026-06-10_2026-06-17.json"), WIB);

        DailyBar holiday = history.bars().get(4);
        assertThat(holiday.date()).isEqualTo(LocalDate.of(2026, 6, 16));
        assertThat(holiday.close()).isNull();
        assertThat(history.bars().getLast().close()).isNotNull();
    }

    @Test
    void theIndexTakesTheSessionCloseOnlyWhenAsked() throws IOException {
        YahooChartClient client = client(JKSE);

        List<Candle> index = client.dailyBars("^JKSE", WIB, LocalDate.of(2026, 10, 29), MONDAY, true);
        assertThat(index).extracting(Candle::date)
                .containsExactly(LocalDate.of(2026, 10, 29), LocalDate.of(2026, 10, 30), MONDAY);
        Candle monday = index.getLast();
        assertThat(monday.close()).isEqualTo(5096.83);
        assertThat(monday.open()).isEqualTo(5039.5391);
        assertThat(monday.high()).isEqualTo(5125.7388);
        assertThat(monday.low()).isEqualTo(4981.519);

        // forex, commodities and macro series (no session close cutoff of their own): the day is left out as before
        assertThat(client.dailyBars("^JKSE", WIB, LocalDate.of(2026, 10, 29), MONDAY)).extracting(Candle::date)
                .containsExactly(LocalDate.of(2026, 10, 29), LocalDate.of(2026, 10, 30));
    }

    /**
     * Yahoo's IDR=X of 2026-10-14 - 2026-11-05 as served on 2026-11-06: 2026-11-03 closes at 17,472.20 under its low of
     * 17,786.60, 2026-11-04 at 17,536.10 under 17,957.60 (ECB: 18,137 / 18,129). They are dropped and reported; the bar
     * of 15 Oct London time (stamped 2026-10-14 23:00 UTC: 17,823.70, 0.18 % under its low 17,856.40) and 29 Oct (0.13 %
     * under) stay - within the 1 % a Forex close may lie outside its day's range.
     */
    @Test
    void aForexCloseOutsideItsDaysRangeIsDropped() throws IOException {
        YahooChartClient.Bars bars = client(IDRX).dailyBarsChecked("IDR=X", ZoneId.of("Europe/London"),
                LocalDate.of(2026, 10, 14), LocalDate.of(2026, 11, 5));

        assertThat(bars.inconsistent()).containsExactly(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 4));
        assertThat(bars.bars()).extracting(Candle::date)
                .doesNotContain(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 4))
                .contains(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 29), LocalDate.of(2026, 11, 2),
                        LocalDate.of(2026, 11, 5));
        // the unchecked bars keep every day, as the index and macro series read them
        assertThat(client(IDRX).dailyBars("IDR=X", ZoneId.of("Europe/London"), LocalDate.of(2026, 10, 14),
                LocalDate.of(2026, 11, 5))).extracting(Candle::date).contains(LocalDate.of(2026, 11, 3));
    }

    /**
     * Yahoo's IDR=X of late June / early July 2006 sends open, high and low of 9.0 beside closes of 9,241, 9,266, 8,964,
     * 9,020 after 9,300: the range is broken there, not the close - no bar is dropped (the wick cleaning of
     * {@code BarCleaner} cuts the range).
     */
    @Test
    void aBrokenRangeKeepsItsClose() throws IOException {
        YahooChartClient.Bars bars = client(IDRX_2006).dailyBarsChecked("IDR=X", ZoneId.of("Europe/London"),
                LocalDate.of(2006, 6, 23), LocalDate.of(2006, 7, 6));

        assertThat(bars.inconsistent()).isEmpty();
        assertThat(bars.bars()).extracting(Candle::close).contains(9241.0, 9266.0, 8964.0, 9020.0);
    }

    private static final String IDRX_2006 = "/price/yahoo_IDRX_2006-06-23_2006-07-06_bad_range.json";
    private static final String IDRX = "/price/yahoo_IDRX_2026-10-14_2026-11-05_inconsistent.json";
    private static final String TLKM = "/price/yahoo_TLKM.JK_2026-10-29_2026-11-02_no_close.json";
    private static final String JKSE = "/price/yahoo_JKSE_2026-10-29_2026-11-02_no_close.json";

    private static YahooChartClient client(String resource) throws IOException {
        PacedHttpClient http = mock(PacedHttpClient.class);
        String body = read(resource);
        when(http.get(any())).thenAnswer(i -> new PacedHttpClient.Response(200, body, null));
        return new YahooChartClient(http, JSON, TestPriceProperties.defaults());
    }

    private static JsonNode result(String resource) throws IOException {
        return JSON.readTree(read(resource)).path("chart").path("result").path(0);
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = YahooSessionCloseTest.class.getResourceAsStream(resource)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
