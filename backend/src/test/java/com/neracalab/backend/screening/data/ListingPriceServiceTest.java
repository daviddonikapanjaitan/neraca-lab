package com.neracalab.backend.screening.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.price.PriceProperties;
import com.neracalab.backend.price.provider.DailyBar;
import com.neracalab.backend.price.provider.PriceHistory;
import com.neracalab.backend.price.provider.PriceProvider;
import com.neracalab.backend.price.provider.RateLimitedException;
import com.neracalab.backend.price.provider.SymbolNotFoundException;
import com.neracalab.backend.technical.screening.TechnicalScreeningProperties;

/** The price history of the screening universe without a database or the network: what is fetched and stored. */
class ListingPriceServiceTest {

    private PriceProvider provider;
    private ListingPriceRepository repository;
    private ListingPriceService service;
    private LocalDate lastCompleted;

    @BeforeEach
    void setUp() {
        provider = mock(PriceProvider.class);
        repository = mock(ListingPriceRepository.class);
        PriceProperties prices = new Binder(new MapConfigurationPropertySource(Map.of("neracalab.prices.provider", "yahoo")))
                .bind("neracalab.prices", PriceProperties.class).get();
        TechnicalScreeningProperties properties = new TechnicalScreeningProperties(0.5, 0.2, 3, 100, 50, 0.4, 120,
                Duration.ofDays(7), 700, 16000, new TechnicalScreeningProperties.PriceHistory(5, true));
        service = new ListingPriceService(provider, prices, repository, properties);
        lastCompleted = prices.lastCompletedTradingDay(ZonedDateTime.now(Exchange.IDX.zone()));
        when(repository.write(anyLong(), anyList(), anyBoolean())).thenAnswer(inv -> ((List<?>) inv.getArgument(1)).size());
    }

    private static DailyBar bar(LocalDate date, double close) {
        BigDecimal c = BigDecimal.valueOf(close);
        return new DailyBar(date, c, c, c, c, c, 1_000L);
    }

    /** Weekday bars ending at {@code last}, one per trading day back to {@code first}. */
    private static List<DailyBar> bars(LocalDate first, LocalDate last, double close) {
        List<DailyBar> out = new ArrayList<>();
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() <= 5) {
                out.add(bar(d, close));
            }
        }
        return out;
    }

    @Test
    void aListingWithoutBarsGetsFiveYearsAndOneUpToDateIsSkipped() {
        when(repository.lastDate(1L)).thenReturn(Optional.empty());
        when(repository.lastDate(2L)).thenReturn(Optional.of(lastCompleted));
        when(provider.fetch(eq(Exchange.IDX), eq("AAAA"), any(), any())).thenAnswer(inv -> new PriceHistory("AAAA.JK",
                "IDR", bars(inv.getArgument(2), lastCompleted, 500)));

        ListingPriceService.Result r = service.update(Exchange.IDX, List.of(new ListingPriceService.Target(1, "AAAA"),
                new ListingPriceService.Target(2, "BBBB")), false, (done, total, ticker) -> { });

        assertThat(r.loaded()).isEqualTo(1);
        assertThat(r.upToDate()).isEqualTo(1);
        verify(provider).fetch(eq(Exchange.IDX), eq("AAAA"), eq(LocalDate.now(Exchange.IDX.zone()).minusYears(5)), any());
        verify(provider, never()).fetch(any(), eq("BBBB"), any(), any());
        // a first load replaces nothing that is stored
        verify(repository).write(eq(1L), anyList(), eq(true));
    }

    @Test
    void aStoredListingFetchesFromTenDaysBeforeItsLastBarAndReplacesItsHistoryAfterAReAdjustment() {
        // a trading day (the last completed day is one): the overlap has a fetched bar on it
        LocalDate last = lastCompleted.minusWeeks(1);
        when(repository.lastDate(1L)).thenReturn(Optional.of(last));
        when(repository.lastDate(2L)).thenReturn(Optional.of(last));
        // AAAA: the overlap matches; BBBB: the provider halved every close (a 1:2 split)
        when(repository.closes(eq(1L), any())).thenReturn(Map.of(last, new BigDecimal("500.0")));
        when(repository.closes(eq(2L), any())).thenReturn(Map.of(last, new BigDecimal("1000.0")));
        when(provider.fetch(eq(Exchange.IDX), any(), any(), any())).thenAnswer(inv -> new PriceHistory("X.JK", "IDR",
                bars(inv.getArgument(2), lastCompleted, 500)));

        ListingPriceService.Result r = service.update(Exchange.IDX, List.of(new ListingPriceService.Target(1, "AAAA"),
                new ListingPriceService.Target(2, "BBBB")), false, (done, total, ticker) -> { });

        assertThat(r.loaded()).isEqualTo(2);
        assertThat(r.reAdjusted()).isEqualTo(1);
        verify(provider).fetch(eq(Exchange.IDX), eq("AAAA"), eq(last.minusDays(10)), any());
        verify(repository).write(eq(1L), anyList(), eq(false));
        verify(provider, times(2)).fetch(eq(Exchange.IDX), eq("BBBB"), any(), any());
        verify(repository).write(eq(2L), anyList(), eq(true));
    }

    @Test
    void aRateLimitStopsTheUpdateAndAnUnknownSymbolIsCounted() {
        when(repository.lastDate(anyLong())).thenReturn(Optional.empty());
        when(provider.fetch(any(), eq("AAAA"), any(), any())).thenThrow(new SymbolNotFoundException("no data for AAAA.JK"));
        when(provider.fetch(any(), eq("BBBB"), any(), any())).thenThrow(new RateLimitedException("HTTP 429", null));

        ListingPriceService.Result r = service.update(Exchange.IDX, List.of(new ListingPriceService.Target(1, "AAAA"),
                new ListingPriceService.Target(2, "BBBB"), new ListingPriceService.Target(3, "CCCC")), false,
                (done, total, ticker) -> { });

        assertThat(r.notFound()).isEqualTo(1);
        assertThat(r.rateLimited()).isTrue();
        assertThat(r.loaded()).isZero();
        verify(provider, never()).fetch(any(), eq("CCCC"), any(), any());
    }
}
