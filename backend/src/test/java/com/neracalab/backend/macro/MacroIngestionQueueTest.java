package com.neracalab.backend.macro;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

/**
 * The macro queue's follow-up of a job: a fetch that stopped early is continued from where it stopped, a series that
 * read everything queues the series derived from it (a rainfall series' SPI), and continuations that read nothing stop
 * after {@link MacroIngestionQueue#MAX_IDLE_CONTINUATIONS}. The runner is a stub; no database.
 */
class MacroIngestionQueueTest {

    private static MacroSeries series(String code, String source, String ref) {
        return new MacroSeries(code, code, "ID", "WEATHER_RAIN", "mm", "MONTHLY", source, ref, null, 15, 1, "DALIO",
                BigDecimal.ZERO, new BigDecimal("3000"), null, LocalDate.of(1981, 1, 1), null);
    }

    private static MacroIngestionService.Result result(int kept, LocalDate resumeFrom, Duration after) {
        return new MacroIngestionService.Result("chirps", 1, null, false, kept, kept, kept, 0, 0, null, null, List.of(),
                List.of(), resumeFrom == null ? null : "stopped early", resumeFrom, after);
    }

    private static void await(List<String> calls, int n) throws InterruptedException {
        long until = System.currentTimeMillis() + 10_000;
        while (calls.size() < n && System.currentTimeMillis() < until) {
            Thread.sleep(20);
        }
    }

    @Test
    void aStoppedFetchContinuesWhereItStoppedThenItsSpiFollows() throws InterruptedException {
        MacroSeries rain = series("ID_RAIN_KAL_PALM", "chirps", "ID_KAL_PALM");
        MacroSeries spi = series("ID_SPI3_KAL_PALM", "spi", "ID_RAIN_KAL_PALM");
        List<String> calls = new CopyOnWriteArrayList<>();
        MacroIngestionQueue queue = new MacroIngestionQueue((s, full, resumeFrom) -> {
            calls.add(s.code() + "@" + resumeFrom);
            if (s == rain && resumeFrom == null) {
                return result(60, LocalDate.of(1986, 1, 1), Duration.ZERO);
            }
            return result(10, null, null);
        }, job -> { }, 50, s -> s == rain ? List.of(spi) : List.of());
        queue.start();
        try {
            queue.submit(rain, false, null);
            await(calls, 3);
        } finally {
            queue.stop();
        }
        assertThat(calls).containsExactly("ID_RAIN_KAL_PALM@null", "ID_RAIN_KAL_PALM@1986-01-01", "ID_SPI3_KAL_PALM@null");
    }

    @Test
    void continuationsThatReadNothingStop() throws InterruptedException {
        MacroSeries rain = series("ID_RAIN_JAVA_RICE", "chirps", "ID_JAVA_RICE");
        List<String> calls = new CopyOnWriteArrayList<>();
        MacroIngestionQueue queue = new MacroIngestionQueue((s, full, resumeFrom) -> {
            calls.add(s.code() + "@" + resumeFrom);
            return result(0, LocalDate.of(1981, 1, 1), Duration.ZERO);
        }, job -> { }, 50, s -> List.of());
        queue.start();
        try {
            queue.submit(rain, false, null);
            await(calls, 1 + MacroIngestionQueue.MAX_IDLE_CONTINUATIONS);
            Thread.sleep(300);   // no further continuation arrives
        } finally {
            queue.stop();
        }
        assertThat(calls).hasSize(1 + MacroIngestionQueue.MAX_IDLE_CONTINUATIONS);
    }
}
