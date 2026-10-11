package com.neracalab.backend.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.ingestion.agent.IngestionSession;
import com.neracalab.backend.ingestion.mapping.FilingMapper;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;
import com.neracalab.backend.price.provider.RateLimitedException;
import com.neracalab.backend.screening.data.YahooFundamentalsClient;
import com.neracalab.backend.screening.data.YahooFundamentalsClient.ShareCount;

/** The web fallback for share counts, with a mocked Yahoo client (no network). */
class WebShareCountsTest {

    private final YahooFundamentalsClient yahoo = mock(YahooFundamentalsClient.class);

    @Test
    void completesAFilingWithoutShareCounts() throws Exception {
        IngestionSession session = session("BNGA", "2025-Tahunan");
        when(yahoo.shareCounts(Exchange.IDX, "BNGA")).thenReturn(List.of(
                new ShareCount(LocalDate.parse("2024-12-31"), 25_137_965_543L, 25_142_205_843L, 4_240_300L),
                new ShareCount(LocalDate.parse("2025-12-31"), 25_140_519_043L, 25_142_205_843L, 1_686_800L)));

        new WebShareCounts(yahoo, true).complete(session);

        assertThat(session.shareCapital().resolved()).isTrue();
        assertThat(session.shareCapital().webSource()).isEqualTo("Yahoo Finance");
        assertThat(session.notes()).anyMatch(n -> n.contains("25140519043"));
    }

    @Test
    void failedFetchLeavesTheFilingAsItIs() throws Exception {
        IngestionSession session = session("BNGA", "2025-Tahunan");
        var before = session.shareCapital();
        when(yahoo.shareCounts(any(), anyString())).thenThrow(new RateLimitedException("HTTP 429", null));

        new WebShareCounts(yahoo, true).complete(session);

        assertThat(session.shareCapital()).isSameAs(before);
        assertThat(session.notes()).anyMatch(n -> n.contains("could not be read"));
    }

    @Test
    void notFetchedWhenDisabledOrWhenTheFilingHasCounts() throws Exception {
        new WebShareCounts(yahoo, false).complete(session("BNGA", "2025-Tahunan"));
        IngestionSession hrta = session("HRTA", "2025-Tahunan");
        assumeTrue(hrta.shareCapital().resolved(), "HRTA's filing gives share counts");
        new WebShareCounts(yahoo, true).complete(hrta);

        verify(yahoo, never()).shareCounts(any(), anyString());
    }

    private static IngestionSession session(String ticker, String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", ticker, "xlsx", "FinancialStatement-" + filing + "-" + ticker + ".xlsx");
        assumeTrue(Files.exists(file), ticker + " source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new IngestionSession(new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString())));
        }
    }
}
