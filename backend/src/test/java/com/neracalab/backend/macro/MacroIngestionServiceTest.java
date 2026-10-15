package com.neracalab.backend.macro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.neracalab.backend.crawl.SourceException;
import com.neracalab.backend.macro.MacroRepository.Row;
import com.neracalab.backend.macro.source.MacroSource;

/**
 * One macro ingestion: the window (the overlap before the latest observation, the whole history the first time), the
 * checks before anything is stored (range, a date twice or in the future), large moves as warnings, revisions counted,
 * the period end and release date of every observation. The source and the database are mocks.
 */
class MacroIngestionServiceTest {

    private static final ZoneId JAKARTA = ZoneId.of("Asia/Jakarta");

    private final MacroRepository repository = mock(MacroRepository.class);
    private final List<LocalDate> asked = new ArrayList<>();
    private List<MacroSource.Observation> answer = List.of();
    private final MacroSource source = new MacroSource() {
        @Override
        public String name() {
            return "fred";
        }

        @Override
        public List<Observation> fetch(MacroSeries series, LocalDate from, LocalDate today) {
            asked.add(from);
            return answer;
        }
    };
    private final MacroIngestionService service = new MacroIngestionService(repository, List.of(source),
            new MacroProperties("f", "c", "t", "s", 45, 300, new MacroProperties.Schedule(false, "0 0 20 * * *", "Asia/Jakarta")),
            Clock.fixed(ZonedDateTime.of(2026, 10, 16, 9, 0, 0, 0, JAKARTA).toInstant(), JAKARTA));

    private static MacroSource.Observation obs(String date, String value) {
        return new MacroSource.Observation(LocalDate.parse(date), new BigDecimal(value));
    }

    @Test
    void incrementalFetchStartsAnOverlapBeforeTheLatestAndCountsRevisions() {
        MacroSeries cpi = MacroSeriesTest.series("US_CPI_INDEX", "MONTHLY", 15);
        when(repository.latestDate("US_CPI_INDEX")).thenReturn(Optional.of(LocalDate.of(2026, 8, 1)),
                Optional.of(LocalDate.of(2026, 9, 1)));
        answer = List.of(obs("2026-06-01", "330.1"), obs("2026-07-01", "333.5"), obs("2026-08-01", "334.9"),
                obs("2026-09-01", "336.298"));
        when(repository.values(eq("US_CPI_INDEX"), any(), any())).thenReturn(Map.of(
                LocalDate.of(2026, 7, 1), new BigDecimal("333.50000000"), LocalDate.of(2026, 8, 1), new BigDecimal("334.7")));

        MacroIngestionService.Result result = service.ingest(cpi, false);

        assertThat(asked).containsExactly(LocalDate.of(2026, 6, 17));   // 45 days before 2026-08-01
        assertThat(result.received()).isEqualTo(4);
        assertThat(result.kept()).isEqualTo(3);                          // June is before the window
        assertThat(result.inserted()).isEqualTo(1);
        assertThat(result.revised()).isEqualTo(1);
        assertThat(result.unchanged()).isEqualTo(1);
        assertThat(result.revisions()).containsExactly("2026-08-01: 334.7 -> 334.9");
        assertThat(result.latestDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(result.latestRelease()).isEqualTo(LocalDate.of(2026, 10, 15));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Row>> rows = ArgumentCaptor.forClass(List.class);
        verify(repository).upsert(eq("US_CPI_INDEX"), eq("fred"), rows.capture());
        Row september = rows.getValue().getLast();
        assertThat(september.periodEnd()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(september.releaseDate()).isEqualTo(LocalDate.of(2026, 10, 15));
    }

    @Test
    void theFirstRunFetchesTheWholeHistory() {
        MacroSeries y10 = MacroSeriesTest.series("US_10Y", "DAILY", 1);
        when(repository.latestDate("US_10Y")).thenReturn(Optional.empty());
        answer = List.of();
        MacroIngestionService.Result result = service.ingest(y10, false);
        assertThat(asked).containsExactly(LocalDate.of(2000, 1, 1));
        assertThat(result.fullHistory()).isTrue();
        verify(repository, never()).upsert(any(), any(), anyList());
    }

    @Test
    void aValueOutOfRangeStoresNothing() {
        MacroSeries y10 = MacroSeriesTest.series("US_10Y", "DAILY", 1);
        when(repository.latestDate("US_10Y")).thenReturn(Optional.of(LocalDate.of(2026, 10, 1)));
        answer = List.of(obs("2026-10-13", "5.21"), obs("2026-10-14", "5210"));
        assertThatThrownBy(() -> service.ingest(y10, false)).isInstanceOf(SourceException.class)
                .hasMessageContaining("outside the range").hasMessageContaining("nothing was stored");
        verify(repository, never()).upsert(any(), any(), anyList());
    }

    @Test
    void aDateTwiceOrInTheFutureStoresNothing() {
        MacroSeries y10 = MacroSeriesTest.series("US_10Y", "DAILY", 1);
        assertThatThrownBy(() -> service.store(y10, List.of(obs("2026-10-13", "5.2"), obs("2026-10-13", "5.3")), "manual",
                0, null, false, 2)).isInstanceOf(SourceException.class).hasMessageContaining("twice");
        assertThatThrownBy(() -> service.store(y10, List.of(obs("2026-10-17", "5.2")), "manual", 0, null, false, 1))
                .isInstanceOf(SourceException.class).hasMessageContaining("future");
        verify(repository, never()).upsert(any(), any(), anyList());
    }

    @Test
    void aLargeMoveIsAWarningAndStillStored() {
        MacroSeries y10 = MacroSeriesTest.series("US_10Y", "DAILY", 1);
        when(repository.values(eq("US_10Y"), any(), any())).thenReturn(Map.of());
        when(repository.latestDate("US_10Y")).thenReturn(Optional.of(LocalDate.of(2026, 10, 14)));
        MacroIngestionService.Result result = service.store(y10, List.of(obs("2026-10-13", "5.2"), obs("2026-10-14", "6.9")),
                "manual", 0, null, false, 2);
        assertThat(result.warnings()).containsExactly("2026-10-14: moved 1.7 % from 5.2 to 6.9");
        assertThat(result.inserted()).isEqualTo(2);
        verify(repository).upsert(eq("US_10Y"), eq("manual"), anyList());
    }

    @Test
    void aManualSeriesIsNotFetched() {
        MacroSeries manual = new MacroSeries("CN_PMI_MFG", "PMI", "CN", "ACTIVITY", "index", "MONTHLY", "manual", null, null, 1,
                3, "DRUCKENMILLER", BigDecimal.ONE, BigDecimal.TEN, null, LocalDate.of(2010, 1, 1), null);
        assertThatThrownBy(() -> service.ingest(manual, false)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("upload a CSV");
    }
}
