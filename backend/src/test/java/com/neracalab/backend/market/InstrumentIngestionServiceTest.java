package com.neracalab.backend.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.neracalab.backend.market.InstrumentRepository.Instrument;
import com.neracalab.backend.price.InstrumentIngestion.InstrumentRef;
import com.neracalab.backend.price.PriceIngestionService.Result;
import com.neracalab.backend.technical.YahooChartClient;
import com.neracalab.backend.technical.engine.Candle;

/**
 * The ingestion of a Forex pair or commodity: the missing days only (with an overlap for the cleaning), no request when
 * the last completed day is stored, never today's unfinished bar, bad prints dropped and deleted; coal monthly from the
 * World Bank up to the month before. Yahoo Finance, the World Bank and the database are mocks.
 */
class InstrumentIngestionServiceTest {

    private final InstrumentRepository repository = mock(InstrumentRepository.class);
    private final YahooChartClient yahoo = mock(YahooChartClient.class);
    private final WorldBankPinkSheetClient worldBank = mock(WorldBankPinkSheetClient.class);
    /** Friday 2026-10-16, 08:00 UTC: 09:00 in London, 04:00 in New York */
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-16T08:00:00Z"), ZoneOffset.UTC);
    private final InstrumentIngestionService service = new InstrumentIngestionService(repository, yahoo, worldBank, clock);

    private static Instrument usdIdr(LocalDate last) {
        return new Instrument("USDIDR", 1, Market.FOREX, "USD/IDR", "yahoo", "IDR=X", "IDR", "IDR per USD", "DAILY",
                BigDecimal.ONE, new BigDecimal("0.03"), LocalDate.of(2001, 1, 1), null, last == null ? 0 : 100, null, last);
    }

    private static Instrument coal(LocalDate last) {
        return new Instrument("COAL", 5, Market.COMMODITY, "Coal (Newcastle)", "worldbank", "Coal, Australian", "USD",
                "USD per metric ton", "MONTHLY", new BigDecimal("0.01"), null, LocalDate.of(1979, 1, 1), null,
                last == null ? 0 : 500, null, last);
    }

    private static Candle bar(String date, double close) {
        return Candle.daily(LocalDate.parse(date), close, close + 10, close - 10, close, close, 0);
    }

    @Test
    void theLastCompletedDayIsTheWeekdayBeforeTodayInTheMarketsZone() {
        when(repository.instrument(Market.FOREX, "USDIDR")).thenReturn(Optional.of(usdIdr(null)));
        InstrumentRef ref = InstrumentIngestionService.ref(usdIdr(null));
        assertThat(service.lastCompleted(ref)).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(Market.lastCompletedDay(LocalDate.of(2026, 10, 19))).isEqualTo(LocalDate.of(2026, 10, 16));   // Monday
        assertThat(Market.lastCompletedDay(LocalDate.of(2026, 10, 18))).isEqualTo(LocalDate.of(2026, 10, 16));   // Sunday
        assertThat(Market.lastCompletedMonth(LocalDate.of(2026, 10, 16))).isEqualTo(LocalDate.of(2026, 9, 1));
    }

    @Test
    void upToDateMakesNoRequest() {
        when(repository.instrument(Market.FOREX, "USDIDR")).thenReturn(Optional.of(usdIdr(LocalDate.of(2026, 10, 15))));
        when(repository.latestDate("USDIDR")).thenReturn(Optional.of(LocalDate.of(2026, 10, 15)));
        Result result = service.ingest(InstrumentIngestionService.ref(usdIdr(null)), false);
        assertThat(result.requests()).isZero();
        verify(yahoo, never()).dailyBars(any(), any(), any(), any());
    }

