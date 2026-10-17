package com.neracalab.backend.technical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.price.IndexIngestion.IndexRef;
import com.neracalab.backend.price.PriceIngestionService.Result;
import com.neracalab.backend.price.TestPriceProperties;
import com.neracalab.backend.technical.engine.Candle;

/**
 * The IHSG ingestion: from the latest stored day to the last completed trading day only, no request when that day is
 * stored, the whole history the first time, never an unfinished bar of today. Yahoo Finance and the database are mocks.
 */
class IndexIngestionServiceTest {

    private static final ZoneId JAKARTA = ZoneId.of("Asia/Jakarta");

    private final TechnicalRepository repository = mock(TechnicalRepository.class);
    private final YahooChartClient yahoo = mock(YahooChartClient.class);

    /** at {@code time} on 2026-10-15 (Thursday) in Jakarta; the session close cutoff is 17:00 */
    private IndexIngestionService service(String time) {
        Clock clock = Clock.fixed(ZonedDateTime.of(LocalDate.of(2026, 10, 15), java.time.LocalTime.parse(time), JAKARTA)
                .toInstant(), JAKARTA);
        return new IndexIngestionService(repository, TechnicalTestData.properties(), yahoo, TestPriceProperties.defaults(),
                clock);
    }

    private static Candle bar(String date, double close) {
        return Candle.daily(LocalDate.parse(date), close, close + 10, close - 10, close, close, 1_000_000);
    }

    private IndexRef ihsg(IndexIngestionService service) {
        return service.index(Exchange.IDX).orElseThrow();
    }

    @Test
    void fetchesFromTheLatestStoredDayToTheLastCompletedDay() {
        IndexIngestionService service = service("18:00");
        when(repository.latestIndexDate("IHSG")).thenReturn(Optional.of(LocalDate.of(2026, 10, 9)),
                Optional.of(LocalDate.of(2026, 10, 15)));
        when(yahoo.dailyBars(eq("^JKSE"), any(), eq(LocalDate.of(2026, 10, 9)), eq(LocalDate.of(2026, 10, 15))))
                .thenReturn(List.of(bar("2026-10-09", 7000), bar("2026-10-12", 7010), bar("2026-10-13", 7020),
                        bar("2026-10-14", 7030), bar("2026-10-15", 7040)));
        when(repository.indexDates(eq("IHSG"), any(), any())).thenReturn(Set.of(LocalDate.of(2026, 10, 9)));
        when(repository.upsertIndexBars(eq("IHSG"), eq("IDX"), eq("yahoo"), anyList())).thenReturn(4);

        Result result = service.ingest(ihsg(service), false);

        assertThat(result.requests()).isEqualTo(1);
        assertThat(result.requestedFrom()).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(result.fullHistory()).isFalse();
        assertThat(result.barsReceived()).isEqualTo(5);
        assertThat(result.inserted()).isEqualTo(4);
        assertThat(result.updated()).isZero();
        assertThat(result.unchanged()).isEqualTo(1);
        assertThat(result.latestTradingDate()).isEqualTo(LocalDate.of(2026, 10, 15));
    }

    @Test
    void beforeTheCutoffTodaysBarIsNotStored() {
        IndexIngestionService service = service("10:30");
        when(repository.latestIndexDate("IHSG")).thenReturn(Optional.of(LocalDate.of(2026, 10, 13)));
        when(yahoo.dailyBars(eq("^JKSE"), any(), any(), any()))
                .thenReturn(List.of(bar("2026-10-13", 7020), bar("2026-10-14", 7030), bar("2026-10-15", 7035)));
        when(repository.indexDates(eq("IHSG"), any(), any())).thenReturn(Set.of(LocalDate.of(2026, 10, 13)));
        when(repository.upsertIndexBars(eq("IHSG"), eq("IDX"), eq("yahoo"), anyList())).thenAnswer(inv -> {
            List<Candle> bars = inv.getArgument(3);
            assertThat(bars).extracting(Candle::date).containsExactly(LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 14));
            return 1;
        });

        Result result = service.ingest(ihsg(service), false);

