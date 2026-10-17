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

import com.neracalab.backend.macro.source.FredSource;
import com.neracalab.backend.macro.source.MacroSource;
import com.neracalab.backend.market.InstrumentRepository.Instrument;
import com.neracalab.backend.price.InstrumentIngestion.InstrumentRef;
import com.neracalab.backend.price.PriceIngestionService.Result;
import com.neracalab.backend.technical.YahooChartClient;
import com.neracalab.backend.technical.engine.Candle;

/**
 * The ingestion of a Forex pair or commodity: the missing days only (with an overlap for the cleaning), no request when
 * the last completed day is stored, never today's unfinished bar, bad prints dropped and deleted; coal monthly from the
 * World Bank up to the month before; palm oil in ringgit as CPO=F x MYR=X. Yahoo Finance, the World Bank and the database
 * are mocks.
 */
class InstrumentIngestionServiceTest {

    private final InstrumentRepository repository = mock(InstrumentRepository.class);
    private final YahooChartClient yahoo = mock(YahooChartClient.class);
    private final WorldBankPinkSheetClient worldBank = mock(WorldBankPinkSheetClient.class);
    private final ImfPcpsClient imf = mock(ImfPcpsClient.class);
    private final FredSource fred = mock(FredSource.class);
    /** Friday 2026-10-16, 08:00 UTC: 09:00 in London, 04:00 in New York */
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-16T08:00:00Z"), ZoneOffset.UTC);
    private final InstrumentIngestionService service = new InstrumentIngestionService(repository, yahoo, worldBank, imf, fred, clock);

    private static Instrument usdIdr(LocalDate last) {
        return new Instrument("USDIDR", 1, Market.FOREX, "USD/IDR", "yahoo", "IDR=X", "IDR", "IDR per USD", "DAILY",
                BigDecimal.ONE, new BigDecimal("0.03"), LocalDate.of(2001, 1, 1), null, last == null ? 0 : 100, null, last);
    }

    private static Instrument coal(LocalDate last) {
        return new Instrument("COAL", 5, Market.COMMODITY, "Coal (Newcastle)", "worldbank", "Coal, Australian", "USD",
                "USD per metric ton", "MONTHLY", new BigDecimal("0.01"), null, LocalDate.of(1979, 1, 1), null,
                last == null ? 0 : 500, null, last);
    }