    @Test
    void fetchesTheMissingDaysWithAnOverlapAndDropsBadPrints() {
        when(repository.instrument(Market.FOREX, "USDIDR")).thenReturn(Optional.of(usdIdr(LocalDate.of(2026, 10, 12))));
        when(repository.latestDate("USDIDR")).thenReturn(Optional.of(LocalDate.of(2026, 10, 12)),
                Optional.of(LocalDate.of(2026, 10, 15)));
        LocalDate from = LocalDate.of(2026, 9, 28);   // 14 days before the latest stored day
        when(yahoo.dailyBars(eq("IDR=X"), eq(Market.FOREX.zone()), eq(from), eq(LocalDate.of(2026, 10, 16))))
                .thenReturn(List.of(bar("2026-10-09", 17900), bar("2026-10-12", 17910), bar("2026-10-13", 15069.4),
                        bar("2026-10-14", 17920), bar("2026-10-15", 17930), bar("2026-10-16", 17950)));
        when(repository.dates(eq("USDIDR"), any(), any())).thenReturn(Set.of(LocalDate.of(2026, 10, 9),
                LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 13)));
        when(repository.delete("USDIDR", List.of(LocalDate.of(2026, 10, 13)))).thenReturn(1);
        when(repository.upsert(eq("USDIDR"), eq("yahoo"), anyList())).thenReturn(2);

        Result result = service.ingest(InstrumentIngestionService.ref(usdIdr(null)), false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Candle>> stored = ArgumentCaptor.forClass(List.class);
        verify(repository).upsert(eq("USDIDR"), eq("yahoo"), stored.capture());
        // today's bar (16 Oct, still forming) and the bad print are not stored
        assertThat(stored.getValue()).extracting(Candle::date).containsExactly(LocalDate.of(2026, 10, 9),
                LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 15));
        assertThat(result.requestedFrom()).isEqualTo(from);
        assertThat(result.barsReceived()).isEqualTo(6);
        assertThat(result.barsSkipped()).isEqualTo(2);
        assertThat(result.inserted()).isEqualTo(2);
        assertThat(result.updated()).isEqualTo(1);   // the deleted bad print
        assertThat(result.latestTradingDate()).isEqualTo(LocalDate.of(2026, 10, 15));
    }

    @Test
    void coalIsMonthlyUpToTheMonthBefore() {
        when(repository.instrument(Market.COMMODITY, "COAL")).thenReturn(Optional.of(coal(LocalDate.of(2026, 8, 1))));
        when(repository.latestDate("COAL")).thenReturn(Optional.of(LocalDate.of(2026, 8, 1)),
                Optional.of(LocalDate.of(2026, 9, 1)));
        when(worldBank.monthly("Coal, Australian")).thenReturn(List.of(
                new WorldBankPinkSheetClient.MonthlyValue(LocalDate.of(2026, 7, 1), 131.9),
                new WorldBankPinkSheetClient.MonthlyValue(LocalDate.of(2026, 8, 1), 135.2),
                new WorldBankPinkSheetClient.MonthlyValue(LocalDate.of(2026, 9, 1), 147.1)));
        when(repository.dates(eq("COAL"), any(), any())).thenReturn(Set.of(LocalDate.of(2026, 8, 1)));
        when(repository.upsert(eq("COAL"), eq("worldbank"), anyList())).thenReturn(1);

        Result result = service.ingest(InstrumentIngestionService.ref(coal(null)), false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Candle>> stored = ArgumentCaptor.forClass(List.class);
        verify(repository).upsert(eq("COAL"), eq("worldbank"), stored.capture());
        assertThat(stored.getValue()).extracting(Candle::date).containsExactly(LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 9, 1));
        Candle september = stored.getValue().getLast();
        assertThat(september.open()).isEqualTo(147.1);
        assertThat(september.high()).isEqualTo(147.1);
        assertThat(september.low()).isEqualTo(147.1);
        assertThat(result.inserted()).isEqualTo(1);
        verify(repository, never()).delete(any(), anyList());
    }

    @Test
    void theFirstRunLoadsTheWholeHistory() {
        when(repository.instrument(Market.FOREX, "USDIDR")).thenReturn(Optional.of(usdIdr(null)));
        when(repository.latestDate("USDIDR")).thenReturn(Optional.empty(), Optional.of(LocalDate.of(2026, 10, 15)));
        when(yahoo.dailyBars(eq("IDR=X"), any(), eq(LocalDate.of(2001, 1, 1)), any())).thenReturn(List.of(
                bar("2026-10-14", 17920), bar("2026-10-15", 17930)));
        when(repository.dates(eq("USDIDR"), any(), any())).thenReturn(Set.of());
        when(repository.upsert(eq("USDIDR"), eq("yahoo"), anyList())).thenReturn(2);
        Result result = service.ingest(InstrumentIngestionService.ref(usdIdr(null)), false);
        assertThat(result.fullHistory()).isTrue();
        assertThat(result.inserted()).isEqualTo(2);
    }
}