        assertThat(result.barsSkipped()).isEqualTo(1);     // the unfinished bar of 15 Oct
        assertThat(result.inserted()).isEqualTo(1);
    }

    @Test
    void upToDateMakesNoRequest() {
        IndexIngestionService service = service("10:30");     // the last completed day is 14 Oct
        when(repository.latestIndexDate("IHSG")).thenReturn(Optional.of(LocalDate.of(2026, 10, 14)));
        when(repository.latestIndexDate("LQ45")).thenReturn(Optional.of(LocalDate.of(2026, 10, 14)));

        Result result = service.ingest(ihsg(service), false);

        assertThat(result.requests()).isZero();
        assertThat(result.latestTradingDate()).isEqualTo(LocalDate.of(2026, 10, 14));
        verify(yahoo, never()).dailyBars(any(), any(), any(), any());
    }

    @Test
    void theFirstRunAndFullFetchTheWholeHistory() {
        IndexIngestionService service = service("18:00");
        when(repository.latestIndexDate("IHSG")).thenReturn(Optional.empty());
        when(yahoo.dailyBars(eq("^JKSE"), any(), eq(LocalDate.of(1990, 1, 1)), any())).thenReturn(List.of());

        Result first = service.ingest(ihsg(service), false);
        assertThat(first.fullHistory()).isTrue();
        assertThat(first.requestedFrom()).isEqualTo(LocalDate.of(1990, 1, 1));

        when(repository.latestIndexDate("IHSG")).thenReturn(Optional.of(LocalDate.of(2026, 10, 15)));
        Result full = service.ingest(ihsg(service), true);
        assertThat(full.fullHistory()).isTrue();
        assertThat(full.requests()).isEqualTo(1);
    }

    /**
     * LQ45 (the Macro page) is stored in the same run as IHSG, from its own latest day; its failure is logged and the
     * IHSG result stands.
     */
    @Test
    void theOtherIndexesOfTheExchangeComeWithIt() {
        IndexIngestionService service = service("18:00");
        when(repository.latestIndexDate("IHSG")).thenReturn(Optional.of(LocalDate.of(2026, 10, 15)));
        when(repository.latestIndexDate("LQ45")).thenReturn(Optional.of(LocalDate.of(2026, 10, 13)));
        when(yahoo.dailyBars(eq("^JKLQ45"), any(), eq(LocalDate.of(2026, 10, 13)), eq(LocalDate.of(2026, 10, 15))))
                .thenReturn(List.of(bar("2026-10-13", 600), bar("2026-10-14", 590), bar("2026-10-15", 585)));
        when(repository.indexDates(eq("LQ45"), any(), any())).thenReturn(Set.of(LocalDate.of(2026, 10, 13)));
        when(repository.upsertIndexBars(eq("LQ45"), eq("IDX"), eq("yahoo"), anyList())).thenReturn(2);

        Result result = service.ingest(ihsg(service), false);

        assertThat(result.requests()).as("IHSG was up to date").isZero();
        verify(repository).upsertIndexBars(eq("LQ45"), eq("IDX"), eq("yahoo"), anyList());

        when(repository.latestIndexDate("LQ45")).thenReturn(Optional.empty());
        when(yahoo.dailyBars(eq("^JKLQ45"), any(), eq(LocalDate.of(1997, 1, 1)), any()))
                .thenThrow(new com.neracalab.backend.price.provider.PriceProviderException("HTTP 500"));
        assertThat(service.ingest(ihsg(service), false).latestTradingDate()).isEqualTo(LocalDate.of(2026, 10, 15));
    }

    /** Up to date only when IHSG and LQ45 both reach the last completed day: a missing LQ45 queues the index job. */
    @Test
    void upToDateCoversTheOtherIndexes() {
        IndexIngestionService service = service("18:00");
        LocalDate day = LocalDate.of(2026, 10, 15);
        when(repository.indexStats("IHSG")).thenReturn(new TechnicalRepository.IndexStats(9000, LocalDate.of(1990, 1, 2), day));
        when(repository.indexStats("LQ45")).thenReturn(new TechnicalRepository.IndexStats(0, null, null));
        assertThat(service.upToDate(ihsg(service), day)).isFalse();
        when(repository.indexStats("LQ45")).thenReturn(new TechnicalRepository.IndexStats(7000, LocalDate.of(1997, 1, 2), day));
        assertThat(service.upToDate(ihsg(service), day)).isTrue();
        assertThat(service.upToDate(ihsg(service), day.plusDays(1))).isFalse();
        assertThat(service.companions(ihsg(service))).singleElement().satisfies(lq45 -> {
            assertThat(lq45.code()).isEqualTo("LQ45");
            assertThat(lq45.symbol()).isEqualTo("^JKLQ45");
            assertThat(lq45.name()).isEqualTo("IDX LQ45 Index (LQ45)");
            assertThat(lq45.exchange()).isEqualTo(Exchange.IDX);
        });
    }

    @Test
    void theIndexBelongsToIdxOnly() {
        IndexIngestionService service = service("18:00");
        IndexRef ref = ihsg(service);
        assertThat(ref.code()).isEqualTo("IHSG");
        assertThat(ref.symbol()).isEqualTo("^JKSE");
        assertThat(ref.name()).isEqualTo("IDX Composite Index (IHSG)");
        assertThat(java.util.Arrays.stream(Exchange.values()).filter(e -> e != Exchange.IDX)
                .map(service::index)).allMatch(Optional::isEmpty);
    }
}
