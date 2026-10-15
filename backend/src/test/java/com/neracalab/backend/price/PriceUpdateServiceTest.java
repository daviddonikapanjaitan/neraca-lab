package com.neracalab.backend.price;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.price.IndexIngestion.IndexRef;
import com.neracalab.backend.price.PriceDailyRepository.CompanyLatest;
import com.neracalab.backend.price.PriceDailyRepository.CompanyRef;
import com.neracalab.backend.price.PriceIngestionQueue.Submission;

/**
 * "Update all companies" of Ingestion › Price Ingestion: only the companies whose prices end before the last completed
 * trading day are queued (a company without prices too: its history), a company with an active job keeps it, and the
 * index is queued unless it is up to date.
 */
class PriceUpdateServiceTest {

    private static final LocalDate LAST = LocalDate.of(2026, 10, 15);
    private static final IndexRef IHSG = new IndexRef("IHSG", "^JKSE", "IDX Composite Index (IHSG)", Exchange.IDX);

    private final PriceIngestionQueue queue = mock(PriceIngestionQueue.class);
    private final PriceIngestionService service = mock(PriceIngestionService.class);
    private final PriceDailyRepository prices = mock(PriceDailyRepository.class);
    private final IndexIngestion index = mock(IndexIngestion.class);
    private final PriceUpdateService updates = new PriceUpdateService(queue, service, prices, index);

    private final CompanyRef antm = company(1, "ANTM");
    private final CompanyRef bbca = company(2, "BBCA");
    private final CompanyRef bach = company(3, "BACH");
    private final CompanyRef hrta = company(4, "HRTA");

    private static CompanyRef company(long id, String ticker) {
        return new CompanyRef(id, Exchange.IDX, ticker, "IDR");
    }

    @BeforeEach
    void setUp() {
        when(service.lastCompletedTradingDay(Exchange.IDX)).thenReturn(LAST);
        when(prices.activeCompaniesWithLatest(Exchange.IDX)).thenReturn(List.of(
                new CompanyLatest(antm, LocalDate.of(2026, 10, 9)),     // behind: 9 Oct -> 15 Oct
                new CompanyLatest(bbca, LAST),                          // up to date
                new CompanyLatest(bach, null),                          // no prices yet
                new CompanyLatest(hrta, LocalDate.of(2026, 10, 14))));  // behind, already queued
        when(queue.submit(any(CompanyRef.class), eq(false), any())).thenAnswer(inv -> {
            CompanyRef c = inv.getArgument(0);
            return new Submission(new PriceIngestionJob(c, false, null), !c.ticker().equals("HRTA"));
        });
        when(queue.submitIndex(any(), anyBoolean(), any()))
                .thenAnswer(inv -> new Submission(new PriceIngestionJob((IndexRef) inv.getArgument(0), false, null), true));
        when(queue.providerName()).thenReturn("yahoo");
        when(index.index(Exchange.IDX)).thenReturn(Optional.of(IHSG));
        when(index.sourceName()).thenReturn("yahoo");
    }

    @Test
    void onlyTheCompaniesBehindAreQueued() {
        when(index.stored(IHSG)).thenReturn(new IndexIngestion.Stored(8890, LocalDate.of(1990, 4, 6), LocalDate.of(2026, 10, 14)));

        PriceUpdateService.Update update = updates.updateAll(Exchange.IDX, true, null);

        assertThat(update.companies()).isEqualTo(4);
        assertThat(update.queued()).isEqualTo(2);           // ANTM, BACH
        assertThat(update.withoutPrices()).isEqualTo(1);    // BACH
        assertThat(update.alreadyActive()).isEqualTo(1);    // HRTA
        assertThat(update.upToDate()).isEqualTo(1);         // BBCA
        assertThat(update.lastCompletedTradingDay()).isEqualTo(LAST);
        assertThat(update.firstJob().ticker()).isEqualTo("ANTM");
        verify(queue, never()).submit(eq(bbca), anyBoolean(), any());
        verify(queue).submit(antm, false, null);            // never the full history for a company with prices
        assertThat(update.index().outcome()).isEqualTo("QUEUED");
        assertThat(update.index().job().index()).isTrue();
        assertThat(update.index().job().ticker()).isEqualTo("IHSG");
    }

    @Test
    void anIndexUpToDateIsNotQueued() {
        when(index.stored(IHSG)).thenReturn(new IndexIngestion.Stored(8891, LocalDate.of(1990, 4, 6), LAST));

        PriceUpdateService.Update update = updates.updateAll(Exchange.IDX, true, null);

        assertThat(update.index().outcome()).isEqualTo("UP_TO_DATE");
        verify(queue, never()).submitIndex(any(), anyBoolean(), any());
        assertThat(updates.updateAll(Exchange.IDX, false, null).index()).isNull();
    }

    @Test
    void theStatusCountsTheCompaniesAgainstTheLastCompletedDay() {
        when(index.stored(IHSG)).thenReturn(new IndexIngestion.Stored(8891, LocalDate.of(1990, 4, 6), LAST));

        PriceUpdateService.Status status = updates.status(Exchange.IDX);

        assertThat(status.companies()).isEqualTo(4);
        assertThat(status.upToDate()).isEqualTo(1);
        assertThat(status.behind()).isEqualTo(2);
        assertThat(status.withoutPrices()).isEqualTo(1);
        assertThat(status.oldestLatestDate()).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(status.newestLatestDate()).isEqualTo(LAST);
        assertThat(status.index().code()).isEqualTo("IHSG");
        assertThat(status.index().upToDate()).isTrue();
        assertThat(status.index().rows()).isEqualTo(8891);
    }

    @Test
    void anExchangeWithoutIndexHasNone() {
        when(index.index(Exchange.IDX)).thenReturn(Optional.empty());

        assertThat(updates.status(Exchange.IDX).index()).isNull();
        assertThat(updates.updateAll(Exchange.IDX, true, null).index()).isNull();
        assertThatThrownBy(() -> updates.submitIndex(Exchange.IDX, false, null))
                .isInstanceOf(PriceUpdateService.NoIndexException.class);
    }
}