    private static Instrument palmOilMyr(LocalDate last) {
        return new Instrument("PALM_OIL_MYR", 18, Market.COMMODITY, "Palm Oil (MYR)", "yahoo", "CPO=F*MYR=X", "MYR",
                "MYR per metric ton", "DAILY", BigDecimal.ONE, new BigDecimal("0.10"), LocalDate.of(2010, 6, 1), null,
                last == null ? 0 : 100, null, last);
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

    /**
     * CPO=F (USD per tonne) times MYR=X of the same day; a day without a rate takes the last one up to 5 days before
     * (a Malaysian holiday), and a price whose last rate is older is not stored.
     */
    @Test
    void palmOilInRinggitIsTheUsdPriceTimesTheRateOfTheDay() {
        when(repository.instrument(Market.COMMODITY, "PALM_OIL_MYR")).thenReturn(Optional.of(palmOilMyr(LocalDate.of(2026, 10, 12))));
        when(repository.latestDate("PALM_OIL_MYR")).thenReturn(Optional.of(LocalDate.of(2026, 10, 12)),
                Optional.of(LocalDate.of(2026, 10, 15)));
        LocalDate from = LocalDate.of(2026, 9, 28);
        when(yahoo.dailyBars(eq("CPO=F"), eq(Market.COMMODITY.zone()), eq(from), eq(LocalDate.of(2026, 10, 16))))
                .thenReturn(List.of(bar("2026-09-28", 1100), bar("2026-10-13", 1140), bar("2026-10-14", 1150),
                        bar("2026-10-15", 1147.25)));
        when(yahoo.dailyBars(eq("MYR=X"), eq(Market.COMMODITY.zone()), eq(from.minusDays(5)), eq(LocalDate.of(2026, 10, 16))))
                .thenReturn(List.of(bar("2026-10-12", 4.08), bar("2026-10-13", 4.10), bar("2026-10-15", 4.0795)));
        when(repository.dates(eq("PALM_OIL_MYR"), any(), any())).thenReturn(Set.of());
        when(repository.upsert(eq("PALM_OIL_MYR"), eq("yahoo"), anyList())).thenReturn(3);

        service.ingest(InstrumentIngestionService.ref(palmOilMyr(null)), false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Candle>> stored = ArgumentCaptor.forClass(List.class);
        verify(repository).upsert(eq("PALM_OIL_MYR"), eq("yahoo"), stored.capture());
        // 28 Sep has no rate within 5 days; 14 Oct takes the rate of 13 Oct
        assertThat(stored.getValue()).extracting(Candle::date).containsExactly(LocalDate.of(2026, 10, 13),
                LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 15));
        assertThat(stored.getValue()).extracting(Candle::close).containsExactly(4674.0, 4715.0, 4680.2064);
        Candle last = stored.getValue().getLast();
        assertThat(last.high()).isEqualTo(4721.0014);   // (1,147.25 + 10) x 4.0795
        assertThat(last.low()).isEqualTo(4639.4114);    // (1,147.25 - 10) x 4.0795
    }

    /** Cobalt from the IMF and the wood pulp index from FRED: monthly bars dated on the 1st, up to the month before. */
    @Test
    void cobaltFromTheImfAndPulpFromFredAreMonthly() {
        Instrument cobalt = new Instrument("COBALT", 20, Market.COMMODITY, "Cobalt", "imf", "PCOBA", "USD", "USD per metric ton",
                "MONTHLY", new BigDecimal("0.01"), null, LocalDate.of(1992, 1, 1), null, 0, null, null);
        when(repository.instrument(Market.COMMODITY, "COBALT")).thenReturn(Optional.of(cobalt));
        when(repository.latestDate("COBALT")).thenReturn(Optional.empty(), Optional.of(LocalDate.of(2026, 9, 1)));
        when(imf.monthly("PCOBA")).thenReturn(List.of(
                new WorldBankPinkSheetClient.MonthlyValue(LocalDate.of(2026, 8, 1), 55860.0),
                new WorldBankPinkSheetClient.MonthlyValue(LocalDate.of(2026, 9, 1), 42212.62),
                new WorldBankPinkSheetClient.MonthlyValue(LocalDate.of(2026, 10, 1), 41000.0)));   // not final yet
        when(repository.dates(eq("COBALT"), any(), any())).thenReturn(Set.of());
        when(repository.upsert(eq("COBALT"), eq("imf"), anyList())).thenReturn(2);

        service.ingest(InstrumentIngestionService.ref(cobalt), false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Candle>> stored = ArgumentCaptor.forClass(List.class);
        verify(repository).upsert(eq("COBALT"), eq("imf"), stored.capture());
        assertThat(stored.getValue()).extracting(Candle::date).containsExactly(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1));
        assertThat(stored.getValue().getLast().low()).isEqualTo(42212.62);

        Instrument pulp = new Instrument("PULP", 21, Market.COMMODITY, "Wood Pulp (US PPI)", "fred", "WPU0911", "USD",
                "index, 1982 = 100", "MONTHLY", new BigDecimal("0.001"), null, LocalDate.of(1990, 1, 1), null, 0, null, null);
        when(repository.instrument(Market.COMMODITY, "PULP")).thenReturn(Optional.of(pulp));
        when(repository.latestDate("PULP")).thenReturn(Optional.of(LocalDate.of(2026, 8, 1)), Optional.of(LocalDate.of(2026, 9, 1)));
        when(fred.observations("WPU0911", LocalDate.of(2026, 8, 1))).thenReturn(List.of(
                new MacroSource.Observation(LocalDate.of(2026, 8, 1), new BigDecimal("219.930")),
                new MacroSource.Observation(LocalDate.of(2026, 9, 1), new BigDecimal("222.983"))));
        when(repository.dates(eq("PULP"), any(), any())).thenReturn(Set.of(LocalDate.of(2026, 8, 1)));
        when(repository.upsert(eq("PULP"), eq("fred"), anyList())).thenReturn(1);

        Result result = service.ingest(InstrumentIngestionService.ref(pulp), false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Candle>> pulpBars = ArgumentCaptor.forClass(List.class);
        verify(repository).upsert(eq("PULP"), eq("fred"), pulpBars.capture());
        assertThat(pulpBars.getValue()).extracting(Candle::close).containsExactly(219.93, 222.983);
        assertThat(result.inserted()).isEqualTo(1);
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
