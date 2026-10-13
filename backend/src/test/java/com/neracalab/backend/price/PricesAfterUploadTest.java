package com.neracalab.backend.price;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.job.Requester;
import com.neracalab.backend.price.PriceDailyRepository.CompanyRef;

/**
 * The prices of a company whose filing was stored: queued when they are missing or behind, for the user who
 * uploaded the filing; never when they are up to date, the company is unknown or the feature is off.
 */
class PricesAfterUploadTest {

    private static final CompanyRef DSNG = new CompanyRef(42, Exchange.IDX, "DSNG", "IDR");
    private static final Requester UPLOADER = new Requester(7, "analyst");

    private final PriceIngestionQueue queue = mock(PriceIngestionQueue.class);
    private final PriceIngestionService service = mock(PriceIngestionService.class);
    private final PriceDailyRepository prices = mock(PriceDailyRepository.class);

    @Test
    void queuesThePricesOfACompanyThatIsBehind() {
        PriceIngestionJob job = new PriceIngestionJob(DSNG, false, UPLOADER);
        when(prices.company(Exchange.IDX, "DSNG")).thenReturn(Optional.of(DSNG));
        when(service.upToDate(DSNG)).thenReturn(false);
        when(queue.submit(DSNG, false, UPLOADER)).thenReturn(new PriceIngestionQueue.Submission(job, true));

        assertThat(new PricesAfterUpload(queue, service, prices, true).queue(Exchange.IDX, "DSNG", UPLOADER))
                .contains(job.id());

        // never full=true: the ingestion itself fetches the whole history of a company without prices
        verify(queue).submit(DSNG, false, UPLOADER);
    }

    /** Five filings of one company stored together: the job that is already active is the one that loads them. */
    @Test
    void returnsThePriceJobThatIsAlreadyActive() {
        PriceIngestionJob active = new PriceIngestionJob(DSNG, false, UPLOADER);
        when(prices.company(Exchange.IDX, "DSNG")).thenReturn(Optional.of(DSNG));
        when(queue.submit(DSNG, false, UPLOADER)).thenReturn(new PriceIngestionQueue.Submission(active, false));

        assertThat(new PricesAfterUpload(queue, service, prices, true).queue(Exchange.IDX, "DSNG", UPLOADER))
                .contains(active.id());
    }

    @Test
    void queuesNothingWhenThePricesAreUpToDate() {
        when(prices.company(Exchange.IDX, "DSNG")).thenReturn(Optional.of(DSNG));
        when(service.upToDate(DSNG)).thenReturn(true);

        assertThat(new PricesAfterUpload(queue, service, prices, true).queue(Exchange.IDX, "DSNG", UPLOADER)).isEmpty();

        verify(queue, never()).submit(any(), anyBoolean(), any());
    }

    @Test
    void queuesNothingForAnUnknownCompanyOrWithoutATicker() {
        when(prices.company(Exchange.IDX, "XXXX")).thenReturn(Optional.empty());
        PricesAfterUpload afterUpload = new PricesAfterUpload(queue, service, prices, true);

        assertThat(afterUpload.queue(Exchange.IDX, "XXXX", UPLOADER)).isEmpty();
        assertThat(afterUpload.queue(Exchange.IDX, null, UPLOADER)).isEmpty();
        assertThat(afterUpload.queue(Exchange.IDX, " ", UPLOADER)).isEmpty();

        verify(queue, never()).submit(any(), anyBoolean(), any());
    }

    @Test
    void queuesNothingWhenSwitchedOff() {
        assertThat(new PricesAfterUpload(queue, service, prices, false).queue(Exchange.IDX, "DSNG", UPLOADER)).isEmpty();

        verify(prices, never()).company(any(), any());
        verify(queue, never()).submit(any(), anyBoolean(), any());
    }
}
